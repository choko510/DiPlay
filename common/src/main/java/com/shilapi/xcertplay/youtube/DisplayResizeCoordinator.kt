package com.shilapi.xcertplay.youtube

internal data class DisplaySize(val width: Int, val height: Int)

internal class DisplayResizeCoordinator {
    private var latestObservedSize: DisplaySize? = null
    private var restartInFlight = false

    fun observe(size: DisplaySize) {
        latestObservedSize = size
    }

    fun observe(size: DisplaySize, imeVisible: Boolean) {
        if (!imeVisible) observe(size)
    }

    fun beginRestart(): Boolean {
        if (restartInFlight) return false
        restartInFlight = true
        return true
    }

    fun latestDesiredSize(fallback: DisplaySize): DisplaySize = latestObservedSize ?: fallback

    fun completeRestart(fallback: DisplaySize): DisplaySize {
        restartInFlight = false
        return latestDesiredSize(fallback)
    }
}
