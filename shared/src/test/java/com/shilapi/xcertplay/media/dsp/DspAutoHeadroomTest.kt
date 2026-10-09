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
    fun positiveSeatTrimIsIncludedOnlyWhenSeatFocusIsActive() {
        val active = DspRuntimeConfig(
            enabled = true,
            seatFocus = DspSeatFocusConfig(enabled = true, leftTrimDb = 6.0, rightTrimDb = -6.0),
        ).prepare(DspAudioFormat(48_000, 2))
        val bypassed = DspRuntimeConfig(
            enabled = true,
            seatFocus = DspSeatFocusConfig(enabled = true, bypassed = true, leftTrimDb = 6.0),
        ).prepare(DspAudioFormat(48_000, 2))
        val mono = DspRuntimeConfig(
            enabled = true,
            seatFocus = DspSeatFocusConfig(enabled = true, leftTrimDb = 6.0),
        ).prepare(DspAudioFormat(48_000, 1))

        assertEquals(7.0, active.headroom.reductionDb, 0.02)
        assertEquals(1.0, bypassed.headroom.reductionDb, 0.02)
        assertEquals(1.0, mono.headroom.reductionDb, 0.02)
        assertEquals(0.0, mono.seatFocus[0], 0.0)
    }

    @Test
    fun advancedMsGainEqAndBandWidthAreIncludedInHeadroom() {
        val midGain = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(enabled = true, midGainDb = 6.0),
        ).prepare(DspAudioFormat(48_000, 2))
        val sideWidth = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(enabled = true, highWidth = 1.5),
        ).prepare(DspAudioFormat(48_000, 2))
        val midEq = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                midEqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 5.0, q = 2.0)),
            ),
        ).prepare(DspAudioFormat(48_000, 2))
        val globallyWideButBandNarrow = DspRuntimeConfig(
            enabled = true,
            stereoWidth = 2.0,
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                lowWidth = 0.5,
                midWidth = 0.5,
                highWidth = 0.5,
            ),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(10.0, midGain.headroom.reductionDb, 0.02)
        assertTrue(sideWidth.headroom.reductionDb > 4.0)
        assertTrue(midEq.headroom.reductionDb > 5.5)
        assertEquals(1.0, globallyWideButBandNarrow.headroom.reductionDb, 0.02)
    }

    @Test
    fun cabinCutOnlyLayersDoNotAddPositiveHeadroomReduction() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                cuts = listOf(DspCabinCut(82.0, 4.0, -6.0, enabled = true)),
            ),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(1.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(5, prepared.cabinStaticCoefficients.size)
        assertEquals(0.0, prepared.cabinDynamicEq[0], 0.0)
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
    fun dynamicBoostHeadroomIncludesEveryEnabledBoostBandMaximum() {
        val bands = DspDynamicEqConfig.defaultBands().toMutableList()
        bands[0] = DspDynamicEqBandConfig(enabled = true, maxBoostDb = 3.0, mode = DspDynamicEqMode.BOOST)
        bands[1] = DspDynamicEqBandConfig(enabled = true, maxBoostDb = 4.0, mode = DspDynamicEqMode.BOOST)
        bands[2] = DspDynamicEqBandConfig(enabled = true, maxBoostDb = 12.0, mode = DspDynamicEqMode.CUT)
        val config = DspRuntimeConfig(
            enabled = true,
            dynamicEq = DspDynamicEqConfig(enabled = true, bands = bands),
        )

        val prepared = config.prepare(DspAudioFormat(48_000, 2))

        assertEquals(8.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(-8.0, prepared.appliedPreampDb, 0.02)
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
}
