package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicInteger

enum class DspLiveUpdateState {
    IDLE,
    PREPARING,
    PREPARED,
    APPLIED,
    DEFERRED,
    FAILED,
    DISABLED,
}

enum class DspLiveUpdateReason {
    NONE,
    LATENCY_CHANGE,
    STATEFUL_HISTORY,
    PREPARE_FAILED,
    PROCESS_FAILED,
}

data class DspLiveUpdateStatus(
    val state: DspLiveUpdateState,
    val reason: DspLiveUpdateReason,
)

object DspLiveUpdateStatusStore {
    private val status = AtomicInteger(encode(DspLiveUpdateState.IDLE, DspLiveUpdateReason.NONE))

    fun update(nextState: DspLiveUpdateState, nextReason: DspLiveUpdateReason = DspLiveUpdateReason.NONE) {
        status.set(encode(nextState, nextReason))
    }

    fun snapshot(): DspLiveUpdateStatus {
        val encoded = status.get()
        return DspLiveUpdateStatus(
            state = DspLiveUpdateState.entries[encoded ushr BITS_PER_VALUE],
            reason = DspLiveUpdateReason.entries[encoded and VALUE_MASK],
        )
    }

    private fun encode(state: DspLiveUpdateState, reason: DspLiveUpdateReason): Int =
        (state.ordinal shl BITS_PER_VALUE) or reason.ordinal

    private const val BITS_PER_VALUE = 8
    private const val VALUE_MASK = (1 shl BITS_PER_VALUE) - 1
}
