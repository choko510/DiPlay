package com.shilapi.xcertplay.youtube

internal enum class YoutubeVisiblePaintOutcome {
    IDLE,
    WAITING,
    COMPLETED,
    TIMED_OUT,
    CANCELED,
}

internal class YoutubeVisiblePaintMeasurement {
    var outcome = YoutubeVisiblePaintOutcome.IDLE
        private set

    val isWaiting: Boolean
        get() = outcome == YoutubeVisiblePaintOutcome.WAITING

    fun begin() {
        outcome = YoutubeVisiblePaintOutcome.WAITING
    }

    fun complete(): Boolean = finish(YoutubeVisiblePaintOutcome.COMPLETED)

    fun timeout(): Boolean = finish(YoutubeVisiblePaintOutcome.TIMED_OUT)

    fun cancel() {
        if (isWaiting) outcome = YoutubeVisiblePaintOutcome.CANCELED
    }

    private fun finish(result: YoutubeVisiblePaintOutcome): Boolean {
        if (!isWaiting) return false
        outcome = result
        return true
    }
}
