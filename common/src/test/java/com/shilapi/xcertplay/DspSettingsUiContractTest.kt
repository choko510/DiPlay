package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.dsp.DspEqType
import org.junit.Assert.assertEquals
import org.junit.Test

class DspSettingsUiContractTest {
    @Test
    fun parametricEqControlsMatchEachFilterType() {
        assertEquals(
            setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.GAIN, EqBandEditorControl.Q),
            eqBandEditorControls(DspEqType.PEAK),
        )
        for (type in listOf(DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
            assertEquals(
                setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.GAIN),
                eqBandEditorControls(type),
            )
        }
        for (type in listOf(DspEqType.HIGH_PASS, DspEqType.LOW_PASS, DspEqType.NOTCH, DspEqType.ALL_PASS)) {
            assertEquals(
                setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.Q),
                eqBandEditorControls(type),
            )
        }
    }

    @Test
    fun convolverWetUsesPercentOnlyInTheEditor() {
        assertEquals(100.0, convolverWetPercentValue(1.0), 0.0)
        assertEquals(50.0, convolverWetPercentValue(0.5), 0.0)
        assertEquals(25.0, convolverWetPercentValue(0.25), 0.0)
        assertEquals(1.0, convolverWetModelValue(100.0), 0.0)
        assertEquals(0.5, convolverWetModelValue(50.0), 0.0)
        assertEquals(0.25, convolverWetModelValue(25.0), 0.0)
    }
}
