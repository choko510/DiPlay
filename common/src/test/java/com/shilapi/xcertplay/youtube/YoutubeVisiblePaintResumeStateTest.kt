package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeVisiblePaintResumeStateTest {
    @Test
    fun resetDuringSuspensionIsPreservedForWarmActivation() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()

        state.beginWarmReopen()

        assertTrue(state.paintStatusResetObserved)
        assertTrue(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun warmPaintAfterSuspensionResetCompletesMeasurement() {
        val state = YoutubeVisiblePaintResumeState()
        val measurement = YoutubeVisiblePaintMeasurement()
        state.startNewSession()
        state.markSuspended()
        state.observePaintStatusReset()
        measurement.begin()
        state.beginWarmReopen()

        val completed = state.allowsPaint(isPrimarySession = true) && measurement.complete()

        assertTrue(completed)
    }

    @Test
    fun warmPaintWithoutResetIsRejected() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()
        state.markSuspended()
        state.beginWarmReopen()

        assertFalse(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun resetArrivingAfterWarmBeginThenPaintCompletes() {
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
        state.markSuspended()
        state.observePaintStatusReset()

        state.startNewSession()

        assertFalse(state.paintStatusResetObserved)
        assertTrue(state.allowsPaint(isPrimarySession = true))
    }

    @Test
    fun paintStatusResetInColdSessionRemainsAvailableForComposite() {
        val state = YoutubeVisiblePaintResumeState()
        state.startNewSession()

        state.observePaintStatusReset()

        assertTrue(state.paintStatusResetObserved)
        assertTrue(state.allowsPaint(isPrimarySession = true))
    }
}
