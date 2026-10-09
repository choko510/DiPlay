package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinCorrectionConfigTest {
    @Test
    fun defaultCorrectionIsOffAndCutOnly() {
        val config = DspCabinCorrectionConfig()

        assertFalse(config.enabled)
        assertTrue(config.cuts.isEmpty())
        DspCabinCut(frequencyHz = 40.0, q = 0.5, reductionDb = -6.0)
        DspCabinCut(frequencyHz = 250.0, q = 8.0, reductionDb = 0.0)

        assertFailsWithIllegalArgument { DspCabinCut(frequencyHz = 80.0, q = 4.0, reductionDb = 0.1) }
        assertFailsWithIllegalArgument { DspCabinCut(frequencyHz = 39.9, q = 4.0, reductionDb = -3.0) }
        assertFailsWithIllegalArgument { DspCabinCut(frequencyHz = 80.0, q = 8.1, reductionDb = -3.0) }
    }

    @Test
    fun numberOfCutsAndCalibrationIdsAreBounded() {
        val cuts = List(DspCabinCorrectionConfig.MAX_CUTS) {
            DspCabinCut(frequencyHz = 50.0 + it * 30.0, q = 2.0, reductionDb = -2.0)
        }
        DspCabinCorrectionConfig(calibrationId = "calibration_01", cuts = cuts)

        assertFailsWithIllegalArgument {
            DspCabinCorrectionConfig(calibrationId = "../outside", cuts = cuts)
        }
        assertFailsWithIllegalArgument {
            DspCabinCorrectionConfig(cuts = cuts + DspCabinCut(frequencyHz = 220.0, q = 2.0, reductionDb = -1.0))
        }
    }

    @Test
    fun dynamicControlRequiresAnImportedMeasuredCutAndIsLimitedToThreeDecibels() {
        assertFailsWithIllegalArgument {
            DspCabinCorrectionConfig(calibrationId = "cal1", dynamicControlEnabled = true)
        }
        assertFailsWithIllegalArgument {
            DspCabinCorrectionConfig(
                calibrationId = "cal1",
                dynamicControlEnabled = true,
                cuts = listOf(DspCabinCut(82.0, 4.0, -3.0, enabled = true)),
            )
        }
        DspCabinCorrectionConfig(
            calibrationId = "cal1",
            dynamicControlEnabled = true,
            maxDynamicCutDb = 3.0,
            cuts = listOf(
                DspCabinCut(
                    frequencyHz = 82.0,
                    q = 4.0,
                    reductionDb = -3.0,
                    enabled = true,
                    source = DspCabinCutSource.MEASUREMENT,
                    confidence = 0.8,
                ),
            ),
        )
        assertFailsWithIllegalArgument {
            DspCabinCorrectionConfig(maxDynamicCutDb = 3.01)
        }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        org.junit.Assert.assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
