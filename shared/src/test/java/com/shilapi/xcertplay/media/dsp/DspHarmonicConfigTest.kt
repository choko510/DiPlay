package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DspHarmonicConfigTest {
    @Test
    fun engineAndBothEffectsDefaultOff() {
        val config = DspHarmonicEngineConfig()

        assertFalse(config.enabled)
        assertEquals(DspHarmonicQuality.TWO_X, config.quality)
        assertFalse(config.highExciter.enabled)
        assertFalse(config.virtualBass.enabled)
        assertEquals(DspHarmonicMode.MIXED, config.highExciter.mode)
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

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        org.junit.Assert.assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
