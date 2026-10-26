package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEditorPanesTest {
    @Test
    fun responseGraphModelUsesEnabledPeakBandsAndPreamp() {
        val bands = listOf(
            DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 6.0),
            DspEqBand(DspEqType.PEAK, frequencyHz = 2_000.0, gainDb = 12.0, enabled = false),
        )

        assertEquals(8.0, dspEqResponseDb(1_000.0, bands, preampDb = 2.0), 1e-9)
        assertEquals(2.0, dspEqResponseDb(18_000.0, bands, preampDb = 2.0), 0.01)
    }

    @Test
    fun responseGraphUsesRuntimeBiquadResponseForAllEqTypes() {
        fun response(type: DspEqType, atHz: Double) = dspEqResponseDb(
            frequencyHz = atHz,
            bands = listOf(DspEqBand(type, frequencyHz = 1_000.0, gainDb = 6.0)),
            sampleRateHz = 48_000,
        )

        assertEquals(6.0, response(DspEqType.PEAK, 1_000.0), 1e-6)
        assertTrue(response(DspEqType.LOW_SHELF, 20.0) > 5.0)
        assertTrue(response(DspEqType.HIGH_SHELF, 18_000.0) > 5.0)
        assertTrue(response(DspEqType.HIGH_PASS, 20.0) < -20.0)
        assertTrue(response(DspEqType.LOW_PASS, 18_000.0) < -20.0)
        assertTrue(response(DspEqType.NOTCH, 1_000.0) < -20.0)
        assertEquals(0.0, response(DspEqType.ALL_PASS, 1_000.0), 1e-6)
    }

    @Test
    fun responseGraphCaps44k1FrequencyAtRuntimeLimit() {
        val band = listOf(DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 6.0))

        assertEquals(
            dspEqResponseDb(19_845.0, band, sampleRateHz = 44_100),
            dspEqResponseDb(20_000.0, band, sampleRateHz = 44_100),
            1e-9,
        )
    }

    @Test
    fun activeEqCapacityIncludesApprovedCabinBands() {
        val peq = List(15) { index -> DspEqBand(DspEqType.PEAK, 100.0 + index, enabled = index < 12) }
        val cabin = List(3) { DspEqBand(DspEqType.PEAK, 50.0, enabled = true) }

        assertEquals(15, activeEqBandCount(peq, cabin))
        assertTrue(activeEqBandCount(peq.dropLast(1) + peq.last().copy(enabled = true), cabin) > DspAudioProfile.MAX_EQ_BANDS)
    }

    @Test
    fun graphPointerMappingUsesLogFrequencyAndClampsToGraphBounds() {
        assertEquals(20.0, eqGraphFrequencyAtX(0f, 100f, 20.0, 20_000.0), 1e-9)
        assertEquals(632.455532, eqGraphFrequencyAtX(50f, 100f, 20.0, 20_000.0), 1e-5)
        assertEquals(20_000.0, eqGraphFrequencyAtX(120f, 100f, 20.0, 20_000.0), 1e-9)
        assertEquals(20.0, eqGraphFrequencyAtX(50f, 0f, 20.0, 20_000.0), 1e-9)
        assertEquals(18.0, eqGraphGainAtY(-10f, 100f), 1e-9)
        assertEquals(0.0, eqGraphGainAtY(50f, 100f), 1e-9)
        assertEquals(-18.0, eqGraphGainAtY(150f, 100f), 1e-9)
        assertEquals(0.0, eqGraphGainAtY(50f, 0f), 1e-9)
    }

    @Test
    fun numericEqValuesStayInsideRuntimeBounds() {
        assertEquals(20.0, safeEqNumericValue(1.0, 20.0..19_845.0), 0.0)
        assertEquals(19_845.0, safeEqNumericValue(30_000.0, 20.0..19_845.0), 0.0)
        assertEquals(-18.0, safeEqNumericValue(Double.NEGATIVE_INFINITY, -18.0..18.0), 0.0)
        assertEquals(0.1, safeEqNumericValue(0.01, 0.1..20.0), 0.0)
        assertEquals(20.0, safeEqNumericValue(Double.NaN, 20.0..20_000.0), 0.0)
    }

    @Test
    fun graphEditsChangeOnlyTheEditorDraftProfile() {
        val applied = DspProfilePresets.find("flat")!!
        val session = DspEditorSession(applied, applied, initialMasterEnabled = false)
        val changedBand = session.draftProfile.eqBands.first().copy(frequencyHz = 900.0, gainDb = -3.0, q = 1.2)

        session.draftProfile = updatedEqBandProfile(session.draftProfile, 0, changedBand)

        assertEquals(changedBand, session.draftProfile.eqBands.first())
        assertEquals(applied, session.appliedProfile)
        assertTrue(session.hasUnappliedChanges)
    }
}
