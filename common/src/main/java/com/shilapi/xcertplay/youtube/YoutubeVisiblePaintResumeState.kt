package com.shilapi.xcertplay.youtube

internal class YoutubeVisiblePaintResumeState {
    private var suspensionGeneration = 0L
    private var resetGeneration: Long? = null
    private var requiredResetGeneration: Long? = null
    private var suspended = false

    val paintStatusResetObserved: Boolean
        get() = resetGeneration == suspensionGeneration

    fun startNewSession() {
        suspensionGeneration = 0L
        resetGeneration = null
        requiredResetGeneration = null
        suspended = false
    }

    fun markSuspended() {
        if (suspended) return
        suspensionGeneration += 1
        requiredResetGeneration = suspensionGeneration
        suspended = true
    }

    fun beginWarmReopen() {
        requiredResetGeneration = suspensionGeneration
    }

    fun markActive() {
        suspended = false
    }

    fun observePaintStatusReset() {
        resetGeneration = suspensionGeneration
    }

    fun allowsPaint(isPrimarySession: Boolean): Boolean {
        if (!isPrimarySession) return true
        val requiredGeneration = requiredResetGeneration ?: return true
        return resetGeneration == requiredGeneration
    }

    fun finishMeasurement() {
        requiredResetGeneration = null
    }
}
