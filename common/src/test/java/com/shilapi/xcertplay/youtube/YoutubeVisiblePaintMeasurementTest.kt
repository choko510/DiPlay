package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeVisiblePaintMeasurementTest {
    @Test
    fun timeoutIsTerminalAndRejectsLateCompletion() {
        val measurement = YoutubeVisiblePaintMeasurement()
        measurement.begin()

        assertTrue(measurement.timeout())
        assertFalse(measurement.complete())
        assertFalse(measurement.timeout())
        assertEquals(YoutubeVisiblePaintOutcome.TIMED_OUT, measurement.outcome)
    }

    @Test
    fun completionIsTerminalAndRejectsTimeout() {
        val measurement = YoutubeVisiblePaintMeasurement()
        measurement.begin()

        assertTrue(measurement.complete())
        assertFalse(measurement.timeout())
        assertEquals(YoutubeVisiblePaintOutcome.COMPLETED, measurement.outcome)
    }

    @Test
    fun cancellationPreventsLaterCompletion() {
        val measurement = YoutubeVisiblePaintMeasurement()
        measurement.begin()

        measurement.cancel()

        assertFalse(measurement.complete())
        assertEquals(YoutubeVisiblePaintOutcome.CANCELED, measurement.outcome)
    }
}
