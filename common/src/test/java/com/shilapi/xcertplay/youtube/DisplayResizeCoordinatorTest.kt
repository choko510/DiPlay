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
}
