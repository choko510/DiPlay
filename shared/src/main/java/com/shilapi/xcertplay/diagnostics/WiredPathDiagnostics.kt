package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.network.WiredTcpPacketClassification
import com.shilapi.xcertplay.network.WiredTcpPacketClassifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

enum class WiredPathEvent(val logKey: String) {
    ATTEMPT_STARTED("attemptStarted"),
    START_SESSION_SENT("startSessionSent"),
    NCM_READ_QUEUED("ncmReadQueued"),
    NCM_READ_QUEUE_FAILURE("ncmReadQueueFailure"),
    NCM_READ_TIMEOUT("ncmReadTimeout"),
    NCM_READ_NULL_ERROR("ncmReadNullError"),
    NCM_READ_UNEXPECTED_COMPLETION("ncmReadUnexpectedCompletion"),
    NCM_USB_COMPLETION("ncmUsbCompletion"),
    NCM_WRITE_SENT("ncmWriteSent"),
    NCM_WRITE_NOT_READY("ncmWriteNotReady"),
    NCM_WRITE_FAILED("ncmWriteFailed"),
    NCM_WRITE_PARTIAL_FAILURE("ncmWritePartialFailure"),
    NCM_ETHERNET_COMPLETE("ncmEthernetComplete"),
    NCM_ETHERNET_DELIVERED("ncmEthernetDelivered"),
    IPV4_SYN_TO_AIRPLAY("v4SynToAirPlay"),
    IPV6_SYN_TO_AIRPLAY("v6SynToAirPlay"),
    IPV6_SYN_DEST_MATCH("v6SynDestMatch"),
    IPV6_SYN_DEST_MISMATCH("v6SynDestMismatch"),
    IPV6_SYN_TUN_WRITE_SUCCESS("v6SynTunWriteSuccess"),
    IPV6_SYN_TUN_WRITE_FAILURE("v6SynTunWriteFailure"),
    TUN_SYN_ACK_OBSERVED("tcpSynAckFromTun"),
    TUN_RST_OBSERVED("tcpRstFromTun"),
    PEER_MAC_MISSING_DROP("peerMacMissingDrop"),
    SYN_ACK_NCM_SENT("synAckNcmSent"),
    SYN_ACK_NCM_NOT_READY("synAckNcmNotReady"),
    SYN_ACK_NCM_FAILED("synAckNcmFailed"),
    FIRST_BULK_OUT_FULL_SENT("firstBulkOutFullSent"),
    LISTENER_BOUND("listenerBound"),
    LISTENER_ACCEPTED("listenerAccepted"),
    RTSP_RECORD_ACTIVE("recordActive"),
    MAIN_SETUP("mainSetup"),
    MAIN_SETUP_REJECTED("mainSetupRejected"),
    ALT_SETUP("altSetup"),
    ALT_SETUP_REJECTED("altSetupRejected"),
    MAIN_FRAME_SUBMITTED("mainFrameSubmitted"),
    INVALID_PACKET("invalidPacket"),
    STALE_CALLBACK("staleCallback"),
    SOFT_TIMEOUT("soft8s"),
    HARD_TIMEOUT("hard15s"),
    FAILURE("failure"),
    FINAL("final"),
}

enum class WiredPathReportReason {
    START,
    START_SESSION,
    BIND,
    SOFT_8S,
    HARD_15S,
    ACCEPT,
    RECORD,
    MAIN_SETUP,
    ALT_SETUP,
    MAIN_FRAME,
    FAIL,
    FINAL,
}

