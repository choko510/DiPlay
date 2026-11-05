package com.shilapi.xcertplay.transport

import com.sun.management.ThreadMXBean
import com.shilapi.xcertplay.transport.EthernetIpv6Codec.parseIpv6View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

class WiredStartupTimingOverheadTest {
    @Test
    fun reportsReproducibleCpuAndAllocationDeltaForTheNcmReceivePath() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(
            "This measurement requires per-thread CPU and allocation counters",
            bean != null && bean.isThreadCpuTimeSupported && bean.isThreadAllocatedMemorySupported,
        )
        bean ?: return
        if (!bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true

        val frame = tcpAckFrame()
        val expectedPayloadBytes = (frame.size - ETHERNET_HEADER_BYTES).toLong() * ITERATIONS
        val modes = ObservationMode.entries
        modes.forEach { runLane(it, frame, WARMUP_ITERATIONS, bean) }

        val samples = modes.associateWith { mutableListOf<Measurement>() }
        repeat(MEASUREMENT_ROUNDS) { round ->
            modes.indices.forEach { offset ->
                val mode = modes[(round + offset) % modes.size]
                samples.getValue(mode).add(runLane(mode, frame, ITERATIONS, bean))
            }
        }

        samples.values.flatten().forEach { measurement ->
            assertEquals(expectedPayloadBytes, measurement.payloadBytes)
            assertTrue(measurement.cpuNanos >= 0L)
            assertTrue(measurement.allocatedBytes >= 0L)
        }

        fun median(mode: ObservationMode, select: (Measurement) -> Long): Long =
            samples.getValue(mode).map(select).sorted()[MEASUREMENT_ROUNDS / 2]

        val baselineCpu = median(ObservationMode.BASELINE, Measurement::cpuNanos)
        val timingOffCpu = median(ObservationMode.TIMING_DEBUG_OFF, Measurement::cpuNanos)
        val timingOnCpu = median(ObservationMode.TIMING_DEBUG_ON, Measurement::cpuNanos)
        val baselineAllocated = median(ObservationMode.BASELINE, Measurement::allocatedBytes)
        val timingOffAllocated = median(ObservationMode.TIMING_DEBUG_OFF, Measurement::allocatedBytes)
        val timingOnAllocated = median(ObservationMode.TIMING_DEBUG_ON, Measurement::allocatedBytes)
        println(
            "WIRED_STARTUP_OVERHEAD_V1 " +
                "iterations=$ITERATIONS rounds=$MEASUREMENT_ROUNDS " +
                "baseline_cpu_ns_per_packet=${baselineCpu.toDouble() / ITERATIONS} " +
                "timing_off_cpu_ns_per_packet=${timingOffCpu.toDouble() / ITERATIONS} " +
                "timing_on_cpu_ns_per_packet=${timingOnCpu.toDouble() / ITERATIONS} " +
                "baseline_alloc_bytes_per_packet=${baselineAllocated.toDouble() / ITERATIONS} " +
                "timing_off_alloc_bytes_per_packet=${timingOffAllocated.toDouble() / ITERATIONS} " +
                "timing_on_alloc_bytes_per_packet=${timingOnAllocated.toDouble() / ITERATIONS}",
        )
    }

