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
        measurement.remainingTimeoutMillis(nowElapsedRealtimeMillis = 1_000L, timeoutMillis = 10_000L)

        assertFalse(measurement.timeout(nowElapsedRealtimeMillis = 10_999L))
        assertTrue(measurement.timeout(nowElapsedRealtimeMillis = 11_000L))
        assertFalse(measurement.complete())
        assertFalse(measurement.timeout(nowElapsedRealtimeMillis = 12_000L))
        assertEquals(YoutubeVisiblePaintOutcome.TIMED_OUT, measurement.outcome)
    }

    @Test
    fun reschedulingUsesTheOriginalActivationDeadline() {
        val measurement = YoutubeVisiblePaintMeasurement()
        measurement.begin()

        assertEquals(
            10_000L,
            measurement.remainingTimeoutMillis(nowElapsedRealtimeMillis = 1_000L, timeoutMillis = 10_000L),
        )
        assertEquals(
            7_000L,
            measurement.remainingTimeoutMillis(nowElapsedRealtimeMillis = 4_000L, timeoutMillis = 10_000L),
        )
        assertEquals(
            0L,
            measurement.remainingTimeoutMillis(nowElapsedRealtimeMillis = 12_000L, timeoutMillis = 10_000L),
        )
    }

    @Test
    fun completionIsTerminalAndRejectsTimeout() {
        val measurement = YoutubeVisiblePaintMeasurement()
        measurement.begin()

        assertTrue(measurement.complete())
        assertFalse(measurement.timeout(nowElapsedRealtimeMillis = 10_000L))
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
