package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

internal enum class WiredStartupTimingEvent {
    FIRST_START_SESSION,
    FIRST_FULL_BULK_OUT,
    FIRST_VALID_AIRPLAY_SYN,
    FIRST_SYN_ACK_TUN,
    FIRST_SYN_ACK_OUT,
    LISTENER_ACCEPT,
    RTSP_RECORD,
    MAIN_SETUP,
    MAIN_FIRST_FRAME,
    TEARDOWN,
}

internal enum class SynObservation {
    PRESENT,
    ABSENT_WITHIN_WINDOW,
    UNKNOWN,
}

internal data class WiredStartupTimingSnapshot(
    val attemptId: Long,
    val attachGeneration: Long?,
    val startSessionSentNs: Long?,
    val firstFullBulkOutNs: Long?,
    val firstValidAirPlaySynNs: Long?,
    val firstSynAckTunNs: Long?,
    val firstSynAckOutNs: Long?,
    val listenerAcceptedNs: Long?,
    val rtspRecordNs: Long?,
    val mainSetupNs: Long?,
    val mainFirstFrameNs: Long?,
    val teardownNs: Long?,
    val synObservation: SynObservation,
)

/** Fixed-size, attempt-local monotonic milestones; this is independent of opt-in packet diagnostics. */
class WiredStartupTiming internal constructor(
    internal val attemptId: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = clockNanos()
    private val timestamps = AtomicLongArray(WiredStartupTimingEvent.entries.size).apply {
        for (index in 0 until length()) set(index, NO_TIMESTAMP)
    }
    private val attachGeneration = AtomicLong(UNKNOWN_GENERATION)
    private val synParseUncertain = AtomicBoolean(false)
    private val sealed = AtomicBoolean(false)

    init {
        require(attemptId > 0L) { "attemptId must be positive" }
    }

    internal fun recordAttachGeneration(generation: Long): Boolean {
        if (generation < 0L || sealed.get()) return false
        val current = attachGeneration.get()
        return current == generation || current == UNKNOWN_GENERATION &&
            attachGeneration.compareAndSet(UNKNOWN_GENERATION, generation)
    }

    internal fun record(event: WiredStartupTimingEvent, callbackGeneration: Long? = null): Boolean {
        if (sealed.get() || !acceptsGeneration(callbackGeneration)) return false
        val elapsed = clockNanos() - startedAtNanos
        if (elapsed < 0L) return false
        val recorded = timestamps.compareAndSet(event.ordinal, NO_TIMESTAMP, elapsed)
        if (recorded && event == WiredStartupTimingEvent.TEARDOWN) {
            sealed.set(true)
        } else if (recorded && sealed.get()) {
            val teardownNs = timestamps.get(WiredStartupTimingEvent.TEARDOWN.ordinal)
            if (teardownNs != NO_TIMESTAMP && elapsed > teardownNs) {
                timestamps.compareAndSet(event.ordinal, elapsed, NO_TIMESTAMP)
                return false
            }
        }
        return recorded
    }

    internal fun recordFullBulkOut(
        transferred: Int,
        expectedFullSize: Int,
        callbackGeneration: Long? = null,
    ): Boolean {
        if (expectedFullSize <= 0 || transferred != expectedFullSize || has(WiredStartupTimingEvent.FIRST_FULL_BULK_OUT)) {
            return false
        }
        return record(WiredStartupTimingEvent.FIRST_FULL_BULK_OUT, callbackGeneration)
    }

    internal fun recordAirPlaySyn(
        classification: WiredPacketClassification,
        callbackGeneration: Long,
    ): Boolean {
        if (
            sealed.get() || has(WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN) ||
            !acceptsGeneration(callbackGeneration)
        ) {
            return false
        }
        if (classification.tcpSyn && classification.portMatches) {
            if (
                classification.family == WiredPacketClassification.FAMILY_IPV6 &&
                classification.addressChecked && classification.addressMatches
            ) {
                return record(WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN, callbackGeneration)
            }
            if (
                classification.family == WiredPacketClassification.FAMILY_IPV4 &&
                classification.addressChecked && classification.addressMatches
            ) {
                return record(WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN, callbackGeneration)
            }
            if (classification.family == WiredPacketClassification.FAMILY_IPV4 && !classification.addressChecked) {
                synParseUncertain.compareAndSet(false, true)
            }
        }
        if (
            classification.family == WiredPacketClassification.FAMILY_IPV6 &&
            (classification.disposition != WiredPacketClassification.DISPOSITION_VALID || classification.fragmented)
        ) {
            synParseUncertain.compareAndSet(false, true)
        }
        if (
            classification.vlanTagged &&
            classification.disposition != WiredPacketClassification.DISPOSITION_VALID
        ) {
            synParseUncertain.compareAndSet(false, true)
        }
        return false
    }

    internal fun recordSynAckFromTun(
        classification: WiredPacketClassification,
        callbackGeneration: Long,
    ): Boolean {
        if (!classification.tcpSynAck || !classification.portMatches) return false
        if (
            classification.family != WiredPacketClassification.FAMILY_IPV6 ||
            !classification.addressChecked || !classification.addressMatches
        ) {
            return false
        }
        return record(WiredStartupTimingEvent.FIRST_SYN_ACK_TUN, callbackGeneration)
    }

    internal fun recordSynAckOut(sent: Boolean, callbackGeneration: Long): Boolean =
        if (sent) record(WiredStartupTimingEvent.FIRST_SYN_ACK_OUT, callbackGeneration) else false

    internal fun has(event: WiredStartupTimingEvent): Boolean = timestamps.get(event.ordinal) != NO_TIMESTAMP

    internal fun snapshot(
        nowNanos: Long = clockNanos(),
        synObservationWindowMillis: Long = DEFAULT_SYN_OBSERVATION_WINDOW_MILLIS,
    ): WiredStartupTimingSnapshot {
        val eventTimes = Array<Long?>(WiredStartupTimingEvent.entries.size) { index ->
            timestamps.get(index).takeUnless { it == NO_TIMESTAMP }
        }
        val startSessionNs = eventTimes[WiredStartupTimingEvent.FIRST_START_SESSION.ordinal]
        val synNs = eventTimes[WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN.ordinal]
        val elapsedNow = (nowNanos - startedAtNanos).takeIf { it >= 0L }
        val windowNs = millisToNanos(synObservationWindowMillis)
        val synObservation = when {
            synNs != null -> SynObservation.PRESENT
            synParseUncertain.get() -> SynObservation.UNKNOWN
            startSessionNs == null || elapsedNow == null -> SynObservation.UNKNOWN
            elapsedNow - startSessionNs < windowNs -> SynObservation.UNKNOWN
            else -> SynObservation.ABSENT_WITHIN_WINDOW
        }
        return WiredStartupTimingSnapshot(
            attemptId = attemptId,
            attachGeneration = attachGeneration.get().takeUnless { it == UNKNOWN_GENERATION },
            startSessionSentNs = startSessionNs,
            firstFullBulkOutNs = eventTimes[WiredStartupTimingEvent.FIRST_FULL_BULK_OUT.ordinal],
            firstValidAirPlaySynNs = synNs,
            firstSynAckTunNs = eventTimes[WiredStartupTimingEvent.FIRST_SYN_ACK_TUN.ordinal],
            firstSynAckOutNs = eventTimes[WiredStartupTimingEvent.FIRST_SYN_ACK_OUT.ordinal],
            listenerAcceptedNs = eventTimes[WiredStartupTimingEvent.LISTENER_ACCEPT.ordinal],
            rtspRecordNs = eventTimes[WiredStartupTimingEvent.RTSP_RECORD.ordinal],
            mainSetupNs = eventTimes[WiredStartupTimingEvent.MAIN_SETUP.ordinal],
            mainFirstFrameNs = eventTimes[WiredStartupTimingEvent.MAIN_FIRST_FRAME.ordinal],
            teardownNs = eventTimes[WiredStartupTimingEvent.TEARDOWN.ordinal],
            synObservation = synObservation,
        )
    }

    private fun acceptsGeneration(callbackGeneration: Long?): Boolean {
        if (callbackGeneration == null) return true
        val current = attachGeneration.get()
        return current != UNKNOWN_GENERATION && current == callbackGeneration
    }

    private fun millisToNanos(millis: Long): Long {
        if (millis <= 0L) return 0L
        return if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            millis * NANOS_PER_MILLISECOND
        }
    }

    private companion object {
        const val UNKNOWN_GENERATION = -1L
        const val NO_TIMESTAMP = Long.MIN_VALUE
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_SYN_OBSERVATION_WINDOW_MILLIS = 15_000L
    }
}

