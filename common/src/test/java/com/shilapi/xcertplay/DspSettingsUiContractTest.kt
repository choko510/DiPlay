package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.dsp.DspSoundMacroSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class DspSettingsUiContractTest {
    @Test
    fun studioNavigationExposesTheFiveProductTabsInOrder() {
        assertEquals(
            listOf(
                DspStudioTab.STUDIO,
                DspStudioTab.EQ,
                DspStudioTab.SPATIAL,
                DspStudioTab.DYNAMICS,
                DspStudioTab.ADVANCED,
            ),
            dspStudioTabs(),
        )
    }

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

    @Test
    fun macroControlsAreBoundedAndOnlyUpdateTheirOwnValue() {
        val initial = DspSoundMacroSettings()
        assertEquals(false, initial.enabled)
        assertEquals(listOf(50, 50, 50, 50), listOf(
            initial.bassImpact,
            initial.vocalPresence,
            initial.airDetail,
            initial.soundstageWidth,
        ))

        val bass = updateDspMacro(initial, DspMacroControl.BASS_IMPACT, 120)
        assertEquals(100, bass.bassImpact)
        assertEquals(initial.vocalPresence, bass.vocalPresence)
        assertEquals(initial.airDetail, bass.airDetail)
        assertEquals(initial.soundstageWidth, bass.soundstageWidth)

        val width = updateDspMacro(initial, DspMacroControl.SOUNDSTAGE_WIDTH, -1)
        assertEquals(0, width.soundstageWidth)
        assertEquals(initial.bassImpact, width.bassImpact)
    }
}
