package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DspAdvancedMsConfigTest {
    @Test
    fun defaultsAreDisabledWithIdentityWidthsAndNoEqBands() {
        val config = DspAdvancedMsConfig()

        assertFalse(config.enabled)
        assertEquals(DspAdvancedMsMonitorMode.NORMAL, config.monitorMode)
        assertEquals(0.0, config.midGainDb, 0.0)
        assertEquals(0.0, config.sideGainDb, 0.0)
        assertEquals(emptyList<DspEqBand>(), config.midEqBands)
        assertEquals(emptyList<DspEqBand>(), config.sideEqBands)
        assertEquals(1.0, config.lowWidth, 0.0)
        assertEquals(1.0, config.midWidth, 0.0)
        assertEquals(1.0, config.highWidth, 0.0)
        assertEquals(150.0, config.lowMidCrossoverHz, 0.0)
        assertEquals(4_000.0, config.midHighCrossoverHz, 0.0)
    }

    @Test
    fun supportedGainWidthAndEqBandLimitsAreEnforced() {
        DspAdvancedMsConfig(midGainDb = -6.0, sideGainDb = 6.0, lowWidth = 0.0, midWidth = 1.5, highWidth = 1.0)
        DspAdvancedMsConfig(
            midEqBands = List(DspAdvancedMsConfig.MAX_EQ_BANDS) {
                DspEqBand(DspEqType.PEAK, 500.0 + it * 500.0)
            },
        )

        assertFailsWithIllegalArgument { DspAdvancedMsConfig(midGainDb = 6.01) }
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(sideGainDb = Double.NaN) }
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(highWidth = 1.51) }
        assertFailsWithIllegalArgument {
            DspAdvancedMsConfig(midEqBands = List(DspAdvancedMsConfig.MAX_EQ_BANDS + 1) {
                DspEqBand(DspEqType.PEAK, 500.0 + it * 500.0)
            })
        }
    }

    @Test
    fun crossoversAndCenterFocusAreBoundedAndOrdered() {
        DspAdvancedMsConfig(
            lowMidCrossoverHz = 80.0,
            midHighCrossoverHz = 8_000.0,
            centerFocusDb = -3.0,
        )
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(lowMidCrossoverHz = 79.0) }
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(midHighCrossoverHz = 8_001.0) }
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(lowMidCrossoverHz = 500.0, midHighCrossoverHz = 1_000.0 - 0.1) }
        assertFailsWithIllegalArgument { DspAdvancedMsConfig(centerFocusDb = 3.01) }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        org.junit.Assert.assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
