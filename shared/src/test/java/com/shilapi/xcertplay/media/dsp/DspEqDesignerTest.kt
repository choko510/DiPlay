package com.shilapi.xcertplay.media.dsp

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEqDesignerTest {
    @Test
    fun peakFilterMatchesTheRequestedCenterGain() {
        val band = band(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 6.0, q = 1.0)
        val magnitude = DspEqDesigner.design(48_000, band).magnitudeAt(48_000, 1_000.0)

        assertEquals(10.0.pow(6.0 / 20.0), magnitude, 1e-10)
    }

    @Test
    fun shelfUsesFixedSlopeAndIgnoresTheBandQ() {
        val lowQ = DspEqDesigner.design(
            48_000,
            band(DspEqType.LOW_SHELF, frequencyHz = 200.0, gainDb = 6.0, q = 0.1),
        )
        val highQ = DspEqDesigner.design(
            48_000,
            band(DspEqType.LOW_SHELF, frequencyHz = 200.0, gainDb = 6.0, q = 20.0),
        )
        val highShelf = DspEqDesigner.design(
            48_000,
            band(DspEqType.HIGH_SHELF, frequencyHz = 4_000.0, gainDb = 6.0, q = 2.0),
        )

        assertEquals(lowQ, highQ)
        assertEquals(10.0.pow(6.0 / 20.0), lowQ.magnitudeAt(48_000, 20.0), 0.002)
        assertEquals(10.0.pow(6.0 / 20.0), highShelf.magnitudeAt(48_000, 20_000.0), 0.002)
    }

    @Test
    fun passFiltersNotchAndAllPassHaveTheirExpectedResponses() {
        val frequency = 1_000.0
        val q = 1.0 / sqrt(2.0)
        val highPass = DspEqDesigner.design(48_000, band(DspEqType.HIGH_PASS, frequency, q = q))
        val lowPass = DspEqDesigner.design(48_000, band(DspEqType.LOW_PASS, frequency, q = q))
        val notch = DspEqDesigner.design(48_000, band(DspEqType.NOTCH, frequency, q = q))
        val allPass = DspEqDesigner.design(48_000, band(DspEqType.ALL_PASS, frequency, q = q))

        assertEquals(1.0 / sqrt(2.0), highPass.magnitudeAt(48_000, frequency), 1e-10)
        assertEquals(1.0 / sqrt(2.0), lowPass.magnitudeAt(48_000, frequency), 1e-10)
        assertEquals(0.0, notch.magnitudeAt(48_000, frequency), 1e-10)
        for (probe in listOf(100.0, 1_000.0, 10_000.0)) {
            assertEquals(1.0, allPass.magnitudeAt(48_000, probe), 1e-10)
        }
    }

    @Test
    fun sampleRatesQEndpointsAndNearNyquistRemainFinite() {
        for (sampleRate in listOf(44_100, 48_000, 96_000)) {
            val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
            for (q in listOf(0.1, 20.0)) {
                val coefficients = DspEqDesigner.design(
                    sampleRate,
                    band(DspEqType.PEAK, maxFrequency, gainDb = 18.0, q = q),
                )
                assertFinite(coefficients)
                assertTrue(coefficients.magnitudeAt(sampleRate, maxFrequency) > 0.0)
            }
        }
    }

    @Test
    fun oneThousandDeterministicBandConfigurationsProduceStableFiniteCoefficients() {
        val random = Random(0xD15EA5E)
        val types = DspEqType.entries
        for (index in 0 until 1_000) {
            val sampleRate = listOf(44_100, 48_000, 96_000)[random.nextInt(3)]
            val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
            val band = band(
                type = types[random.nextInt(types.size)],
                frequencyHz = 20.0 + random.nextDouble() * (maxFrequency - 20.0),
                gainDb = -18.0 + random.nextDouble() * 36.0,
                q = 0.1 + random.nextDouble() * 19.9,
            )
            val coefficients = DspEqDesigner.design(sampleRate, band)
            assertFinite(coefficients)
            assertTrue(kotlin.math.abs(coefficients.a2) < 1.0)
            assertTrue(1.0 + coefficients.a1 + coefficients.a2 > 0.0)
            assertTrue(1.0 - coefficients.a1 + coefficients.a2 > 0.0)
        }
    }

    @Test
    fun runtimeConfigCopiesBandsAndEnforcesTheFifteenBandLimit() {
        val mutableBands = mutableListOf(band(DspEqType.PEAK, 1_000.0))
        val config = DspRuntimeConfig(enabled = true, peqBands = mutableBands)
        mutableBands.clear()
        assertEquals(1, config.peqBands.size)

        val tooManyBands = List(16) { band(DspEqType.PEAK, 1_000.0) }
        assertTrue(runCatching { DspRuntimeConfig(enabled = true, peqBands = tooManyBands) }.isFailure)
        assertTrue(
            runCatching {
                DspEqDesigner.design(8_000, band(DspEqType.PEAK, 4_000.0))
            }.isFailure,
        )
    }

    private fun assertFinite(coefficients: DspBiquadCoefficients) {
        assertTrue(coefficients.b0.isFinite())
        assertTrue(coefficients.b1.isFinite())
        assertTrue(coefficients.b2.isFinite())
        assertTrue(coefficients.a1.isFinite())
        assertTrue(coefficients.a2.isFinite())
    }

    private fun band(
        type: DspEqType,
        frequencyHz: Double,
        gainDb: Double = 0.0,
        q: Double = 0.7071067811865476,
    ) = DspEqBand(type, frequencyHz, gainDb, q)
}
