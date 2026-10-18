package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayResizeCoordinatorTest {
    @Test
    fun settledResizeSequenceUsesOnlyTheLatestSize() {
        val coordinator = DisplayResizeCoordinator()
        val observed = listOf(
            DisplaySize(1920, 1080),
            DisplaySize(1710, 1080),
            DisplaySize(1480, 1080),
            DisplaySize(1056, 1080),
        )

        observed.forEach(coordinator::observe)

        assertEquals(observed.last(), coordinator.latestDesiredSize(observed.first()))
    }

    @Test
    fun resizeDuringRestartWinsAndRestartCannotStartTwice() {
        val coordinator = DisplayResizeCoordinator()
        val original = DisplaySize(1920, 1080)
        val latest = DisplaySize(1056, 720)
        coordinator.observe(original)

        assertTrue(coordinator.beginRestart())
        assertFalse(coordinator.beginRestart())
        coordinator.observe(latest)

        assertEquals(latest, coordinator.completeRestart(original))
    }

    @Test
    fun keyboardResizeDoesNotReplaceTheNegotiatedCarPlaySize() {
        val coordinator = DisplayResizeCoordinator()
        val negotiated = DisplaySize(1920, 1080)
        coordinator.observe(negotiated)

        coordinator.observe(DisplaySize(1920, 620), imeVisible = true)

        assertEquals(negotiated, coordinator.latestDesiredSize(negotiated))

        val settledAfterKeyboard = DisplaySize(1920, 1080)
        coordinator.observe(settledAfterKeyboard, imeVisible = false)
        assertEquals(settledAfterKeyboard, coordinator.latestDesiredSize(negotiated))
    }

    @Test
    fun resumedActivitySettlesSuppressedResizeOnlyWhenImeIsHidden() {
        assertTrue(
            ImeResizeResumePolicy.shouldScheduleSettleCheck(
                imeVisible = false,
                animationInProgress = false,
                resizeSuppressed = true,
            ),
        )
        assertTrue(
            ImeResizeResumePolicy.shouldScheduleSettleCheck(
                imeVisible = false,
                animationInProgress = true,
                resizeSuppressed = false,
            ),
        )
        assertFalse(
            ImeResizeResumePolicy.shouldScheduleSettleCheck(
                imeVisible = true,
                animationInProgress = true,
                resizeSuppressed = true,
            ),
        )
        assertFalse(
            ImeResizeResumePolicy.shouldScheduleSettleCheck(
                imeVisible = false,
                animationInProgress = false,
                resizeSuppressed = false,
            ),
        )
    }

    @Test
    fun localSplitResizeKeepsTheNegotiatedCanvasAcrossTwentyCycles() {
        val canvas = DisplaySize(1920, 1080)
        val splitViewport = DisplaySize(1056, 1080)
        val observedViewports = List(20) { listOf(splitViewport, canvas) }.flatten()
        var retainedCanvas = canvas
        var restartCount = 0

        observedViewports.forEach { viewport ->
            if (viewport == retainedCanvas) return@forEach
            val keepController = SplitDisplayResizePolicy.keepsCurrentController(
                previous = retainedCanvas,
                requested = viewport,
                controllerPresent = true,
                localScaleEnabled = true,
            )
            if (keepController) return@forEach
            restartCount += 1
            retainedCanvas = viewport
        }

        assertEquals(0, restartCount)
        assertEquals(canvas, retainedCanvas)
    }

    @Test
    fun resizeFallsBackWhenLocalScaleIsNotEligible() {
        val canvas = DisplaySize(1920, 1080)
        val splitViewport = DisplaySize(1056, 1080)

        assertFalse(
            SplitDisplayResizePolicy.keepsCurrentController(
                previous = canvas,
                requested = splitViewport,
                controllerPresent = true,
                localScaleEnabled = false,
            ),
        )
        assertFalse(
            SplitDisplayResizePolicy.keepsCurrentController(
                previous = canvas,
                requested = splitViewport,
                controllerPresent = false,
                localScaleEnabled = true,
            ),
        )
    }
}
