package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinCorrectionConfigTest {
    @Test
    fun cabinNotchesUseExistingPeqAndRemainInactiveUntilApproved() {
        val summary = summary()
        val notch = DspCabinNotchConfig(frequencyHz = 80.0, gainDb = -6.0, q = 2.0)
        val storedButDisabled = DspCabinCorrectionConfig(
            enabled = false,
            measurement = summary,
            notches = listOf(notch),
        )
        val disabled = DspRuntimeConfig(enabled = true, cabinCorrection = storedButDisabled)
            .prepare(DspAudioFormat(48_000, 2))

        assertEquals(0, disabled.eqCoefficients.size)

        val approved = DspRuntimeConfig(
            enabled = true,
            cabinCorrection = storedButDisabled.copy(enabled = true),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(DspEqDesigner.COEFFICIENTS_PER_BAND, approved.eqCoefficients.size)
        assertTrue(approved.eqCoefficients[0] < 1.0)
    }

    @Test
    fun approvedCabinNotchesCannotExceedRemainingPeqSlots() {
        val allBands = (1..DspEqDesigner.MAX_BANDS).map { index ->
            DspEqBand(
                type = DspEqType.PEAK,
                frequencyHz = index * 100.0,
                gainDb = 0.0,
                enabled = true,
            )
        }
        val activeCorrection = DspCabinCorrectionConfig(
            enabled = true,
            measurement = summary(),
            notches = listOf(DspCabinNotchConfig(frequencyHz = 80.0, gainDb = -6.0, q = 2.0)),
        )

        assertTrue(
            runCatching {
                DspRuntimeConfig(enabled = true, peqBands = allBands, cabinCorrection = activeCorrection)
            }.isFailure,
        )
    }

    private fun summary() = DspCabinMeasurementSummary(
        noiseFloorDb = -60.0,
        pointCount = 120,
        minimumFrequencyHz = 20.0,
        maximumFrequencyHz = 300.0,
        clippedSamples = 0,
    )
}
