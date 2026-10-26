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
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, target, true, true, 0, 0, true, mediaRole = false),
        )
    }

    @Test
    fun peqChangesUseLocalWarmEvenWhenAnUnrelatedStatefulStageIsActive() {
        val current = DspRuntimeConfig(enabled = true)
        val target = DspRuntimeConfig(
            enabled = true,
            peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0)),
            convolver = DspConvolverConfig(enabled = true, impulseResponseId = "fixture-ir"),
        )

        assertEquals(setOf(DspRuntimeField.PEQ, DspRuntimeField.CONVOLVER), DspUpdatePlanner.diff(current, target))
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, target, true, true, 0, 0, false),
        )

        val peqOnly = DspRuntimeConfig(
            enabled = true,
            peqBands = target.peqBands,
            convolver = target.convolver,
        )
        val currentWithConvolver = DspRuntimeConfig(enabled = true, convolver = target.convolver)
        assertEquals(setOf(DspRuntimeField.PEQ), DspUpdatePlanner.diff(currentWithConvolver, peqOnly))
        assertEquals(
            DspUpdatePlan.LOCAL_WARM,
            DspUpdatePlanner.classify(currentWithConvolver, peqOnly, true, true, 0, 0, true),
        )
        val bassOnly = DspRuntimeConfig(enabled = true, bass = DspBassConfig(enabled = true, gainDb = 2.0))
        val bassOnlyWithConvolver = DspRuntimeConfig(
            enabled = true,
            bass = bassOnly.bass,
            convolver = target.convolver,
        )
        assertEquals(
            DspUpdatePlan.LOCAL_WARM,
            DspUpdatePlanner.classify(currentWithConvolver, bassOnlyWithConvolver, true, true, 0, 0, true),
        )
        assertEquals(emptyList<DspEqBand>(), currentWithConvolver.peqBands)
    }

    @Test
    fun dangerousLatencyAndIrUpdatesDeferWithReasons() {
        val current = DspRuntimeConfig(enabled = true)
        val latencyChanged = DspRuntimeConfig(enabled = true, harmonic = DspHarmonicConfig(enabled = true))
        val irChanged = DspRuntimeConfig(
            enabled = true,
            convolver = DspConvolverConfig(enabled = true, impulseResponseId = "fixture-ir"),
        )
        val statefulTopologyChanged = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(enabled = true, crossoverHz = 400.0),
        )

        assertEquals(
            "algorithmic latency would change",
            DspUpdatePlanner.decide(current, latencyChanged, true, true, 0, 64, true).reason,
        )
        assertEquals(
            "impulse response topology requires a new track",
            DspUpdatePlanner.decide(current, irChanged, true, true, 0, 0, false).reason,
        )
        assertEquals(
            "changed stateful stage cannot preserve its required history",
            DspUpdatePlanner.decide(current, statefulTopologyChanged, true, true, 0, 0, true).reason,
        )
    }

    @Test
    fun unchangedFieldsAreNotPlannedAndMasterOffIsNeverHot() {
        val current = DspRuntimeConfig(enabled = true, convolver = DspConvolverConfig(enabled = true, impulseResponseId = "fixture-ir"))
        val off = DspRuntimeConfig(enabled = false, convolver = current.convolver)

        assertEquals(emptySet<DspRuntimeField>(), DspUpdatePlanner.diff(current, current))
        assertEquals(DspUpdatePlan.HOT, DspUpdatePlanner.classify(current, current, true, true, 0, 0, true))
        assertEquals(
            DspUpdatePlan.WARM,
            DspUpdatePlanner.classify(current, off, true, true, 0, 0, false),
        )
        assertEquals(
            DspUpdatePlan.WARM,
            DspUpdatePlanner.classify(current, off, true, false, 0, 0, false),
        )
    }

    @Test
    fun masterSwitchAndOtherNonHotFieldsAreNeverClassifiedAsHot() {
        val current = DspRuntimeConfig(enabled = true)
        val disabled = DspRuntimeConfig(enabled = false)
        val profileEdit = DspRuntimeConfig(enabled = true, stereoWidth = 1.25)
        val preampEdit = DspRuntimeConfig(enabled = true, gainDb = 2.0)

        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, disabled, true, false, 0, 0, false))
        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, profileEdit, true, false, 0, 0, false))
        assertEquals(DspUpdatePlan.HOT, DspUpdatePlanner.classify(current, preampEdit, true, true, 0, 0, false))
    }

}
