package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspUpdatePlannerTest {
    @Test
    fun dynamicsAndDynamicEqSafeFieldsAreHotButTopologyAndUnsafeLimiterChangesDefer() {
        val compressor = DspCompressorConfig(enabled = true)
        val limiter = DspSafetyLimiterConfig(enabled = true)
        val current = DspRuntimeConfig(enabled = true, compressor = compressor, safetyLimiter = limiter)
        val compressorParameters = DspRuntimeConfig(
            enabled = true,
            compressor = compressor.copy(thresholdDb = -26.0, ratio = 5.0, attackMs = 14.0,
                releaseMs = 140.0, kneeDb = 4.0, makeupDb = 2.0),
            safetyLimiter = limiter.copy(thresholdDb = -2.0, releaseMs = 90.0),
        )
        assertEquals(DspUpdatePlan.HOT,
            DspUpdatePlanner.classify(current, compressorParameters, true, true, 0, 0, true))
        assertTrue(current.canApplyStudioHotUpdateTo(compressorParameters))

        val compressorTopology = DspRuntimeConfig(enabled = true, compressor = compressor.copy(enabled = false), safetyLimiter = limiter)
        assertEquals("COMPRESSOR enabled topology change is unsafe for HOT; keeping the active pipeline",
            DspUpdatePlanner.decide(current, compressorTopology, true, true, 0, 0, true).reason)
        val unsafeLimiter = DspRuntimeConfig(enabled = true, compressor = compressor,
            safetyLimiter = limiter.copy(thresholdDb = -0.5))
        assertEquals(DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, unsafeLimiter, true, true, 0, 0, true))

        val eqCurrent = DspRuntimeConfig(enabled = true, dynamicEq = DspDynamicEqConfig(enabled = true))
        val eqThreshold = DspDynamicEqConfig(enabled = true, bands = eqCurrent.dynamicEq.bands.mapIndexed { index, band ->
            if (index == 0) band.copy(thresholdDb = -30.0, ratio = 3.0, attackMs = 12.0,
                releaseMs = 130.0, maxBoostDb = 4.0, maxCutDb = 5.0) else band
        })
        val eqTarget = DspRuntimeConfig(enabled = true, dynamicEq = eqThreshold)
        assertEquals(DspUpdatePlan.HOT,
            DspUpdatePlanner.classify(eqCurrent, eqTarget, true, true, 0, 0, true))
        val frequencyTarget = DspRuntimeConfig(enabled = true, dynamicEq = DspDynamicEqConfig(
            enabled = true, bands = eqThreshold.bands.mapIndexed { index, band ->
                if (index == 0) band.copy(frequencyHz = 125.0) else band
            }))
        assertEquals(DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(eqCurrent, frequencyTarget, true, true, 0, 0, true))
        assertEquals("DYNAMIC_EQ enabled/mode/frequency/Q topology change is unsafe for HOT; keeping the active pipeline",
            DspUpdatePlanner.decide(eqCurrent, frequencyTarget, true, true, 0, 0, true).reason)

        val mixedSafeDynamicsUnsafeEq = DspRuntimeConfig(enabled = true,
            compressor = compressor.copy(thresholdDb = -30.0), dynamicEq = frequencyTarget.dynamicEq)
        val bothCurrent = DspRuntimeConfig(enabled = true, compressor = compressor,
            dynamicEq = eqCurrent.dynamicEq)
        assertEquals(DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(bothCurrent, mixedSafeDynamicsUnsafeEq, true, true, 0, 0, true))
    }

    @Test
    fun studioOverlayChangesAreHotWhenAPipelineIsActive() {
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
        assertTrue(current.canApplyStudioHotUpdateTo(target))
        assertFalse(current.canApplyStudioHotUpdateTo(current))
    }

    @Test
    fun preampOnlyChangesUseBoundedHotRampAndKeepUnsafeTargetsDeferred() {
        val current = DspRuntimeConfig(enabled = true, gainDb = 0.0)
        val safeTarget = DspRuntimeConfig(enabled = true, gainDb = -8.0)
        val unsafeTarget = DspRuntimeConfig(enabled = true, gainDb = -3.0)

        assertEquals(setOf(DspRuntimeField.PREAMP), DspUpdatePlanner.diff(current, safeTarget))
        assertEquals(
            DspUpdatePlan.HOT,
            DspUpdatePlanner.classify(current, safeTarget, true, true, 0, 0, true),
        )
        assertTrue(current.canApplyPreampHotUpdateTo(safeTarget))
        assertEquals(setOf(DspRuntimeField.PREAMP), DspUpdatePlanner.diff(current, unsafeTarget))
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, unsafeTarget, true, true, 0, 0, true),
        )
        assertFalse(current.canApplyPreampHotUpdateTo(unsafeTarget))
        assertFalse(current.canApplyStudioHotUpdateTo(safeTarget))
        val manualHeadroomTarget = DspRuntimeConfig(enabled = true, gainDb = -12.0, autoHeadroomEnabled = false)
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, manualHeadroomTarget, true, true, 0, 0, true),
        )
    }

    @Test
    fun peqAndBassShelfChangesUseLocalWarmWhenEnabledEvenWithUnrelatedStatefulStages() {
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
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(currentWithConvolver, peqOnly, true, true, 0, 0, true),
        )
        assertEquals(
            DspUpdatePlan.LOCAL_WARM,
            DspUpdatePlanner.classify(
                currentWithConvolver,
                peqOnly,
                true,
                true,
                0,
                0,
                true,
                studioLocalWarmEnabled = true,
            ),
        )
        val compressor = DspCompressorConfig(enabled = true, attackMs = 1_000.0, releaseMs = 1_000.0)
        val currentWithCompressor = DspRuntimeConfig(enabled = true, compressor = compressor)
        val peqWithCompressor = DspRuntimeConfig(enabled = true, compressor = compressor, peqBands = target.peqBands)
        assertEquals(
            DspUpdatePlan.LOCAL_WARM,
            DspUpdatePlanner.classify(
                currentWithCompressor,
                peqWithCompressor,
                true,
                true,
                0,
                0,
                true,
                studioLocalWarmEnabled = true,
            ),
        )
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(
                currentWithCompressor,
                peqWithCompressor,
                true,
                true,
                0,
                0,
                true,
                mediaRole = false,
                studioLocalWarmEnabled = true,
            ),
        )
        val bassOnly = DspRuntimeConfig(enabled = true, bass = DspBassConfig(enabled = true, gainDb = 2.0))
        val bassOnlyWithConvolver = DspRuntimeConfig(
            enabled = true,
            bass = bassOnly.bass,
            convolver = target.convolver,
        )
        assertEquals(
            DspUpdatePlan.LOCAL_WARM,
            DspUpdatePlanner.classify(
                currentWithConvolver,
                bassOnlyWithConvolver,
                true,
                true,
                0,
                0,
                true,
                studioLocalWarmEnabled = true,
            ),
        )
        assertEquals(
            "PEQ and Bass Shelf local warm-up are disabled; keeping the active pipeline until the next AudioTrack generation",
            DspUpdatePlanner.decide(
                currentWithConvolver,
                bassOnlyWithConvolver,
                true,
                true,
                0,
                0,
                true,
            ).reason,
        )
        val cabinOnly = DspRuntimeConfig(
            enabled = true,
            convolver = target.convolver,
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                notches = listOf(DspCabinCorrectionNotch(enabled = true, frequencyHz = 80.0, gainDb = -6.0)),
            ),
        )
        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(
                currentWithConvolver,
                cabinOnly,
                true,
                true,
                0,
                0,
                true,
                studioLocalWarmEnabled = true,
            ),
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
        assertEquals(DspUpdatePlan.WARM, DspUpdatePlanner.classify(current, preampEdit, true, false, 0, 0, false))
        assertEquals(
            DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "preamp HOT requires Auto Headroom and at least 6 dB of reserve",
            ),
            DspUpdatePlanner.decide(current, preampEdit, true, true, 0, 0, false),
        )
    }

    @Test
    fun disablingStudioHotUpdatesDefersCommittedHotChanges() {
        val current = DspRuntimeConfig(enabled = true)
        val target = DspRuntimeConfig(
            enabled = true,
            macroTone = DspMacroToneConfig(enabled = true, vocalGainDb = 1.0),
        )

        assertEquals(
            DspUpdatePlan.DEFERRED,
            DspUpdatePlanner.classify(current, target, true, true, 0, 0, true, studioHotUpdatesEnabled = false),
        )
        assertEquals(
            DspUpdatePlan.WARM,
            DspUpdatePlanner.classify(current, target, true, false, 0, 0, true, studioHotUpdatesEnabled = false),
        )
    }

}
