package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspHarmonicConfigTest {
    @Test
    fun engineAndBothEffectsDefaultOff() {
        val config = DspHarmonicEngineConfig()

        assertFalse(config.enabled)
        assertFalse(config.bypassed)
        assertEquals(DspHarmonicQuality.TWO_X, config.quality)
        assertFalse(config.highExciter.enabled)
        assertFalse(config.virtualBass.enabled)
        assertEquals(DspHarmonicMode.MIXED, config.highExciter.mode)
        assertEquals(18, DspHarmonicEngineConfig.NATIVE_VALUE_COUNT)
        assertEquals(64, DspHarmonicEngineConfig.ALGORITHM_LATENCY_FRAMES)
        assertEquals(16_000, DspHarmonicEngineConfig.MIN_SAMPLE_RATE_HZ)
        assertEquals(0.42, DspHarmonicEngineConfig.MAX_BAND_SAMPLE_RATE_FRACTION, 0.0)
    }

    @Test
    fun highExciterParametersRejectInvalidRangesAndNonFiniteValues() {
        DspHighExciterConfig(
            enabled = true,
            frequencyHz = 16_000.0,
            drive = 8.0,
            amount = 1.0,
            mode = DspHarmonicMode.ODD,
            wet = 1.0,
            outputCompensationDb = -12.0,
        )

        assertFailsWithIllegalArgument { DspHighExciterConfig(frequencyHz = 999.0) }
        assertFailsWithIllegalArgument { DspHighExciterConfig(drive = Double.POSITIVE_INFINITY) }
        assertFailsWithIllegalArgument { DspHighExciterConfig(amount = 1.01) }
        assertFailsWithIllegalArgument { DspHighExciterConfig(wet = -0.01) }
        assertFailsWithIllegalArgument { DspHighExciterConfig(outputCompensationDb = 6.01) }
    }

    @Test
    fun virtualBassHasBoundedFundamentalAndIndependentSecondThirdHarmonicAmounts() {
        DspVirtualBassConfig(
            enabled = true,
            fundamentalLowHz = 35.0,
            fundamentalHighHz = 80.0,
            secondHarmonicAmount = 1.0,
            thirdHarmonicAmount = 0.0,
        )

        assertFailsWithIllegalArgument { DspVirtualBassConfig(fundamentalLowHz = 34.9) }
        assertFailsWithIllegalArgument { DspVirtualBassConfig(fundamentalLowHz = 75.0, fundamentalHighHz = 70.0) }
        assertFailsWithIllegalArgument { DspVirtualBassConfig(secondHarmonicAmount = Double.NaN) }
        assertFailsWithIllegalArgument { DspVirtualBassConfig(thirdHarmonicAmount = 1.01) }
    }

    @Test
    fun preparedDiagnosticsDescribeActiveModulesLatencyAndCalibration() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            seatFocus = DspSeatFocusConfig(enabled = true),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                calibrationId = "cal_front",
                cuts = listOf(
                    DspCabinCut(
                        frequencyHz = 82.0,
                        q = 4.0,
                        reductionDb = -3.0,
                        enabled = true,
                        source = DspCabinCutSource.MEASUREMENT,
                    ),
                ),
            ),
            harmonicEngine = DspHarmonicEngineConfig(
                enabled = true,
                quality = DspHarmonicQuality.FOUR_X,
                highExciter = DspHighExciterConfig(enabled = true, wet = 1.0),
            ),
        ).prepare(DspAudioFormat(48_000, 2))

        assertTrue(prepared.activeModulesMask and DspActiveModuleMask.MASTER != 0)
        assertTrue(prepared.activeModulesMask and DspActiveModuleMask.SEAT_FOCUS != 0)
        assertTrue(prepared.activeModulesMask and DspActiveModuleMask.CABIN_CORRECTION != 0)
        assertTrue(prepared.activeModulesMask and DspActiveModuleMask.HIGH_EXCITER != 0)
        assertEquals(264, prepared.seatLatencyFrames)
        assertEquals(DspHarmonicQuality.FOUR_X, prepared.harmonicQuality)
        assertEquals("cal_front", prepared.cabinCalibrationId)
        assertTrue(prepared.autoHeadroomDb > 1.0)
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        org.junit.Assert.assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