class WiredPathDiagnostics internal constructor(
    internal val enabled: Boolean,
    internal val controllerRunId: String,
    internal val attempt: Int,
    internal val listenerPort: Int,
    private val startedAtNanos: Long = System.nanoTime(),
    private val onReportRequested: ((WiredPathReportReason) -> Unit)? = null,
) {
    private val counts = AtomicLongArray(WiredPathEvent.entries.size)
    private val firstElapsedNanos = AtomicLongArray(WiredPathEvent.entries.size)
    private val attachGeneration = AtomicInteger(UNSET_GENERATION)
    private val expectedLocalIpv6 = AtomicReference<ByteArray?>(null)
    private val recorderState = AtomicInteger(OPEN)
    private val inFlight = AtomicInteger()
    private val sealRequest = AtomicReference<SealRequest?>(null)
    private val finalSnapshot = AtomicReference<WiredPathSnapshot?>(null)
    private val sealCompleted = CountDownLatch(1)

    init {
        for (index in 0 until firstElapsedNanos.length()) firstElapsedNanos.set(index, NO_EVENT)
    }

    internal fun record(event: WiredPathEvent): Boolean {
        if (!enter()) return false
        return try {
            recordInside(event, elapsedNanos(System.nanoTime()))
        } finally {
            leave()
        }
    }

    internal fun recordAt(event: WiredPathEvent, nowNanos: Long): Boolean {
        if (!enter()) return false
        return try {
            recordInside(event, elapsedNanos(nowNanos))
        } finally {
            leave()
        }
    }

    internal fun observeEthernetFrame(
        frame: ByteArray,
        length: Int,
    ): WiredTcpPacketClassification {
        if (!enter()) return WiredTcpPacketClassification.EMPTY
        return try {
            classifyEthernetAt(frame, length, System.nanoTime())
        } finally {
            leave()
        }
    }

    internal fun observeEthernetFrameAt(
        frame: ByteArray,
        length: Int,
        nowNanos: Long,
    ): WiredTcpPacketClassification {
        if (!enter()) return WiredTcpPacketClassification.EMPTY
        return try {
            classifyEthernetAt(frame, length, nowNanos)
        } finally {
            leave()
        }
    }

    private fun classifyEthernetAt(
        frame: ByteArray,
        length: Int,
        nowNanos: Long,
    ): WiredTcpPacketClassification {
        val classification = WiredTcpPacketClassifier.classifyEthernet(
            frame = frame,
            length = length,
            expectedIpv6 = expectedLocalIpv6.get(),
        )
        recordClassification(classification, isTunPacket = false, elapsedNanos = elapsedNanos(nowNanos))
        return classification
    }

    internal fun observeTunPacket(
        packet: ByteArray,
        length: Int,
    ): WiredTcpPacketClassification {
        if (!enter()) return WiredTcpPacketClassification.EMPTY
        return try {
            classifyTunAt(packet, length, System.nanoTime())
        } finally {
            leave()
        }
    }

    internal fun observeTunPacketAt(
        packet: ByteArray,
        length: Int,
        nowNanos: Long,
    ): WiredTcpPacketClassification {
        if (!enter()) return WiredTcpPacketClassification.EMPTY
        return try {
            classifyTunAt(packet, length, nowNanos)
        } finally {
            leave()
        }
    }

    private fun classifyTunAt(
        packet: ByteArray,
        length: Int,
        nowNanos: Long,
    ): WiredTcpPacketClassification {
        val classification = WiredTcpPacketClassifier.classifyTunPacket(
            packet = packet,
            length = length,
            expectedIpv6 = expectedLocalIpv6.get(),
        )
        recordClassification(classification, isTunPacket = true, elapsedNanos = elapsedNanos(nowNanos))
        return classification
    }

    internal fun setVpnAttachGeneration(generation: Int) {
        if (!enter()) return
        try {
            attachGeneration.compareAndSet(UNSET_GENERATION, generation)
        } finally {
            leave()
        }
    }

    internal fun setExpectedLocalIpv6Address(address: ByteArray) {
        if (!enabled || address.size != IPV6_ADDRESS_BYTES || !enter()) return
        try {
            expectedLocalIpv6.compareAndSet(null, address.copyOf())
        } finally {
            leave()
        }
    }

    internal fun recordTunWriteResult(
        classification: WiredTcpPacketClassification,
        succeeded: Boolean,
    ) {
        if (!classification.isAirPlaySyn(listenerPort) || classification.ipVersion != IPV6_VERSION) return
        record(
            if (succeeded) WiredPathEvent.IPV6_SYN_TUN_WRITE_SUCCESS
            else WiredPathEvent.IPV6_SYN_TUN_WRITE_FAILURE,
        )
    }

    internal fun snapshot(
        reason: WiredPathReportReason,
        nowNanos: Long = System.nanoTime(),
    ): WiredPathSnapshot? {
        if (!enabled) return null
        finalSnapshot.get()?.let { return it.withReason(reason) }
        return copySnapshot(reason, elapsedNanos(nowNanos))
    }

    internal fun seal(
        reason: WiredPathReportReason = WiredPathReportReason.FINAL,
        nowNanos: Long = System.nanoTime(),
    ): WiredPathSnapshot? {
        if (!enabled) return null
        val request = SealRequest(reason, elapsedNanos(nowNanos))
        if (sealRequest.compareAndSet(null, request)) {
            recordAt(WiredPathEvent.FINAL, nowNanos)
            recorderState.compareAndSet(OPEN, SEAL_REQUESTED)
        }
        completeSealIfIdle()
        var interrupted = false
        while (finalSnapshot.get() == null) {
            try {
                sealCompleted.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return finalSnapshot.get()
    }

    internal fun count(event: WiredPathEvent): Long =
        if (enabled) counts.get(event.ordinal) else 0L

    private fun recordClassification(
        classification: WiredTcpPacketClassification,
        isTunPacket: Boolean,
        elapsedNanos: Long,
    ) {
        if (classification.status != WiredTcpPacketClassification.STATUS_TCP &&
            classification.status != WiredTcpPacketClassification.STATUS_NON_TCP
        ) {
            recordInside(WiredPathEvent.INVALID_PACKET, elapsedNanos)
        }
        if (isTunPacket) {
            when {
                classification.isAirPlaySynAck(listenerPort) ->
                    recordInside(WiredPathEvent.TUN_SYN_ACK_OBSERVED, elapsedNanos)
                classification.isAirPlayRst(listenerPort) ->
                    recordInside(WiredPathEvent.TUN_RST_OBSERVED, elapsedNanos)
            }
            return
        }
        if (!classification.isAirPlaySyn(listenerPort)) return
        when (classification.ipVersion) {
            IPV4_VERSION -> recordInside(WiredPathEvent.IPV4_SYN_TO_AIRPLAY, elapsedNanos)
            IPV6_VERSION -> {
                recordInside(WiredPathEvent.IPV6_SYN_TO_AIRPLAY, elapsedNanos)
                when (classification.destinationMatch) {
                    WiredTcpPacketClassification.DESTINATION_MATCH ->
                        recordInside(WiredPathEvent.IPV6_SYN_DEST_MATCH, elapsedNanos)
                    WiredTcpPacketClassification.DESTINATION_MISMATCH ->
                        recordInside(WiredPathEvent.IPV6_SYN_DEST_MISMATCH, elapsedNanos)
                }
            }
        }
    }

    private fun recordInside(event: WiredPathEvent, elapsedNanos: Long): Boolean {
        val index = event.ordinal
        counts.incrementAndGet(index)
        val first = firstElapsedNanos.compareAndSet(index, NO_EVENT, elapsedNanos)
        if (first) {
            reportReason(event)?.let { reason ->
                runCatching { onReportRequested?.invoke(reason) }
            }
        }
        return first
    }

    private fun enter(): Boolean {
        if (!enabled || recorderState.get() != OPEN) return false
        inFlight.incrementAndGet()
        if (recorderState.get() == OPEN) return true
        leave()
        return false
    }

    private fun leave() {
        if (inFlight.decrementAndGet() == 0) completeSealIfIdle()
    }

    private fun completeSealIfIdle() {
        if (inFlight.get() != 0 || !recorderState.compareAndSet(SEAL_REQUESTED, SEALING)) return
        val request = sealRequest.get() ?: SealRequest(WiredPathReportReason.FINAL, elapsedNanos(System.nanoTime()))
        finalSnapshot.set(copySnapshot(request.reason, request.elapsedNanos))
        recorderState.set(SEALED)
        sealCompleted.countDown()
    }

    private fun copySnapshot(reason: WiredPathReportReason, elapsedNanos: Long): WiredPathSnapshot {
        val countCopy = LongArray(WiredPathEvent.entries.size) { counts.get(it) }
        val firstCopy = LongArray(WiredPathEvent.entries.size) { firstElapsedNanos.get(it) }
        return WiredPathSnapshot(
            controllerRunId = controllerRunId,
            attempt = attempt,
            attachGeneration = attachGeneration.get(),
            reason = reason,
            elapsedNanos = elapsedNanos,
            counts = countCopy,
            firstElapsedNanos = firstCopy,
        )
    }

    private fun elapsedNanos(nowNanos: Long): Long = (nowNanos - startedAtNanos).coerceAtLeast(0L)

    private fun reportReason(event: WiredPathEvent): WiredPathReportReason? = when (event) {
        WiredPathEvent.ATTEMPT_STARTED -> WiredPathReportReason.START
        WiredPathEvent.START_SESSION_SENT -> WiredPathReportReason.START_SESSION
        WiredPathEvent.LISTENER_BOUND -> WiredPathReportReason.BIND
        WiredPathEvent.LISTENER_ACCEPTED -> WiredPathReportReason.ACCEPT
        WiredPathEvent.RTSP_RECORD_ACTIVE -> WiredPathReportReason.RECORD
        WiredPathEvent.MAIN_SETUP, WiredPathEvent.MAIN_SETUP_REJECTED -> WiredPathReportReason.MAIN_SETUP
        WiredPathEvent.ALT_SETUP, WiredPathEvent.ALT_SETUP_REJECTED -> WiredPathReportReason.ALT_SETUP
        WiredPathEvent.MAIN_FRAME_SUBMITTED -> WiredPathReportReason.MAIN_FRAME
        WiredPathEvent.SOFT_TIMEOUT -> WiredPathReportReason.SOFT_8S
        WiredPathEvent.HARD_TIMEOUT -> WiredPathReportReason.HARD_15S
        WiredPathEvent.FAILURE -> WiredPathReportReason.FAIL
        else -> null
    }

    private data class SealRequest(val reason: WiredPathReportReason, val elapsedNanos: Long)

    private companion object {
        const val OPEN = 0
        const val SEAL_REQUESTED = 1
        const val SEALING = 2
        const val SEALED = 3
        const val UNSET_GENERATION = -1
        const val NO_EVENT = -1L
        const val IPV4_VERSION = 4
        const val IPV6_VERSION = 6
        const val IPV6_ADDRESS_BYTES = 16
    }
}

internal class WiredPathSnapshot internal constructor(
    val controllerRunId: String,
    val attempt: Int,
    val attachGeneration: Int,
    val reason: WiredPathReportReason,
    private val elapsedNanos: Long,
    private val counts: LongArray,
    private val firstElapsedNanos: LongArray,
) {
    fun count(event: WiredPathEvent): Long = counts[event.ordinal]

    fun firstElapsedMillis(event: WiredPathEvent): Long? =
        firstElapsedNanos[event.ordinal].takeIf { it >= 0 }?.div(NANOS_PER_MILLISECOND)

    fun firstElapsedSinceStartSessionMillis(event: WiredPathEvent): Long? {
        val eventTime = firstElapsedNanos[event.ordinal]
        val startSessionTime = firstElapsedNanos[WiredPathEvent.START_SESSION_SENT.ordinal]
        if (eventTime < 0 || startSessionTime < 0) return null
        return (eventTime - startSessionTime) / NANOS_PER_MILLISECOND
    }

    fun hasStartSessionTime(): Boolean =
        firstElapsedNanos[WiredPathEvent.START_SESSION_SENT.ordinal] >= 0

    internal fun withReason(reason: WiredPathReportReason): WiredPathSnapshot =
        if (this.reason == reason) this else WiredPathSnapshot(
            controllerRunId,
            attempt,
            attachGeneration,
            reason,
            elapsedNanos,
            counts,
            firstElapsedNanos,
        )

    fun elapsedMillis(): Long = elapsedNanos / NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
