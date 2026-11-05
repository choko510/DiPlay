package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

internal enum class WiredPathEvent(val key: String) {
    START("start"),
    NCM_USB_QUEUE("ncm_usb_queue"),
    NCM_USB_COMPLETION("ncm_usb_completion"),
    NCM_ETHERNET_COMPLETE("ncm_ethernet_complete"),
    NCM_ETHERNET_DELIVERED("ncm_ethernet_delivered"),
    V4_SYN_TO_AIRPLAY("v4_syn_to_airplay"),
    V6_SYN_TO_AIRPLAY("v6_syn_to_airplay"),
    V6_SYN_DEST_MATCH("v6_syn_dest_match"),
    V6_SYN_DEST_MISMATCH("v6_syn_dest_mismatch"),
    V6_SYN_DEST_UNVERIFIED("v6_syn_dest_unverified"),
    SYN_TUN_WRITE_SUCCESS("syn_tun_write_success"),
    SYN_TUN_WRITE_FAILURE("syn_tun_write_failure"),
    TCP_SYN_ACK_FROM_TUN("tcp_syn_ack_from_tun"),
    TCP_RST_FROM_TUN("tcp_rst_from_tun"),
    PEER_MAC_DROP("peer_mac_drop"),
    SYN_ACK_NCM_SENT("syn_ack_ncm_sent"),
    SYN_ACK_NCM_NOT_READY("syn_ack_ncm_not_ready"),
    SYN_ACK_NCM_FAILED("syn_ack_ncm_failed"),
    FIRST_BULK_OUT_FULL_SENT("first_bulk_out_full_sent"),
    VPN_ESTABLISHED("vpn_established"),
    LISTENER_BOUND("listener_bound"),
    LISTENER_ACCEPTED("listener_accepted"),
    RECORD_ACTIVE("record_active"),
    MAIN_SETUP("main_setup"),
    ALT_SETUP("alt_setup"),
    MAIN_FIRST_FRAME("main_first_frame"),
    INVALID_PACKET("invalid_packet"),
    UNSUPPORTED_PACKET("unsupported_packet"),
    UNKNOWN_PACKET("unknown_packet"),
    STALE_CALLBACK("stale_callback"),
}

internal enum class WiredPathReportReason(val key: String) {
    START("START"),
    SOFT_8S("SOFT_8S"),
    HARD_15S("HARD_15S"),
    ACCEPT("ACCEPT"),
    RECORD("RECORD"),
    MAIN("MAIN"),
    FINAL("FINAL"),
}

internal data class WiredPathCounterSnapshot(
    val count: Long,
    val firstElapsedMs: Long?,
)

internal data class WiredPathSnapshot(
    val runId: String,
    val wiredStartupAttempt: Int,
    val attachGeneration: Long?,
    val reason: WiredPathReportReason,
    val sealed: Boolean,
    val counterOverflow: Boolean,
    val counters: List<WiredPathCounterSnapshot>,
) {
    fun counter(event: WiredPathEvent): WiredPathCounterSnapshot = counters[event.ordinal]
}

