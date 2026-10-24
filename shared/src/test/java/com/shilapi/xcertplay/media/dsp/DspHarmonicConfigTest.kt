package com.shilapi.xcertplay.media.dsp

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspHarmonicConfigTest {
    @Test
    fun moduleDefaultsOffAndFirQualitySelectsFixedLatency() {
        val defaults = DspHarmonicConfig()
        assertFalse(defaults.enabled)
        assertFalse(defaults.isActive)
        assertEquals(0, defaults.algorithmicLatencyFrames)

        val active = defaults.copy(enabled = true, exciterEnabled = true)
        assertEquals(4, active.copy(quality = DspHarmonicQuality.LOW).algorithmicLatencyFrames)
        assertEquals(8, active.copy(quality = DspHarmonicQuality.MEDIUM).algorithmicLatencyFrames)
        assertEquals(16, active.copy(quality = DspHarmonicQuality.HIGH).algorithmicLatencyFrames)
        assertTrue(active.requiresStateContinuity)
    }

    @Test
    fun amountAndCutoffBoundsAreEnforcedForSupportedFormats() {
        val config = DspHarmonicConfig(enabled = true, exciterEnabled = true)
        assertTrue(runCatching { config.validateForSampleRate(44_100) }.isSuccess)
        assertTrue(runCatching { config.validateForSampleRate(48_000) }.isSuccess)
        assertTrue(runCatching { config.validateForSampleRate(8_000) }.isFailure)
        assertTrue(runCatching { DspHarmonicConfig(exciterAmount = 0.51) }.isFailure)
        assertTrue(runCatching { DspHarmonicConfig(virtualBassCutoffHz = 251.0) }.isFailure)
    }

    @Test
    fun bothEffectsContributeToConservativeHeadroom() {
        val config = DspHarmonicConfig(
            enabled = true,
            exciterEnabled = true,
            exciterAmount = 0.5,
            virtualBassEnabled = true,
            virtualBassAmount = 0.5,
        )
        val levelMatchGain = 10.0.pow(-1.5 / 20.0)
        val expectedBoostDb = 20.0 * kotlin.math.log10(levelMatchGain + 1.0)

        assertEquals(expectedBoostDb, config.worstCaseBoostDb, 0.001)
        assertEquals(20.0 * kotlin.math.log10(2.0), config.copy(levelMatchEnabled = false).worstCaseBoostDb, 0.001)
        val prepared = DspRuntimeConfig(enabled = true, harmonic = config)
            .prepare(DspAudioFormat(48_000, 1))
        assertEquals(expectedBoostDb + 1.0, prepared.headroom.reductionDb, 0.02)
    }
}
