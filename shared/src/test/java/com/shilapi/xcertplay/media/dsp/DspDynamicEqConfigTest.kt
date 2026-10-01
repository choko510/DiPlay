package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspDynamicEqConfigTest {
    @Test
    fun configurationIsDisabledByDefaultAndUsesAStableFiveBandNativeLayout() {
        val config = DspDynamicEqConfig()

        val values = config.toNativeValues()

        assertFalse(config.enabled)
        assertEquals(DspDynamicEqConfig.MAX_BANDS, config.bands.size)
        assertEquals(DspDynamicEqConfig.NATIVE_VALUE_COUNT, values.size)
        assertEquals(0.0, values[0], 0.0)
        assertEquals(0.0, values[1], 0.0)
        assertEquals(DspDynamicEqMode.CUT.name, config.bands[0].mode.name)
        assertTrue(values.all(Double::isFinite))
    }

    @Test
    fun boostAndCutModesMapToTheNativeModesAndRemainBounded() {
        val bands = DspDynamicEqConfig.defaultBands().toMutableList()
        bands[0] = DspDynamicEqBandConfig(enabled = true, mode = DspDynamicEqMode.BOOST, maxBoostDb = 3.0)
        bands[1] = DspDynamicEqBandConfig(enabled = true, mode = DspDynamicEqMode.CUT, maxCutDb = 8.0)
        val config = DspDynamicEqConfig(enabled = true, bands = bands)

        val values = config.toNativeValues()

        assertEquals(1.0, values[0], 0.0)
        assertEquals(1.0, values[1], 0.0)
        assertEquals(1.0, values[11], 0.0)
        assertEquals(8.0, values[20], 0.0)
    }

    @Test
    fun bandsBeyondFiveAndInvalidRangesAreRejected() {
        assertFalse(
            runCatching {
                DspDynamicEqConfig(bands = DspDynamicEqConfig.defaultBands() + DspDynamicEqBandConfig())
            }.isSuccess,
        )
        assertFalse(runCatching { DspDynamicEqBandConfig(frequencyHz = Double.NaN) }.isSuccess)
        assertFalse(runCatching { DspDynamicEqBandConfig(maxBoostDb = 12.1) }.isSuccess)
        assertFalse(
            runCatching {
                DspRuntimeConfig(
                    enabled = true,
                    dynamicEq = DspDynamicEqConfig(
                        enabled = true,
                        bands = listOf(DspDynamicEqBandConfig(enabled = true, frequencyHz = 4_000.0)),
                    ),
                ).prepare(DspAudioFormat(8_000, 2))
            }.isSuccess,
        )
    }
}
