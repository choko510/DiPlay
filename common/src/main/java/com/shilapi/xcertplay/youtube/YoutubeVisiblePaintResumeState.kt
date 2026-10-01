package com.shilapi.xcertplay.youtube

internal class YoutubeVisiblePaintResumeState {
    private var primaryWasSuspended = false

    var paintStatusResetObserved = false
        private set

    fun startNewSession() {
        primaryWasSuspended = false
        paintStatusResetObserved = false
    }

    fun markSuspended() {
        if (!primaryWasSuspended) paintStatusResetObserved = false
        primaryWasSuspended = true
    }

    fun beginWarmReopen() {
        primaryWasSuspended = true
    }

    fun observePaintStatusReset() {
        paintStatusResetObserved = true
    }

    fun allowsPaint(isPrimarySession: Boolean): Boolean =
        !isPrimarySession || !primaryWasSuspended || paintStatusResetObserved

    fun finishMeasurement() {
        primaryWasSuspended = false
        paintStatusResetObserved = false
    }
}
