package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSplitMeasureDiagnosticsTest {
    @Test
    fun repeatedConstraintSignatureIsNotRecordedAgain() {
        val diagnostics = DspSplitMeasureDiagnostics()
        val minHeight = Int.MIN_VALUE
        val maxHeight = Int.MAX_VALUE
        val capHeight = Int.MAX_VALUE - 1

        assertTrue(diagnostics.recordUnboundedBody(minHeight, maxHeight, capHeight))
        assertFalse(diagnostics.recordUnboundedBody(minHeight, maxHeight, capHeight))
        assertTrue(
            diagnostics.recentMeasurements()
                .contains("minHeight=$minHeight maxHeight=$maxHeight capHeight=$capHeight"),
        )
    }

    @Test
    fun repeatedComposeViewMeasurementIsNotRecordedAgain() {
        val diagnostics = DspSplitMeasureDiagnostics()
        val widthMode = 0x40000000
        val widthSize = 1700
        val heightMode = 0x80000000.toInt()
        val heightSize = 800
        val measuredWidth = 1700
        val measuredHeight = 800
        val measurement =
            "widthMode=EXACTLY widthSize=1700 heightMode=AT_MOST heightSize=800 measured=1700x800"

        assertTrue(
            diagnostics.recordViewMeasurement(
                widthMode,
                widthSize,
                heightMode,
                heightSize,
                measuredWidth,
                measuredHeight,
            ),
        )
        assertFalse(
            diagnostics.recordViewMeasurement(
                widthMode,
                widthSize,
                heightMode,
                heightSize,
                measuredWidth,
                measuredHeight,
            ),
        )
        assertTrue(diagnostics.recentMeasurements().contains(measurement))
    }

    @Test
    fun diagnosticLogsStopAfterTheLifetimeLimit() {
        val diagnostics = DspSplitMeasureDiagnostics()
        val acceptedLogs = (0 until 100).count { index ->
            diagnostics.recordUnboundedBody(
                minHeight = 100_000 + index,
                maxHeight = Int.MAX_VALUE,
                capHeight = Int.MAX_VALUE - 1,
            )
        }

        assertEquals(64, acceptedLogs)
        assertFalse(
            diagnostics.recordUnboundedBody(
                minHeight = 200_000,
                maxHeight = Int.MAX_VALUE,
                capHeight = Int.MAX_VALUE - 1,
            ),
        )
    }
}
