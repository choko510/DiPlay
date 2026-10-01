package com.shilapi.xcertplay.youtube

internal enum class YoutubeVisiblePaintOutcome {
    IDLE,
    WAITING,
    COMPLETED,
    TIMED_OUT,
    CANCELED,
}

internal class YoutubeVisiblePaintMeasurement {
    private var deadlineElapsedRealtimeMillis: Long? = null

    var outcome = YoutubeVisiblePaintOutcome.IDLE
        private set

    val isWaiting: Boolean
        get() = outcome == YoutubeVisiblePaintOutcome.WAITING

    fun begin() {
        outcome = YoutubeVisiblePaintOutcome.WAITING
        deadlineElapsedRealtimeMillis = null
    }

    fun complete(): Boolean = finish(YoutubeVisiblePaintOutcome.COMPLETED)

    fun remainingTimeoutMillis(nowElapsedRealtimeMillis: Long, timeoutMillis: Long): Long {
        if (!isWaiting) return 0L
        val deadline = deadlineElapsedRealtimeMillis
            ?: (nowElapsedRealtimeMillis + timeoutMillis).also { deadlineElapsedRealtimeMillis = it }
        return (deadline - nowElapsedRealtimeMillis).coerceAtLeast(0L)
    }

    fun timeout(nowElapsedRealtimeMillis: Long): Boolean {
        val deadline = deadlineElapsedRealtimeMillis ?: return false
        if (nowElapsedRealtimeMillis < deadline) return false
        return finish(YoutubeVisiblePaintOutcome.TIMED_OUT)
    }

    fun cancel() {
        finish(YoutubeVisiblePaintOutcome.CANCELED)
    }

    private fun finish(result: YoutubeVisiblePaintOutcome): Boolean {
        if (!isWaiting) return false
        outcome = result
        deadlineElapsedRealtimeMillis = null
        return true
    }
}