internal fun hasQuietWindowElapsed(
    lastActivityNs: Long?,
    nowNs: Long,
    quietMillis: Long,
): Boolean {
    if (lastActivityNs == null || quietMillis < 0L) return false
    val elapsed = nowNs - lastActivityNs
    if (elapsed < 0L) return false
    val required = if (quietMillis > Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        quietMillis * 1_000_000L
    }
    return elapsed >= required
}

internal object WiredStartupTimingFormatter {
    fun format(snapshot: WiredStartupTimingSnapshot): String = buildString {
        append("WIRED_STAGE3_V1")
        append(" attempt=").append(snapshot.attemptId)
        append(" attach_generation=").append(snapshot.attachGeneration ?: "none")
        append(" start_session_ns=").append(snapshot.startSessionSentNs ?: "none")
        append(" first_full_bulk_out_ns=").append(snapshot.firstFullBulkOutNs ?: "none")
        append(" first_valid_airplay_syn_ns=").append(snapshot.firstValidAirPlaySynNs ?: "none")
        append(" syn_ack_tun_ns=").append(snapshot.firstSynAckTunNs ?: "none")
        append(" syn_ack_out_ns=").append(snapshot.firstSynAckOutNs ?: "none")
        append(" listener_accept_ns=").append(snapshot.listenerAcceptedNs ?: "none")
        append(" rtsp_record_ns=").append(snapshot.rtspRecordNs ?: "none")
        append(" main_setup_ns=").append(snapshot.mainSetupNs ?: "none")
        append(" main_first_frame_ns=").append(snapshot.mainFirstFrameNs ?: "none")
        append(" teardown_ns=").append(snapshot.teardownNs ?: "none")
        append(" syn_observation=").append(snapshot.synObservation.name)
    }
}
