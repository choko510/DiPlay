package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspAdvancedMsConfigTest {
    @Test
    fun defaultsOffAndFirstEnableFoldsLegacyWidthAndMonoBassIntoBands() {
        val config = DspAdvancedMsConfig()
        assertFalse(config.enabled)
        assertEquals(0.0, config.worstCaseBoostDb, 0.0)

        val folded = DspAdvancedMsConfig.fromLegacy(
            width = 1.5,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 120),
        )

        assertTrue(folded.enabled)
        assertEquals(120.0, folded.crossoverHz, 0.0)
        assertEquals(0.0, folded.lowBandWidth, 0.0)
        assertEquals(1.5, folded.highBandWidth, 0.0)
    }

    @Test
    fun advancedWidthAndToneContributeConservativelyToHeadroom() {
        val config = DspAdvancedMsConfig(
            enabled = true,
            centerFocusDb = 2.0,
            midLowGainDb = 1.0,
            sideHighGainDb = 3.0,
            highBandWidth = 2.0,
        )

        assertEquals(9.020599913, config.worstCaseBoostDb, 0.001)
        val prepared = DspRuntimeConfig(enabled = true, advancedMs = config)
            .prepare(DspAudioFormat(48_000, 2))
        assertEquals(10.020599913, prepared.headroom.reductionDb, 0.02)
        assertEquals(-10.020599913, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun configurationRequiresSupportedSampleRateAndBoundedParameters() {
        val config = DspAdvancedMsConfig(enabled = true, crossoverHz = 1_000.0)

        assertTrue(runCatching { config.validateForSampleRate(48_000) }.isSuccess)
        assertTrue(runCatching { config.validateForSampleRate(2_000) }.isFailure)
        assertTrue(runCatching { DspAdvancedMsConfig(lowBandWidth = 2.01) }.isFailure)
        assertTrue(runCatching { DspMsSideCompressorConfig(ratio = 8.1) }.isFailure)
    }
}
