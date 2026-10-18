package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.network.WiredTcpPacketClassification
import com.shilapi.xcertplay.network.recordSynAckNcmResult
import com.shilapi.xcertplay.network.writeNcmFrameToTun
import com.shilapi.xcertplay.step4.Step4PacketFixtures
import com.shilapi.xcertplay.step4.FakeTunDevice
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredPathDiagnosticsTest {
    @Test
    fun recordsIpv4SynByFamilyOnce() {
        val diagnostics = diagnostics()
        val frame = Step4PacketFixtures.ethernetIpv4Tcp()

        val classification = diagnostics.observeEthernetFrameAt(frame, frame.size, 2_000_000L)

        assertTrue(classification.isAirPlaySyn(7_000))
        assertEquals(1L, diagnostics.count(WiredPathEvent.IPV4_SYN_TO_AIRPLAY))
        assertEquals(0L, diagnostics.count(WiredPathEvent.IPV6_SYN_TO_AIRPLAY))
    }

    @Test
    fun keepsIpv6DestinationMismatchSeparateFromSynPresence() {
        val diagnostics = diagnostics()
        val frame = Step4PacketFixtures.ethernetIpv6Tcp()
        diagnostics.setExpectedLocalIpv6Address(frame.copyOfRange(14 + 24, 14 + 40))
        frame[14 + 24 + 15] = (frame[14 + 24 + 15].toInt() xor 1).toByte()

        diagnostics.observeEthernetFrameAt(frame, frame.size, 2_000_000L)

        assertEquals(1L, diagnostics.count(WiredPathEvent.IPV6_SYN_TO_AIRPLAY))
        assertEquals(1L, diagnostics.count(WiredPathEvent.IPV6_SYN_DEST_MISMATCH))
        assertEquals(0L, diagnostics.count(WiredPathEvent.IPV6_SYN_DEST_MATCH))
    }

    @Test
    fun associatesSynAckWithNcmOutcomeWithoutCallingItAccepted() {
        val diagnostics = diagnostics()
        val frame = Step4PacketFixtures.ethernetIpv6Tcp(
            sourcePort = 7_000,
            destinationPort = 49_152,
            flags = 0x12,
        )
        val packet = frame.copyOfRange(14, 14 + 40 + 20)
        val observation = diagnostics.observeTunPacketAt(packet, packet.size, 2_000_000L)

        recordSynAckNcmResult(observation, diagnostics, NcmSendResult.NotReady)

        assertEquals(1L, diagnostics.count(WiredPathEvent.TUN_SYN_ACK_OBSERVED))
        assertEquals(1L, diagnostics.count(WiredPathEvent.SYN_ACK_NCM_NOT_READY))
        assertEquals(0L, diagnostics.count(WiredPathEvent.SYN_ACK_NCM_SENT))
        assertEquals(0L, diagnostics.count(WiredPathEvent.LISTENER_ACCEPTED))

        val sentDiagnostics = diagnostics()
        val sentObservation = sentDiagnostics.observeTunPacketAt(packet, packet.size, 2_000_000L)
        recordSynAckNcmResult(sentObservation, sentDiagnostics, NcmSendResult.Sent)
        assertEquals(1L, sentDiagnostics.count(WiredPathEvent.SYN_ACK_NCM_SENT))
        assertEquals(0L, sentDiagnostics.count(WiredPathEvent.LISTENER_ACCEPTED))
    }

    @Test
    fun recordsPacketProvenanceAndRelativeFirstTimesWithoutAddressStrings() {
        val startNanos = 10_000_000L
        val diagnostics = diagnostics(startNanos = startNanos)
        val frame = Step4PacketFixtures.ethernetIpv6Tcp()
        val expectedAddress = frame.copyOfRange(14 + 24, 14 + 40)
        diagnostics.setExpectedLocalIpv6Address(expectedAddress)
        diagnostics.setVpnAttachGeneration(7)
        diagnostics.recordAt(WiredPathEvent.START_SESSION_SENT, startNanos + 1_000_000L)

        val ethernet = diagnostics.observeEthernetFrameAt(frame, frame.size, startNanos + 2_000_000L)
        val synAck = Step4PacketFixtures.ethernetIpv6Tcp(
            sourcePort = 7_000,
            destinationPort = 49_152,
            flags = 0x12,
        ).copyOfRange(14, 14 + 40 + 20)
        val tun = diagnostics.observeTunPacketAt(synAck, synAck.size, startNanos + 4_000_000L)
        val rst = Step4PacketFixtures.ethernetIpv6Tcp(
            sourcePort = 7_000,
            destinationPort = 49_152,
            flags = 0x04,
        ).copyOfRange(14, 14 + 40 + 20)
        diagnostics.observeTunPacketAt(rst, rst.size, startNanos + 5_000_000L)
        diagnostics.recordAt(WiredPathEvent.LISTENER_ACCEPTED, startNanos + 6_000_000L)

        assertTrue(ethernet.isAirPlaySyn(7_000))
        assertEquals(WiredTcpPacketClassification.DESTINATION_MATCH, ethernet.destinationMatch)
        assertTrue(tun.isAirPlaySynAck(7_000))
        val snapshot = diagnostics.snapshot(WiredPathReportReason.ACCEPT, startNanos + 7_000_000L)
        assertNotNull(snapshot)
        assertEquals(7, snapshot?.attachGeneration)
        assertEquals(1L, snapshot?.count(WiredPathEvent.IPV6_SYN_TO_AIRPLAY))
        assertEquals(1L, snapshot?.count(WiredPathEvent.IPV6_SYN_DEST_MATCH))
        assertEquals(1L, snapshot?.count(WiredPathEvent.TUN_SYN_ACK_OBSERVED))
        assertEquals(1L, snapshot?.count(WiredPathEvent.TUN_RST_OBSERVED))
        assertEquals(1L, snapshot?.count(WiredPathEvent.LISTENER_ACCEPTED))
        assertEquals(2L, snapshot?.firstElapsedMillis(WiredPathEvent.IPV6_SYN_TO_AIRPLAY))
        assertEquals(1L, snapshot?.firstElapsedSinceStartSessionMillis(WiredPathEvent.IPV6_SYN_TO_AIRPLAY))
        val report = WiredPathReportFormatter.format(snapshot!!)
        assertTrue(report.startsWith("WIRED_PATH_V1 schema=1 runId=test-run attempt=4"))
        assertTrue(report.contains("timeBase=START_SESSION"))
        assertTrue(report.contains("v6SynToAirPlay=1@1"))
        assertFalse(report.contains("2001:db8"))
        assertFalse(report.contains("02:00:00"))
        assertFalse(report.contains("49152"))
    }

    @Test
    fun sealIsIdempotentImmutableAndRejectsLateEvents() {
        val diagnostics = diagnostics()
        diagnostics.record(WiredPathEvent.ATTEMPT_STARTED)
        val final = diagnostics.seal(WiredPathReportReason.FINAL)

        assertNotNull(final)
        assertEquals(1L, final?.count(WiredPathEvent.ATTEMPT_STARTED))
        assertEquals(1L, final?.count(WiredPathEvent.FINAL))
        assertFalse(diagnostics.record(WiredPathEvent.LISTENER_ACCEPTED))
        assertSame(final, diagnostics.seal(WiredPathReportReason.FAIL))
        assertEquals(0L, final?.count(WiredPathEvent.LISTENER_ACCEPTED))
    }

    @Test
    fun concurrentCounterUpdatesAreNotLostAndStayAttemptScoped() {
        val first = diagnostics(attempt = 4)
        val second = diagnostics(attempt = 5)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 10).map {
                executor.submit {
                    repeat(1_000) { first.record(WiredPathEvent.NCM_READ_QUEUED) }
                }
            }
            tasks.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        first.record(WiredPathEvent.STALE_CALLBACK)
        val snapshot = first.seal()!!

        assertEquals(10_000, snapshot.count(WiredPathEvent.NCM_READ_QUEUED))
        assertEquals(1, snapshot.count(WiredPathEvent.STALE_CALLBACK))
        assertEquals(0, second.count(WiredPathEvent.NCM_READ_QUEUED))
        assertNull(second.snapshot(WiredPathReportReason.FINAL)?.firstElapsedMillis(WiredPathEvent.NCM_READ_QUEUED))
    }

    @Test
    fun oldAttemptAndAttachmentEventsCannotIncrementCurrentDiagnostics() {
        val old = diagnostics(attempt = 4)
        val current = diagnostics(attempt = 5)
        old.setVpnAttachGeneration(11)
        current.setVpnAttachGeneration(12)

        old.record(WiredPathEvent.LISTENER_ACCEPTED)
        old.record(WiredPathEvent.STALE_CALLBACK)

        assertEquals(1L, old.count(WiredPathEvent.LISTENER_ACCEPTED))
        assertEquals(12, current.snapshot(WiredPathReportReason.ACCEPT)?.attachGeneration)
        assertEquals(0L, current.count(WiredPathEvent.LISTENER_ACCEPTED))
        assertEquals(0L, current.count(WiredPathEvent.STALE_CALLBACK))
    }

    @Test
    fun fakeTunWriteOutcomesAreRecordedOnlyForIpv6AirPlaySyn() {
        val enabledDiagnostics = diagnostics()
        val frame = Step4PacketFixtures.ethernetIpv6Tcp()
        val tun = FakeTunDevice()

        val rejectedWrite = runCatching {
            writeNcmFrameToTun(frame, tunOutput(tun), enabledDiagnostics)
        }
        assertTrue(rejectedWrite.exceptionOrNull() is IOException)
        tun.enqueueWriteOutcome(true)
        writeNcmFrameToTun(frame, tunOutput(tun), enabledDiagnostics)
        tun.enqueueWriteException(IllegalStateException("scripted TUN failure"))
        val exceptionalWrite = runCatching {
            writeNcmFrameToTun(frame, tunOutput(tun), enabledDiagnostics)
        }
        assertTrue(exceptionalWrite.exceptionOrNull() is IllegalStateException)
        val disabled = diagnostics(enabled = false)
        val disabledTun = FakeTunDevice().apply { enqueueWriteOutcome(true) }
        val enabledTun = FakeTunDevice().apply { enqueueWriteOutcome(true) }
        writeNcmFrameToTun(frame, tunOutput(disabledTun), disabled)
        writeNcmFrameToTun(frame, tunOutput(enabledTun), enabledDiagnostics)

        val wrongPort = Step4PacketFixtures.ethernetIpv6Tcp(destinationPort = 7_001)
        val wrongPortTun = FakeTunDevice().apply { enqueueWriteOutcome(true) }
        writeNcmFrameToTun(wrongPort, tunOutput(wrongPortTun), enabledDiagnostics)
        assertArrayEquals(disabledTun.writtenPackets.single(), enabledTun.writtenPackets.single())
        assertEquals(0L, disabled.count(WiredPathEvent.IPV6_SYN_TUN_WRITE_SUCCESS))
        assertEquals(2L, enabledDiagnostics.count(WiredPathEvent.IPV6_SYN_TUN_WRITE_SUCCESS))
        assertEquals(2L, enabledDiagnostics.count(WiredPathEvent.IPV6_SYN_TUN_WRITE_FAILURE))
    }

    @Test
    fun sealWaitsForInFlightMilestoneCallbackAndReturnsImmutableSnapshot() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val diagnostics = WiredPathDiagnostics(
            enabled = true,
            controllerRunId = "test-run",
            attempt = 4,
            listenerPort = 7_000,
            startedAtNanos = 1_000_000L,
            onReportRequested = {
                callbackEntered.countDown()
                releaseCallback.await()
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val recording = executor.submit<Boolean> {
                diagnostics.record(WiredPathEvent.ATTEMPT_STARTED)
            }
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            val sealing = executor.submit<WiredPathSnapshot?> { diagnostics.seal() }
            assertFalse(sealing.isDone)
            releaseCallback.countDown()
            assertTrue(recording.get(1, TimeUnit.SECONDS))
            val final = sealing.get(1, TimeUnit.SECONDS)

            assertNotNull(final)
            assertEquals(1L, final?.count(WiredPathEvent.ATTEMPT_STARTED))
            assertEquals(1L, final?.count(WiredPathEvent.FINAL))
            assertFalse(diagnostics.record(WiredPathEvent.LISTENER_ACCEPTED))
            assertEquals(0L, final?.count(WiredPathEvent.LISTENER_ACCEPTED))
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun disabledRecorderReturnsWithoutEventsOrReport() {
        val diagnostics = diagnostics(enabled = false)
        val frame = Step4PacketFixtures.ethernetIpv4Tcp()

        assertFalse(diagnostics.record(WiredPathEvent.ATTEMPT_STARTED))
        assertEquals(WiredTcpPacketClassification.EMPTY, diagnostics.observeEthernetFrame(frame, frame.size))
        assertEquals(0, diagnostics.count(WiredPathEvent.ATTEMPT_STARTED))
        assertNull(diagnostics.snapshot(WiredPathReportReason.START))
    }

    @Test
    fun requestsReportsOnlyForFirstOccurrenceOfMilestoneEvents() {
        val reports = mutableListOf<WiredPathReportReason>()
        val diagnostics = WiredPathDiagnostics(
            enabled = true,
            controllerRunId = "test-run",
            attempt = 4,
            listenerPort = 7_000,
            startedAtNanos = 1_000_000L,
            onReportRequested = reports::add,
        )

        diagnostics.record(WiredPathEvent.ATTEMPT_STARTED)
        diagnostics.record(WiredPathEvent.ATTEMPT_STARTED)
        diagnostics.record(WiredPathEvent.NCM_READ_QUEUED)
        diagnostics.record(WiredPathEvent.LISTENER_ACCEPTED)
        diagnostics.record(WiredPathEvent.LISTENER_ACCEPTED)

        assertEquals(
            listOf(WiredPathReportReason.START, WiredPathReportReason.ACCEPT),
            reports,
        )
    }

    private fun diagnostics(
        enabled: Boolean = true,
        attempt: Int = 4,
        startNanos: Long = 1_000_000L,
    ) = WiredPathDiagnostics(
        enabled = enabled,
        controllerRunId = "test-run",
        attempt = attempt,
        listenerPort = 7_000,
        startedAtNanos = startNanos,
    )

    private fun tunOutput(tun: FakeTunDevice): OutputStream = object : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (!tun.write(buffer.copyOfRange(offset, offset + length))) {
                throw IOException("scripted TUN write rejection")
            }
        }
    }
}