    private fun runLane(
        mode: ObservationMode,
        frame: ByteArray,
        iterations: Int,
        bean: ThreadMXBean,
    ): Measurement {
        val timing = if (mode == ObservationMode.BASELINE) null else {
            WiredStartupTiming(attemptId = 1L).also { it.recordAttachGeneration(ATTACH_GENERATION) }
        }
        val diagnostics = if (mode == ObservationMode.TIMING_DEBUG_ON) {
            WiredPathDiagnostics("timing-bench", 1, enabled = true).also {
                it.recordAttachGeneration(ATTACH_GENERATION)
            }
        } else {
            null
        }
        val threadId = Thread.currentThread().threadId()
        val cpuStart = bean.getThreadCpuTime(threadId)
        val allocatedStart = bean.getThreadAllocatedBytes(threadId)
        var payloadBytes = 0L
        var firstPayloadByteSum = 0L
        repeat(iterations) {
            if (mode != ObservationMode.BASELINE) {
                val classification = WiredTcpPacketClassifier.classifyEthernet(
                    frame,
                    expectedAddress = EXPECTED_ADDRESS,
                    expectedPort = AIRPLAY_PORT,
                )
                timing?.recordAirPlaySyn(classification, ATTACH_GENERATION)
                if (diagnostics != null) {
                    val event = when (classification.disposition) {
                        WiredPacketClassification.DISPOSITION_INVALID -> WiredPathEvent.INVALID_PACKET
                        WiredPacketClassification.DISPOSITION_UNSUPPORTED -> WiredPathEvent.UNSUPPORTED_PACKET
                        WiredPacketClassification.DISPOSITION_UNKNOWN -> WiredPathEvent.UNKNOWN_PACKET
                        else -> null
                    }
                    if (event != null) diagnostics.record(event, ATTACH_GENERATION)
                }
            }
            val view = parseIpv6View(frame) ?: error("benchmark frame did not parse as IPv6")
            payloadBytes += view.payloadLength
            firstPayloadByteSum += frame[view.payloadOffset].toLong() and 0xffL
        }
        val allocatedEnd = bean.getThreadAllocatedBytes(threadId)
        val cpuEnd = bean.getThreadCpuTime(threadId)
        assertTrue(firstPayloadByteSum > 0L)
        timing?.let { assertEquals(null, it.snapshot().firstValidAirPlaySynNs) }
        return Measurement(
            payloadBytes = payloadBytes,
            cpuNanos = (cpuEnd - cpuStart).coerceAtLeast(0L),
            allocatedBytes = (allocatedEnd - allocatedStart).coerceAtLeast(0L),
        )
    }

    private fun tcpAckFrame(): ByteArray = ByteArray(ETHERNET_HEADER_BYTES + IPV6_HEADER_BYTES + TCP_HEADER_BYTES).apply {
        this[12] = 0x86.toByte()
        this[13] = 0xdd.toByte()
        this[ETHERNET_HEADER_BYTES] = 0x60
        writeU16(this, ETHERNET_HEADER_BYTES + IPV6_PAYLOAD_LENGTH_OFFSET, TCP_HEADER_BYTES)
        this[ETHERNET_HEADER_BYTES + IPV6_NEXT_HEADER_OFFSET] = IP_TCP.toByte()
        EXPECTED_ADDRESS.copyInto(this, ETHERNET_HEADER_BYTES + IPV6_DESTINATION_OFFSET)
        val tcpOffset = ETHERNET_HEADER_BYTES + IPV6_HEADER_BYTES
        writeU16(this, tcpOffset, 50_000)
        writeU16(this, tcpOffset + 2, AIRPLAY_PORT)
        this[tcpOffset + TCP_DATA_OFFSET_OFFSET] = 0x50
        this[tcpOffset + TCP_FLAGS_OFFSET] = TCP_ACK.toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private data class Measurement(
        val payloadBytes: Long,
        val cpuNanos: Long,
        val allocatedBytes: Long,
    )

    private enum class ObservationMode {
        BASELINE,
        TIMING_DEBUG_OFF,
        TIMING_DEBUG_ON,
    }

    private companion object {
        const val ITERATIONS = 2_000_000
        const val WARMUP_ITERATIONS = 300_000
        const val MEASUREMENT_ROUNDS = 5
        const val ATTACH_GENERATION = 7L
        const val AIRPLAY_PORT = 7_000
        const val ETHERNET_HEADER_BYTES = 14
        const val IPV6_HEADER_BYTES = 40
        const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        const val IPV6_NEXT_HEADER_OFFSET = 6
        const val IPV6_DESTINATION_OFFSET = 24
        const val TCP_HEADER_BYTES = 20
        const val TCP_DATA_OFFSET_OFFSET = 12
        const val TCP_FLAGS_OFFSET = 13
        const val IP_TCP = 6
        const val TCP_ACK = 0x10
        val EXPECTED_ADDRESS = byteArrayOf(
            0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1,
        )
    }
}
