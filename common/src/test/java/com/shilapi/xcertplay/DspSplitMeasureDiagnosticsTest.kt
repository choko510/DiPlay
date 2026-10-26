package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSplitMeasureDiagnosticsTest {
    @Test
    fun repeatedConstraintSignatureIsNotRecordedAgain() {
        val minHeight = Int.MIN_VALUE
        val maxHeight = Int.MAX_VALUE
        val capHeight = Int.MAX_VALUE - 1

        assertTrue(DspSplitMeasureDiagnostics.recordUnboundedBody(minHeight, maxHeight, capHeight))
        assertFalse(DspSplitMeasureDiagnostics.recordUnboundedBody(minHeight, maxHeight, capHeight))
        assertTrue(
            DspSplitMeasureDiagnostics.recentMeasurements()
                .contains("minHeight=$minHeight maxHeight=$maxHeight capHeight=$capHeight"),
        )
    }

    @Test
    fun repeatedComposeViewMeasurementIsNotRecordedAgain() {
        val measurement = "widthMode=EXACTLY widthSize=1700 heightMode=AT_MOST heightSize=800 measured=1700x800"

        assertTrue(DspSplitMeasureDiagnostics.record(measurement))
        assertFalse(DspSplitMeasureDiagnostics.record(measurement))
        assertTrue(DspSplitMeasureDiagnostics.recentMeasurements().contains(measurement))
    }
}
