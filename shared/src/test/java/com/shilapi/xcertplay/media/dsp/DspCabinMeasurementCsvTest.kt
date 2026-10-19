package com.shilapi.xcertplay.media.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinMeasurementCsvTest {
    @Test
    fun validatedMeasurementSuggestsOnlyBoundedLowFrequencyNotches() {
        val result = DspCabinMeasurementCsv.parse(
            csv { frequency ->
                70.0 + peak(frequency, centerHz = 80.0, prominenceDb = 9.0) +
                    peak(frequency, centerHz = 180.0, prominenceDb = 7.0)
            },
        )

        val ready = result as DspCabinMeasurementImportResult.Ready
        assertEquals(120, ready.summary.pointCount)
        assertEquals(-60.0, ready.summary.noiseFloorDb, 0.0)
        assertTrue(ready.suggestedNotches.isNotEmpty())
        assertTrue(ready.suggestedNotches.size <= DspCabinCorrectionConfig.MAX_NOTCHES)
        ready.suggestedNotches.forEach { notch ->
            assertTrue(notch.frequencyHz in 20.0..300.0)
            assertTrue(notch.gainDb in -12.0..-3.0)
            assertTrue(notch.q in 0.7..10.0)
            assertTrue(notch.enabled)
        }
        assertTrue(ready.suggestedNotches.any { abs(it.frequencyHz - 80.0) < 10.0 })
        assertTrue(ready.suggestedNotches.any { abs(it.frequencyHz - 180.0) < 20.0 })
    }

    @Test
    fun missingQualityMetadataClippingAndNoiseReturnCalibrationUnavailable() {
        val valid = csv { 70.0 }
        val missingMetadata = valid.lines().filterNot { it.startsWith("# noise_floor_db=") }.joinToString("\n")
        assertUnavailable(missingMetadata, DspCabinUnavailableReason.MISSING_QUALITY_METADATA)
        assertUnavailable(
            valid.replace("# clipped_samples=0", "# clipped_samples=12"),
            DspCabinUnavailableReason.INPUT_CLIPPED,
        )
        assertUnavailable(
            csv(noiseFloorDb = -40.0) { -35.0 },
            DspCabinUnavailableReason.NOISE_TOO_HIGH,
        )
    }

    @Test
    fun insufficientPointCountCoverageAndResolutionAreUnavailable() {
        assertUnavailable(
            csv(pointCount = 20) { peak(it, 80.0, 8.0) },
            DspCabinUnavailableReason.TOO_FEW_POINTS,
        )
        assertUnavailable(
            csv(minimumHz = 40.0, maximumHz = 240.0) { peak(it, 80.0, 8.0) },
            DspCabinUnavailableReason.INSUFFICIENT_FREQUENCY_SPAN,
        )
        val sparse = csv(pointCount = 32, logarithmic = false) { peak(it, 80.0, 8.0) }
        assertUnavailable(sparse, DspCabinUnavailableReason.INSUFFICIENT_FREQUENCY_RESOLUTION)
    }

    @Test
    fun malformedOrUnorderedRowsAreRejected() {
        assertEquals(DspCabinMeasurementImportResult.InvalidCsv, DspCabinMeasurementCsv.parse("not,a,measurement"))
        val valid = csv { 70.0 }
        val rows = valid.lines().toMutableList()
        val firstPointIndex = rows.indexOfFirst { it.firstOrNull()?.isDigit() == true }
        val firstPoint = rows[firstPointIndex]
        rows[firstPointIndex] = rows[firstPointIndex + 1]
        rows[firstPointIndex + 1] = firstPoint
        assertEquals(
            DspCabinMeasurementImportResult.InvalidCsv,
            DspCabinMeasurementCsv.parse(rows.joinToString("\n")),
        )
    }

    @Test
    fun flatResponseReturnsUnavailableInsteadOfInventingCorrections() {
        assertUnavailable(csv { 70.0 }, DspCabinUnavailableReason.NO_SIGNIFICANT_PEAKS)
    }

    private fun assertUnavailable(csv: String, reason: DspCabinUnavailableReason) {
        val result = DspCabinMeasurementCsv.parse(csv) as DspCabinMeasurementImportResult.CalibrationUnavailable
        assertEquals(reason, result.reason)
    }

    private fun csv(
        pointCount: Int = 120,
        minimumHz: Double = 20.0,
        maximumHz: Double = 300.0,
        noiseFloorDb: Double = -60.0,
        clippedSamples: Int = 0,
        logarithmic: Boolean = true,
        level: (Double) -> Double,
    ): String = buildString {
        appendLine("# noise_floor_db=$noiseFloorDb")
        appendLine("# clipped_samples=$clippedSamples")
        appendLine("frequency_hz,level_db")
        repeat(pointCount) { index ->
            val fraction = index.toDouble() / (pointCount - 1)
            val frequency = if (logarithmic) {
                minimumHz * (maximumHz / minimumHz).pow(fraction)
            } else {
                minimumHz + (maximumHz - minimumHz) * fraction
            }
            appendLine("$frequency,${level(frequency)}")
        }
    }

    private fun peak(frequency: Double, centerHz: Double, prominenceDb: Double): Double {
        val octaves = ln(frequency / centerHz) / ln(2.0)
        return prominenceDb * kotlin.math.exp(-(octaves * octaves) / 0.015)
    }
}
