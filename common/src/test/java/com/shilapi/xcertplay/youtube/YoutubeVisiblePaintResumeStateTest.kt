package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeVisiblePaintResumeStateTest {
    @Test
    fun profileTimeoutWhileSuspendedPreservesResetEvidence() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()

        state.finishMeasurement()
        state.beginWarmReopen()

        assertTrue(state.paintStatusResetObserved)
        assertTrue(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun lateProfileResolutionAfterTimeoutCanUseSuspensionReset() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()
        state.finishMeasurement()
        val measurement = YoutubeVisiblePaintMeasurement()

        state.beginWarmReopen()
        measurement.begin()

        val completed = state.allowsPaint(isPrimarySession = true) && measurement.complete()

        assertTrue(completed)
    }

    @Test
    fun resumeWithoutMeasurementConsumesOldSuspensionEvidence() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()
        state.finishMeasurement()
        state.markActive()

        state.markSuspended()

        assertFalse(state.paintStatusResetObserved)
        assertFalse(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun secondSuspensionRequiresANewReset() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()
        state.beginWarmReopen()
        state.markActive()

        state.markSuspended()

        assertFalse(state.paintStatusResetObserved)
        assertFalse(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun oldResetCannotAuthorizePaintForNewSuspension() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()
        state.beginWarmReopen()
        state.markActive()
        state.markSuspended()

        assertFalse(state.allowsPaint(isPrimarySession = true))

        state.observePaintStatusReset()

        assertTrue(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun currentSuspensionResetAuthorizesWarmPaint() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.beginWarmReopen()

        assertFalse(state.allowsPaint(isPrimarySession = true))
        state.observePaintStatusReset()

        assertTrue(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun coldSessionDoesNotInheritPreviousReset() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.observePaintStatusReset()

        state.startNewSession()

        assertFalse(state.paintStatusResetObserved)
        assertTrue(state.allowsPaint(isPrimarySession = true))
    }
}
