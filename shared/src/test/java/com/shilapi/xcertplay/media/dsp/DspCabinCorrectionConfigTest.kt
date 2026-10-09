package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinCorrectionConfigTest {
    @Test
    fun defaultCabinCorrectionIsOffAndHasNoCuts() {
        val config = DspCabinCorrectionConfig()

        assertFalse(config.enabled)
        assertFalse(config.dynamicControlEnabled)
        assertTrue(config.cuts.isEmpty())
    }

    @Test
    fun dynamicControlRequiresAnApprovedMeasurementCutAndCalibrationReference() {
        DspCabinCorrectionConfig(
            enabled = true,
            calibrationId = "cal_front",
            dynamicControlEnabled = true,
            cuts = listOf(
                DspCabinCut(82.0, 4.0, -4.0, enabled = true, source = DspCabinCutSource.MEASUREMENT),
            ),
        )

        assertInvalid {
            DspCabinCorrectionConfig(
                enabled = true,
                dynamicControlEnabled = true,
                cuts = listOf(DspCabinCut(82.0, 4.0, -4.0, enabled = true)),
            )
        }
        assertInvalid {
            DspCabinCorrectionConfig(
                enabled = true,
                calibrationId = "cal_front",
                dynamicControlEnabled = true,
                cuts = listOf(DspCabinCut(82.0, 4.0, -4.0, enabled = false, source = DspCabinCutSource.MEASUREMENT)),
            )
        }
    }

    @Test
    fun cutCountAndDynamicReductionAreBounded() {
        DspCabinCorrectionConfig(cuts = List(DspCabinCorrectionConfig.MAX_CUTS) { index ->
            DspCabinCut(40.0 + index * 40.0, 4.0, -6.0)
        })

        assertInvalid {
            DspCabinCorrectionConfig(cuts = List(DspCabinCorrectionConfig.MAX_CUTS + 1) { index ->
                DspCabinCut(40.0 + index * 20.0, 4.0, -3.0)
            })
        }
        assertInvalid { DspCabinCorrectionConfig(maxDynamicCutDb = 3.01) }
        assertInvalid { DspCabinCut(39.0, 4.0, -3.0) }
        assertInvalid { DspCabinCut(82.0, 8.1, -3.0) }
        assertInvalid { DspCabinCut(82.0, 4.0, 0.01) }
    }

    private fun assertInvalid(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
