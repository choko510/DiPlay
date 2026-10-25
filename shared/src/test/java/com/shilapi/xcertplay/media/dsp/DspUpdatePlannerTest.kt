package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class DspUpdatePlannerTest {
    @Test
    fun studioOverlayAndPreampChangesAreHotWhenAPipelineIsActive() {
        val current = DspRuntimeConfig(enabled = true)
        val target = DspRuntimeConfig(
            enabled = true,
            macroTone = DspMacroToneConfig(enabled = true, vocalGainDb = 1.0),
            smartLoudness = DspSmartLoudnessRuntimeConfig(enabled = true, lowGainDb = 1.0),
            dynamicBass = DspDynamicBassRuntimeConfig(enabled = true),
        )

        assertEquals(
            setOf(
                DspRuntimeField.MACRO_TONE,
                DspRuntimeField.SMART_LOUDNESS,
                DspRuntimeField.DYNAMIC_BASS,
            ),
            DspUpdatePlanner.diff(current, target),
        )
        assertEquals(
            DspUpdatePlan.HOT,
            DspUpdatePlanner.classify(current, target, true, true, 0, 0, true),
        )
    }

    @Test
    fun staticEqChangesAreWarmButDeferredWhenLatencyOrStateContinuityWouldChange() {
        val current = DspRuntimeConfig(enabled = true)
        val target = DspRuntimeConfig(
            enabled = true,
            peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0)),
        )

        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, target, true, false, 0, 0, false))
        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, target, true, true, 0, 0, false))
        assertEquals(DspUpdatePlan.DEFERRED, DspUpdatePlanner.classify(current, target, true, true, 0, 1, false))
        assertEquals(DspUpdatePlan.DEFERRED, DspUpdatePlanner.classify(current, target, true, true, 0, 0, true))
    }

    @Test
    fun masterSwitchAndOtherNonHotFieldsAreNeverClassifiedAsHot() {
        val current = DspRuntimeConfig(enabled = true)
        val disabled = DspRuntimeConfig(enabled = false)
        val profileEdit = DspRuntimeConfig(enabled = true, stereoWidth = 1.25)
        val preampEdit = DspRuntimeConfig(enabled = true, gainDb = 2.0)

        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, disabled, true, false, 0, 0, false))
        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, profileEdit, true, false, 0, 0, false))
        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, preampEdit, true, false, 0, 0, false))
    }
}
