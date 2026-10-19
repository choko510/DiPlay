package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspHarmonicConfigTest {
    @Test
    fun defaultsOffAndQualityDefinesOversamplingLatency() {
        val config = DspHarmonicConfig()

        assertFalse(config.isActive)
        assertEquals(0, config.algorithmicLatencyFrames)
        assertEquals(DspHarmonicConfig.NATIVE_VALUE_COUNT, config.toNativeValues().size)
        assertEquals(4, DspHarmonicQuality.LOW.latencyFrames)
        assertEquals(8, DspHarmonicQuality.BALANCED.latencyFrames)
        assertEquals(16, DspHarmonicQuality.HIGH.latencyFrames)
    }

    @Test
    fun enabledHarmonicModulesHaveFixedLatencyAndBoundedHeadroomEstimate() {
        val config = DspHarmonicConfig(
            highExciter = DspHighExciterConfig(enabled = true, amount = 0.2),
            virtualBass = DspVirtualBassConfig(enabled = true, amount = 0.3),
            quality = DspHarmonicQuality.HIGH,
            levelMatchEnabled = false,
        )

        assertTrue(config.isActive)
        assertEquals(16, config.algorithmicLatencyFrames)
        assertEquals(20.0 * kotlin.math.log10(1.5), config.maximumBoostDb, 1e-12)
    }

    @Test
    fun highExciterAboveRateSafetyMarginIsDisabledForThatFormat() {
        val config = DspHarmonicConfig(
            highExciter = DspHighExciterConfig(enabled = true, amount = 0.2, crossoverHz = 6_000.0),
            quality = DspHarmonicQuality.BALANCED,
        )

        val lowRate = config.effectiveFor(DspAudioFormat(8_000, 2))
        val standardRate = config.effectiveFor(DspAudioFormat(48_000, 2))

        assertFalse(lowRate.highExciter.enabled)
        assertFalse(lowRate.isActive)
        assertEquals(0, lowRate.algorithmicLatencyFrames)
        assertTrue(standardRate.highExciter.enabled)
        assertEquals(8, standardRate.algorithmicLatencyFrames)
    }

    @Test
    fun amountsAndCutoffsAreValidated() {
        assertTrue(runCatching { DspHighExciterConfig(amount = 0.31) }.isFailure)
        assertTrue(runCatching { DspHighExciterConfig(crossoverHz = 2_999.0) }.isFailure)
        assertTrue(runCatching { DspVirtualBassConfig(amount = Double.NaN) }.isFailure)
        assertTrue(runCatching { DspVirtualBassConfig(crossoverHz = 161.0) }.isFailure)
    }

    @Test
    fun autoHeadroomIncludesTheBoundedHarmonicPeakEstimate() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            harmonic = DspHarmonicConfig(
                highExciter = DspHighExciterConfig(enabled = true, amount = 0.2),
                levelMatchEnabled = false,
            ),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(1.0 + 20.0 * kotlin.math.log10(1.2), prepared.headroom.reductionDb, 1e-12)
        assertEquals(-prepared.headroom.reductionDb, prepared.appliedPreampDb, 1e-12)
    }
}
