package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspFixedHistogramTest {
    @Test
    fun summarizesFixedBucketsAndResetsWithoutSorting() {
        val histogram = DspFixedHistogram()

        histogram.record(10_000)
        histogram.record(40_000)
        histogram.record(100_000)
        histogram.record(1_500_000)

        val summary = histogram.snapshot()
        assertEquals(4L, summary.sampleCount)
        assertEquals(412.5, summary.averageUs, 0.001)
        assertEquals(60L, summary.p50Us)
        assertEquals(1_520L, summary.p95Us)
        assertEquals(1_520L, summary.p99Us)
        assertEquals(1_500L, summary.maxUs)

        histogram.reset()

        assertEquals(DspTimingSummary(), histogram.snapshot())
        histogram.record(20_500_000)
        val overflow = histogram.snapshot()
        assertEquals(20_500L, overflow.p50Us)
        assertEquals(20_500L, overflow.maxUs)
        assertTrue(overflow.sampleCount == 1L)
    }
}
