package com.shilapi.xcertplay.dsp

import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspCabinPeakDetectorTest {
    @Test
    fun narrowMeasuredPeaksProduceBoundedCutCandidates() {
        val points = response(
            peaks = listOf(Triple(82.0, 6.0, 4.0), Triple(117.0, 4.0, 3.0)),
        )

        val candidates = DspCabinPeakDetector.detect(points)

        assertEquals(2, candidates.size)
        assertEquals(82.0, candidates[0].frequencyHz, 3.0)
        assertEquals(117.0, candidates[1].frequencyHz, 4.0)
        assertEquals(-6.0, candidates[0].reductionDb, 0.5)
        assertTrue(candidates[0].q in 2.5..6.5)
        assertTrue(candidates[1].q in 1.8..5.0)
        assertTrue(candidates.all { it.confidence in 0.0..1.0 && it.prominenceDb >= 3.0 })
        assertTrue(candidates.all { it.toCut().source == com.shilapi.xcertplay.media.dsp.DspCabinCutSource.MEASUREMENT })
    }

    @Test
    fun flatBroadBassNullAndIsolatedNoiseDoNotBecomeRoomResonanceCandidates() {
        assertTrue(DspCabinPeakDetector.detect(response(emptyList())).isEmpty())
        assertTrue(DspCabinPeakDetector.detect(response(listOf(Triple(90.0, 8.0, 0.55)))).isEmpty())
        assertTrue(DspCabinPeakDetector.detect(response(listOf(Triple(90.0, -12.0, 4.0)))).isEmpty())

        val points = response(emptyList()).toMutableList()
        val closest = points.indices.minBy { kotlin.math.abs(points[it].frequencyHz - 100.0) }
        points[closest] = points[closest].copy(magnitudeDb = 12.0)
        assertTrue(DspCabinPeakDetector.detect(points).isEmpty())
    }

    @Test
    fun duplicateAndUnsortedMeasurementPointsAreRejected() {
        val valid = response(emptyList())
        assertTrue(runCatching { DspCabinPeakDetector.detect(valid + valid.last()) }.isFailure)
        assertTrue(runCatching { DspCabinPeakDetector.detect(valid.reversed()) }.isFailure)
    }

    private fun response(peaks: List<Triple<Double, Double, Double>>): List<DspCabinMeasurementPoint> {
        val points = ArrayList<DspCabinMeasurementPoint>()
        val steps = (ln(500.0 / 20.0) / ln(2.0) * STEPS_PER_OCTAVE).toInt()
        for (index in 0..steps) {
            val frequency = 20.0 * 2.0.pow(index.toDouble() / STEPS_PER_OCTAVE)
            val magnitude = peaks.sumOf { (centerHz, amplitudeDb, q) ->
                val sigma = (1.0 / (2.0 * q)) / kotlin.math.sqrt(2.0 * ln(2.0))
                amplitudeDb * kotlin.math.exp(-0.5 * (ln(frequency / centerHz) / sigma).pow(2.0))
            }
            points += DspCabinMeasurementPoint(frequency, magnitude)
        }
        return points
    }

    companion object {
        private const val STEPS_PER_OCTAVE = 100.0
    }
}
