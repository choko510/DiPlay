package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspAutoHeadroomTest {
    @Test
    fun preampAndMarginAreIncludedInAppliedGain() {
        val config = DspRuntimeConfig(enabled = true, gainDb = 6.0)

        val prepared = config.prepare(DspAudioFormat(48_000, 2))

        assertEquals(7.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(-1.0, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun sameFrequencyFiltersCombineTheirResponsesBeforeFindingThePeak() {
        val sameFrequency = listOf(
            DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 6.0, q = 4.0),
            DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 6.0, q = 4.0),
        )
        val differentFrequencies = listOf(
            DspEqBand(DspEqType.PEAK, 500.0, gainDb = 6.0, q = 4.0),
            DspEqBand(DspEqType.PEAK, 5_000.0, gainDb = 6.0, q = 4.0),
        )

        val same = DspAutoHeadroom.calculate(48_000, 0.0, sameFrequency)
        val different = DspAutoHeadroom.calculate(48_000, 0.0, differentFrequencies)

        assertTrue(same.filterPeakDb > 11.5)
        assertTrue(different.filterPeakDb < same.filterPeakDb)
        assertTrue(different.filterPeakDb > 5.5)
    }

    @Test
    fun bassMakeupAndWidthAreIncludedAtTheSameScannedFrequency() {
        val peq = listOf(DspEqBand(DspEqType.PEAK, 80.0, gainDb = 6.0, q = 4.0))
        val staticBass = listOf(DspEqBand(DspEqType.LOW_SHELF, 80.0, gainDb = 6.0, q = 0.1))
        val result = DspAutoHeadroom.calculate(
            sampleRate = 48_000,
            preampDb = 2.0,
            peqBands = peq,
            compressorMakeupDb = 3.0,
            stereoWidth = 2.0,
            marginDb = 1.0,
            staticBassBands = staticBass,
        )

        assertTrue(result.filterPeakDb > 8.5)
        assertEquals(
            2.0 + result.filterPeakDb + 3.0 + 20.0 * kotlin.math.log10(2.0) + 1.0,
            result.reductionDb,
            0.02,
        )
    }

    @Test
    fun stereoWidthReservesFullTheoreticalAntiphasePeakHeadroom() {
        val wide = DspRuntimeConfig(enabled = true, stereoWidth = 1.6, autoHeadroomMarginDb = 0.0)
        val prepared = wide.prepare(DspAudioFormat(48_000, 2))
        val expectedDb = 20.0 * kotlin.math.log10(1.6)
        assertEquals(expectedDb, prepared.headroom.reductionDb, 0.02)
        assertEquals(-expectedDb, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun disabledAutoHeadroomLeavesTheUserPreampUnchanged() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            gainDb = 4.0,
            peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 12.0)),
            autoHeadroomEnabled = false,
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(0.0, prepared.headroom.reductionDb, 0.0)
        assertEquals(4.0, prepared.appliedPreampDb, 0.0)
        assertEquals(5, prepared.eqCoefficients.size)
    }

    @Test
    fun negativeMakeupDoesNotReduceHeadroomAndStereoWidthOneIsNeutral() {
        val result = DspAutoHeadroom.calculate(
            sampleRate = 96_000,
            preampDb = 0.0,
            peqBands = emptyList(),
            compressorMakeupDb = -8.0,
            stereoWidth = 1.0,
        )

        assertEquals(0.0, result.filterPeakDb, 0.0)
        assertEquals(1.0, result.reductionDb, 0.0)
    }

    @Test
    fun studioToneHeadroomUsesConservativeL1SafetyBoundsAndExposesSteadyStateReference() {
        val macro = DspMacroToneConfig(enabled = true, bassGainDb = 4.0, vocalGainDb = 2.5, airGainDb = 2.0)
        val loudness = DspSmartLoudnessRuntimeConfig(enabled = true, lowGainDb = 3.0, highGainDb = 1.5)
        val config = DspRuntimeConfig(enabled = true, macroTone = macro, smartLoudness = loudness)

        val prepared = config.prepare(DspAudioFormat(48_000, 2))
        val expected = 1.0 + macro.conservativeBoostDb() + loudness.conservativeBoostDb()

        assertEquals(expected, prepared.headroom.reductionDb, 0.02)
        assertEquals(-expected, prepared.appliedPreampDb, 0.02)
        assertTrue(macro.conservativeBoostDb() > macro.bassGainDb + macro.vocalGainDb + macro.airGainDb)
        assertTrue(macro.expectedFrequencyResponsePeakDb(48_000) < macro.conservativeBoostDb())
    }

    @Test
    fun smartLoudnessHighPassTransientUsesItsWorstCaseImpulseGainForHeadroom() {
        val loudness = DspSmartLoudnessRuntimeConfig(enabled = true, highGainDb = 1.5)

        assertEquals(
            20.0 * kotlin.math.log10(1.0 + 2.0 * (StrictMath.pow(10.0, 1.5 / 20.0) - 1.0)),
            loudness.conservativeBoostDb(),
            0.0001,
        )
        assertTrue(loudness.conservativeBoostDb() > loudness.highGainDb)
        assertTrue(loudness.expectedFrequencyResponsePeakDb(48_000) in 0.7..2.0)
    }

    @Test
    fun enabledMultibandMakeupUsesTheLargestPositiveBandGainForHeadroom() {
        val config = DspRuntimeConfig(
            enabled = true,
            multiband = DspMultibandConfig(
                enabled = true,
                low = DspCompressorConfig(enabled = true, makeupDb = 2.0),
                mid = DspCompressorConfig(enabled = true, makeupDb = 5.0),
                high = DspCompressorConfig(enabled = true, makeupDb = 3.0),
            ),
        )

        val prepared = config.prepare(DspAudioFormat(48_000, 2))

        assertEquals(6.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(-6.0, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun dynamicBoostHeadroomReservesFullBoostEvenWhenDetectorBandThresholdIsLow() {
        val bands = DspDynamicEqConfig.defaultBands().toMutableList()
        bands[0] = DspDynamicEqBandConfig(enabled = true, thresholdDb = -24.0, maxBoostDb = 3.0, mode = DspDynamicEqMode.BOOST)
        bands[1] = DspDynamicEqBandConfig(enabled = true, thresholdDb = -2.0, maxBoostDb = 4.0, mode = DspDynamicEqMode.BOOST)
        bands[2] = DspDynamicEqBandConfig(enabled = true, maxBoostDb = 12.0, mode = DspDynamicEqMode.CUT)
        val config = DspRuntimeConfig(
            enabled = true,
            dynamicEq = DspDynamicEqConfig(enabled = true, bands = bands),
            dynamicBass = DspDynamicBassRuntimeConfig(enabled = true, strength = 1.0, maxBoostDb = 3.0, lowThresholdDbfs = -35.0),
        )

        val prepared = config.prepare(DspAudioFormat(48_000, 2))

        // 3.0 + 4.0 (Dynamic EQ) + 3.0 (Dynamic Bass) + 1.0 (margin) = 11.0 dB so full-scale out-of-band + quiet bass never clips.
        assertEquals(11.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(-11.0, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun frequencyScanAcceptsAnExactNotchZeroAtItsUpperEndpoint() {
        val result = DspAutoHeadroom.calculate(
            sampleRate = 48_000,
            preampDb = 0.0,
            peqBands = listOf(DspEqBand(DspEqType.NOTCH, 20_000.0)),
        )

        assertTrue(result.filterPeakDb.isFinite())
        assertEquals(0.0, result.filterPeakDb, 0.02)
    }

    @Test
    fun measuredCabinCutsReusePeqSlotsAndRejectMoreThanFifteenActiveFilters() {
        val cabinCorrection = DspCabinCorrectionConfig(
            enabled = true,
            notches = listOf(
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 70.0, gainDb = -6.0),
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 120.0, gainDb = -4.0),
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 210.0, gainDb = -3.0),
            ),
        )
        val existingBands = (0 until 12).map { index ->
            DspEqBand(DspEqType.PEAK, 300.0 + index * 100.0, gainDb = 0.0, enabled = true)
        }

        val prepared = DspRuntimeConfig(
            enabled = true,
            peqBands = existingBands,
            cabinCorrection = cabinCorrection,
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(DspEqDesigner.MAX_BANDS * DspEqDesigner.COEFFICIENTS_PER_BAND, prepared.eqCoefficients.size)
        assertTrue(
            runCatching {
                DspRuntimeConfig(
                    enabled = true,
                    peqBands = existingBands + DspEqBand(DspEqType.PEAK, 1_600.0, enabled = true),
                    cabinCorrection = cabinCorrection,
                ).prepare(DspAudioFormat(48_000, 2))
            }.isFailure,
        )
    }

    @Test
    fun staticBassShelfUsesASeparateGraphFilterWithoutConsumingTheFifteenPeqSlots() {
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            peqBands = (0 until DspEqDesigner.MAX_BANDS).map { index ->
                DspEqBand(DspEqType.PEAK, 100.0 + index * 100.0)
            },
            bass = DspBassConfig(enabled = true, gainDb = 3.0, frequencyHz = 90.0),
        )

        val prepared = config.prepare(DspAudioFormat(48_000, 2))

        assertEquals((DspEqDesigner.MAX_BANDS + 1) * DspEqDesigner.COEFFICIENTS_PER_BAND, prepared.eqCoefficients.size)
        assertEquals(0, prepared.bassCoefficients.size)
        assertEquals(DspEqDesigner.MAX_BANDS, config.peqBands.size)
    }

    @Test
    fun activeHarmonicBranchesAddTheirWorstCaseBoostToAutoHeadroom() {
        val config = DspRuntimeConfig(
            enabled = true,
            harmonic = DspHarmonicConfig(
                enabled = true,
                exciterEnabled = true,
                exciterAmount = 0.5,
                virtualBassEnabled = true,
                virtualBassAmount = 0.25,
            ),
        )

        val prepared = config.prepare(DspAudioFormat(48_000, 2))
        val expectedBoostDb = config.harmonic.worstCaseBoostDb
        assertEquals(expectedBoostDb + 1.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(-expectedBoostDb - 1.0, prepared.appliedPreampDb, 0.02)
    }

    @Test
    fun advancedMsSuppressesMonoBassAndLowSampleRateSanitizesSafely() {
        val advanced = DspRuntimeConfig(
            enabled = true,
            stereoWidth = 1.5,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 120),
            advancedMs = DspAdvancedMsConfig(enabled = true),
            macroTone = DspMacroToneConfig(enabled = true, relativeWidth = 1.25),
            harmonic = DspHarmonicConfig(enabled = true, exciterEnabled = true, exciterCutoffHz = 6_000.0),
        )
        val prepared48 = advanced.prepare(DspAudioFormat(48_000, 2))
        assertEquals(0, prepared48.monoBassCoefficients.size)
        assertEquals(0.0, prepared48.spatial[1], 0.0)
        assertEquals(1.0, prepared48.macroTone[4], 0.0)
        assertTrue(prepared48.diagnosticsNotes.contains("mono_bass_bypassed_by_advanced_ms"))

        // Low 8 kHz stream does not throw even with 6 kHz harmonic exciter configured (F-5).
        val prepared8 = advanced.prepare(DspAudioFormat(8_000, 2))
        assertEquals(0.0, prepared8.harmonic[0], 0.0)
        assertTrue(prepared8.diagnosticsNotes.contains("harmonic_disabled_for_sample_rate"))
    }

    @Test
    fun macroWidthIsBoundedWhenStackedWithSpatialWidthAndConvolverUsesSpectralPeak() {
        val stacked = DspRuntimeConfig(
            enabled = true,
            stereoWidth = 1.5,
            macroTone = DspMacroToneConfig(enabled = true, relativeWidth = 1.25),
        )
        val prepared = stacked.prepare(DspAudioFormat(48_000, 2))
        assertTrue(prepared.macroTone[4] * stacked.stereoWidth <= 1.61)
        assertTrue(prepared.diagnosticsNotes.contains("macro_width_capped_with_spatial"))

        // Dispersed impulse with flat unit spectrum (+-0.5 alternating? Sum(|h|)=1.0, use [0.5, 0.5, 0.0, 0.0]) vs oscillating IR
        val alternatingIr = DspImpulseResponse(
            id = "osc",
            sampleRate = 48_000,
            channels = 1,
            samples = FloatArray(16) { index -> if (index % 2 == 0) 0.1f else -0.1f },
        )
        // L1 time sum would be 1.6 (~+4.08 dB), whereas Nyquist alternating sequence has spectral peak 1.6 only at Nyquist;
        // compare with dense orthogonal impulse [0.5, 0.5, 0.5, -0.5] where L1 sum = 2.0 (6.02 dB) vs spectral peak ~ 1.414 (3.01 dB).
        val barker4 = DspImpulseResponse("barker4", 48_000, 1, floatArrayOf(0.5f, 0.5f, 0.5f, -0.5f))
        assertEquals(20.0 * kotlin.math.log10(2.0), barker4.conservativePeakGainDb(1.0), 0.001)
        assertTrue(barker4.spectralPeakGainDb(1.0) < 4.0)
        assertTrue(alternatingIr.conservativePeakGainDb(1.0) > 0.0)
    }
}