/** Per-wired-startup-attempt, bounded counters only; packet contents never leave the caller. */
class WiredPathDiagnostics internal constructor(
    runId: String,
    internal val wiredStartupAttempt: Int,
    private val enabled: Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val safeRunId = runId.takeIf { RUN_ID.matches(it) } ?: "run-unknown"
    private val startedAtNanos = clockNanos()
    private val counts = AtomicLongArray(WiredPathEvent.entries.size)
    private val firstAtNanos = AtomicLongArray(WiredPathEvent.entries.size).apply {
        for (index in 0 until length()) set(index, NO_TIMESTAMP)
    }
    private val onceFlags = AtomicLongArray(WiredPathEvent.entries.size)
    private val attachGeneration = AtomicLong(UNKNOWN_GENERATION)
    private val counterOverflow = AtomicBoolean(false)
    private val writerState = AtomicLong(0L)
    private val sealedSnapshot = AtomicReference<WiredPathSnapshot?>(null)

    internal val isEnabled: Boolean get() = enabled

    internal fun recordAttachGeneration(generation: Long) {
        if (!enabled || generation < 0L || !enterWriter()) return
        try {
            attachGeneration.set(generation)
        } finally {
            leaveWriter()
        }
    }

    internal fun record(event: WiredPathEvent, callbackAttachGeneration: Long? = null) {
        recordInternal(event, callbackAttachGeneration, once = false)
    }

    internal fun recordOnce(event: WiredPathEvent, callbackAttachGeneration: Long? = null) {
        recordInternal(event, callbackAttachGeneration, once = true)
    }

    private fun recordInternal(
        event: WiredPathEvent,
        callbackAttachGeneration: Long?,
        once: Boolean,
    ) {
        if (!enabled || !enterWriter()) return
        try {
            if (callbackAttachGeneration != null) {
                val expected = attachGeneration.get()
                if (expected != UNKNOWN_GENERATION && callbackAttachGeneration != expected) {
                    increment(WiredPathEvent.STALE_CALLBACK)
                    return
                }
            }
            if (once && !onceFlags.compareAndSet(event.ordinal, 0L, 1L)) return
            increment(event)
        } finally {
            leaveWriter()
        }
    }

    internal fun snapshot(reason: WiredPathReportReason): WiredPathSnapshot {
        sealedSnapshot.get()?.let { return it }
        return capture(reason, sealed = false)
    }

    internal fun seal(): WiredPathSnapshot {
        sealedSnapshot.get()?.let { return it }
        while (true) {
            val state = writerState.get()
            if (state and SEALED_BIT != 0L) break
            if (writerState.compareAndSet(state, state or SEALED_BIT)) break
        }
        while (writerState.get() and WRITER_MASK != 0L) Thread.yield()
        sealedSnapshot.get()?.let { return it }
        val snapshot = capture(WiredPathReportReason.FINAL, sealed = true)
        sealedSnapshot.compareAndSet(null, snapshot)
        return sealedSnapshot.get() ?: snapshot
    }

    private fun capture(reason: WiredPathReportReason, sealed: Boolean): WiredPathSnapshot {
        val counters = ArrayList<WiredPathCounterSnapshot>(WiredPathEvent.entries.size).apply {
            for (index in 0 until WiredPathEvent.entries.size) {
            val first = firstAtNanos.get(index)
                add(
                    WiredPathCounterSnapshot(
                        count = counts.get(index),
                        firstElapsedMs = if (first == NO_TIMESTAMP) null else {
                            ((first - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
                        },
                    ),
                )
            }
        }
        return WiredPathSnapshot(
            runId = safeRunId,
            wiredStartupAttempt = wiredStartupAttempt,
            attachGeneration = attachGeneration.get().takeUnless { it == UNKNOWN_GENERATION },
            reason = reason,
            sealed = sealed,
            counterOverflow = counterOverflow.get(),
            counters = Collections.unmodifiableList(counters),
        )
    }

    private fun increment(event: WiredPathEvent) {
        val index = event.ordinal
        while (true) {
            val old = counts.get(index)
            if (old == Long.MAX_VALUE) {
                counterOverflow.set(true)
                break
            }
            if (counts.compareAndSet(index, old, old + 1L)) break
        }
        firstAtNanos.compareAndSet(index, NO_TIMESTAMP, clockNanos())
    }

    private fun enterWriter(): Boolean {
        while (true) {
            val state = writerState.get()
            if (state and SEALED_BIT != 0L) return false
            if (state and WRITER_MASK == WRITER_MASK) {
                counterOverflow.set(true)
                return false
            }
            if (writerState.compareAndSet(state, state + WRITER_ONE)) return true
        }
    }

    private fun leaveWriter() {
        writerState.addAndGet(-WRITER_ONE)
    }

    private companion object {
        const val UNKNOWN_GENERATION = -1L
        const val NO_TIMESTAMP = Long.MIN_VALUE
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SEALED_BIT = 1L
        const val WRITER_ONE = 2L
        const val WRITER_MASK = Long.MAX_VALUE - 1L
        val RUN_ID = Regex("[A-Za-z0-9_-]{1,48}")
    }
}
