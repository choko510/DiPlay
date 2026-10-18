package com.shilapi.xcertplay.media

internal class MicrophoneReadRecovery {
    private var consecutiveZeroReads = 0

    fun shouldRetryAfterZeroRead(isActive: Boolean): Boolean {
        if (!isActive) return false
        consecutiveZeroReads++
        return consecutiveZeroReads <= MAX_CONSECUTIVE_ZERO_READS
    }

    fun recordDataRead() {
        consecutiveZeroReads = 0
    }

    companion object {
        const val MAX_CONSECUTIVE_ZERO_READS = 10
        const val ZERO_READ_BACKOFF_NANOS = 10_000_000L
    }
}
