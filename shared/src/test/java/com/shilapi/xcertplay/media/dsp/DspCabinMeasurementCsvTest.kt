package com.shilapi.xcertplay.media.dsp

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinMeasurementCsvTest {
    @Test
    fun validMeasuredResponseProducesAtMostThreeDisabledReviewableCuts() {
        val levels = DoubleArray(25) { 70.0 }.apply {
            this[5] = 78.0
            this[17] = 76.0
            this[22] = 75.0
        }

        val result = DspCabinMeasurementCsv.parse(csv(levels = levels)) as DspCabinMeasurementParseResult.Available

        assertEquals(3, result.suggestedNotchCount)
        assertEquals(3, result.correction.notches.size)
        assertFalse(result.correction.enabled)
        assertTrue(result.correction.notches.all { !it.enabled && it.gainDb in -12.0..0.0 })
        assertEquals(25, result.correction.measurement?.pointCount)
        assertEquals(64, result.correction.measurement?.sourceSha256?.length)
    }

    @Test
    fun cleanMeasurementWithoutPeaksCanBeSavedForManualCuts() {
        val result = DspCabinMeasurementCsv.parse(csv()) as DspCabinMeasurementParseResult.Available

        assertTrue(result.correction.notches.isEmpty())
        assertFalse(result.correction.enabled)
        assertEquals(25, result.correction.measurement?.pointCount)
    }

    @Test
    fun clippedLowMarginSparseAndNonSixthOctaveMeasurementsAreUnavailable() {
        assertUnavailable(csv(clipped = true), DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.CLIPPING_REPORTED)
        assertUnavailable(
            csv(noiseFloorDb = -80.0, levels = DoubleArray(25) { -70.0 }),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.NOISE_MARGIN,
        )
        assertUnavailable(
            csv(pointCount = 19),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.TOO_FEW_POINTS,
        )
        assertUnavailable(
            csv(pointCount = DspCabinMeasurementSummary.MAX_POINTS + 1),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.TOO_MANY_POINTS,
        )
        assertUnavailable(
            csv(stepOctaves = 0.25, pointCount = 24),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.RESOLUTION,
        )
    }

    @Test
    fun coverageAndInputSizeAreBounded() {
        assertUnavailable(
            csv(startFrequencyHz = 25.0),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.FREQUENCY_COVERAGE,
        )
        assertUnavailable(
            "x".repeat(DspCabinMeasurementCsv.MAX_INPUT_CHARACTERS + 1),
            DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.INPUT_TOO_LARGE,
        )
    }

    private fun assertUnavailable(
        contents: String,
        expectedReason: DspCabinMeasurementParseResult.CalibrationUnavailable.Reason,
    ) {
        val result = DspCabinMeasurementCsv.parse(contents) as DspCabinMeasurementParseResult.CalibrationUnavailable
        assertEquals(expectedReason, result.reason)
    }

    private fun csv(
        noiseFloorDb: Double = -80.0,
        clipped: Boolean = false,
        levels: DoubleArray? = null,
        pointCount: Int = 25,
        startFrequencyHz: Double = 20.0,
        stepOctaves: Double = 1.0 / 6.0,
    ): String = buildString {
        appendLine("# format=diplay-cabin-v1")
        appendLine("# noise_floor_db=$noiseFloorDb")
        appendLine("# clipped=$clipped")
        appendLine("frequency_hz,level_db")
        repeat(pointCount) { index ->
            append(startFrequencyHz * 2.0.pow(index * stepOctaves))
            append(',')
            appendLine(levels?.getOrNull(index) ?: 70.0)
        }
    }
}
