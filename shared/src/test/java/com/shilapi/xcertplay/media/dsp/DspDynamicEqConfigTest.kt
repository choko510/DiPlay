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
        val lowRatePrepared = DspRuntimeConfig(
            enabled = true,
            dynamicEq = DspDynamicEqConfig(
                enabled = true,
                bands = listOf(DspDynamicEqBandConfig(enabled = true, frequencyHz = 4_000.0)),
            ),
        ).prepare(DspAudioFormat(8_000, 2))
        assertEquals(0.0, lowRatePrepared.dynamicEq[1], 0.0)
    }

    @Test
    fun highLegacyFrequencyIsDisabledPerBandAt44100AndRemainsValidAt48000() {
        val bands = DspDynamicEqConfig.defaultBands().toMutableList()
        bands[0] = DspDynamicEqBandConfig(enabled = true, frequencyHz = 20_000.0)
        val config = DspRuntimeConfig(
            enabled = true,
            gainDb = 3.0,
            autoHeadroomEnabled = false,
            dynamicEq = DspDynamicEqConfig(enabled = true, bands = bands),
        )

        val prepared44100 = config.prepare(DspAudioFormat(44_100, 2))
        val prepared48000 = config.prepare(DspAudioFormat(48_000, 2))

        assertEquals(0.0, prepared44100.dynamicEq[1], 0.0)
        assertEquals(1.0, prepared48000.dynamicEq[1], 0.0)
        assertEquals(3.0, prepared44100.appliedPreampDb, 0.0)
        assertEquals(20_000.0, config.dynamicEq.bands[0].frequencyHz, 0.0)
        assertEquals(19_800.0, DspDynamicEqConfig.UI_MAX_FREQUENCY_HZ, 0.0)
    }
}
