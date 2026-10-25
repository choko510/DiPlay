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
        assertEquals(2.0, dspEqResponseDb(20_000.0, bands, preampDb = 2.0), 1e-6)
    }

    @Test
    fun activeEqCapacityIncludesApprovedCabinBands() {
        val peq = List(15) { index -> DspEqBand(DspEqType.PEAK, 100.0 + index, enabled = index < 12) }
        val cabin = List(3) { DspEqBand(DspEqType.PEAK, 50.0, enabled = true) }

        assertEquals(15, activeEqBandCount(peq, cabin))
        assertTrue(activeEqBandCount(peq.dropLast(1) + peq.last().copy(enabled = true), cabin) > DspAudioProfile.MAX_EQ_BANDS)
    }
}
