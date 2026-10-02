package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspMultibandConfigTest {
    @Test
    fun nativeValuesKeepThreeCrossoverAndBandCompressorConfigsInStableOrder() {
        val config = DspMultibandConfig(
            enabled = true,
            lowMidCrossoverHz = 160.0,
            midHighCrossoverHz = 2_000.0,
            low = DspCompressorConfig(enabled = true, thresholdDb = -30.0, ratio = 5.0, makeupDb = 2.0),
            mid = DspCompressorConfig(enabled = true, thresholdDb = -24.0, ratio = 3.0),
            high = DspCompressorConfig(enabled = true, thresholdDb = -18.0, ratio = 2.0),
        )

        val values = config.toNativeValues()

        assertEquals(DspMultibandConfig.NATIVE_VALUE_COUNT, values.size)
        assertEquals(1.0, values[0], 0.0)
        assertEquals(160.0, values[1], 0.0)
        assertEquals(2_000.0, values[2], 0.0)
        assertEquals(1.0, values[3], 0.0)
        assertEquals(-30.0, values[4], 0.0)
        assertEquals(2.0, values[9], 0.0)
        assertEquals(1.0, values[10], 0.0)
        assertEquals(1.0, values[17], 0.0)
    }

    @Test
    fun cutoffRangeIsSafeAtTheLowestSupportedSampleRate() {
        val config = DspMultibandConfig(
            enabled = true,
            lowMidCrossoverHz = DspMultibandConfig.MAX_LOW_MID_CROSSOVER_HZ,
            midHighCrossoverHz = DspMultibandConfig.MAX_MID_HIGH_CROSSOVER_HZ,
        )

        val prepared = DspRuntimeConfig(enabled = true, multiband = config).prepare(DspAudioFormat(8_000, 2))

        assertTrue(prepared.multiband.all(Double::isFinite))
        assertEquals(3_500.0, prepared.multiband[2], 0.0)
    }

    @Test
    fun invalidCrossoversAreRejected() {
        assertFalse(runCatching { DspMultibandConfig(lowMidCrossoverHz = 500.0, midHighCrossoverHz = 500.0) }.isSuccess)
        assertFalse(runCatching { DspMultibandConfig(midHighCrossoverHz = Double.NaN) }.isSuccess)
        assertFalse(runCatching { DspMultibandConfig(midHighCrossoverHz = 3_501.0) }.isSuccess)
    }
}
