package com.shilapi.xcertplay.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTuningResolverTest {
    @Test
    fun tMac02NeutralAndEndpointValuesResolveToTheDeclaredGainCurves() {
        val neutral = DspTuningResolver.resolve(
            profile = flatProfile(),
            masterEnabled = true,
            macros = DspSoundMacroSettings(enabled = true),
        )
        val low = DspTuningResolver.resolve(
            profile = flatProfile(),
            masterEnabled = true,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 0, vocalPresence = 0, airDetail = 0, soundstageWidth = 0),
        )
        val high = DspTuningResolver.resolve(
            profile = flatProfile(),
            masterEnabled = true,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 100, vocalPresence = 100, airDetail = 100, soundstageWidth = 100),
        )

        assertEquals(0.0, neutral.breakdown.macroBassDb, 0.0)
        assertEquals(0.0, neutral.breakdown.macroVocalDb, 0.0)
        assertEquals(0.0, neutral.breakdown.macroAirDb, 0.0)
        assertEquals(1.0, neutral.breakdown.relativeWidth, 0.0)
        assertEquals(-2.0, low.breakdown.macroBassDb, 0.0)
        assertEquals(-2.0, low.breakdown.macroVocalDb, 0.0)
        assertEquals(-2.0, low.breakdown.macroAirDb, 0.0)
        assertEquals(0.75, low.breakdown.relativeWidth, 0.0)
        assertEquals(4.0, high.breakdown.macroBassDb, 0.0)
        assertEquals(2.5, high.breakdown.macroVocalDb, 0.0)
        assertEquals(2.0, high.breakdown.macroAirDb, 0.0)
        assertEquals(1.25, high.breakdown.relativeWidth, 0.0)
    }

    @Test
    fun tMac03MacroResolutionDoesNotMutateTheBaseProfile() {
        val profile = flatProfile().copy(
            bass = com.shilapi.xcertplay.media.dsp.DspBassConfig(enabled = true, gainDb = 5.0, frequencyHz = 90.0),
            eqBands = DspProfilePresets.flatBands().mapIndexed { index, band ->
                if (index == 5) band.copy(enabled = true, gainDb = 3.0) else band
            },
        )
        val snapshot = profile.copy(eqBands = profile.eqBands.toList())

        val result = DspTuningResolver.resolve(
            profile = profile,
            masterEnabled = true,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 95, vocalPresence = 82, airDetail = 65, soundstageWidth = 90),
        )

        assertEquals(snapshot, profile)
        assertEquals(profile.eqBands, result.runtimeConfig.peqBands)
        assertEquals(profile.bass, result.runtimeConfig.bass)
    }

    @Test
    fun tLoud02ManualCompensationFallsAsTheDeclaredListeningLevelRises() {
        val quiet = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = DspSmartLoudnessSettings(
                mode = DspSmartLoudnessMode.MANUAL_ESTIMATE,
                strength = 1.0,
                quietListeningLevel = 0.0,
                referenceListeningLevel = 1.0,
                manualListeningLevel = 0.0,
            ),
        )
        val reference = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = DspSmartLoudnessSettings(
                mode = DspSmartLoudnessMode.MANUAL_ESTIMATE,
                strength = 1.0,
                quietListeningLevel = 0.0,
                referenceListeningLevel = 1.0,
                manualListeningLevel = 1.0,
            ),
        )

        assertEquals(3.0, quiet.breakdown.loudnessLowDb, 0.0)
        assertEquals(1.5, quiet.breakdown.loudnessHighDb, 0.0)
        assertEquals(0.0, reference.breakdown.loudnessLowDb, 0.0)
        assertEquals(0.0, reference.breakdown.loudnessHighDb, 0.0)
    }

    @Test
    fun tLoud04AutoRequiresAValidCalibrationAndMatchingTrustedRoute() {
        val settings = DspSmartLoudnessSettings(
            mode = DspSmartLoudnessMode.AUTO_CALIBRATED,
            strength = 1.0,
            calibration = DspLoudnessCalibration("route_a", 0.25, 0.75),
            quietListeningLevel = 0.0,
            referenceListeningLevel = 1.0,
        )
        val unavailable = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = settings,
            volumeReading = DspVolumeReading("route_b", 0.5, trustworthy = true),
        )
        val calibrated = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = settings,
            volumeReading = DspVolumeReading("route_a", 0.5, trustworthy = true),
        )

        assertFalse(unavailable.breakdown.loudnessAvailable)
        assertEquals(0.0, unavailable.breakdown.loudnessLowDb, 0.0)
        assertTrue(calibrated.breakdown.loudnessAvailable)
        assertEquals(0.75, calibrated.breakdown.loudnessLowDb, 1e-12)
        assertEquals(0.375, calibrated.breakdown.loudnessHighDb, 1e-12)
    }

    @Test
    fun tLoud08FixedCompensationDoesNotNeedAnAutomaticVolumeSource() {
        val result = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = DspSmartLoudnessSettings(
                mode = DspSmartLoudnessMode.FIXED,
                strength = 0.5,
                fixedAmount = 0.5,
            ),
        )

        assertTrue(result.breakdown.loudnessAvailable)
        assertEquals(0.75, result.breakdown.loudnessLowDb, 0.0)
        assertEquals(0.375, result.breakdown.loudnessHighDb, 0.0)
    }

    @Test
    fun tSafe01CombinedAddedBassLayersStayWithinTheSixDbBudget() {
        val result = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 100),
            loudness = DspSmartLoudnessSettings(
                mode = DspSmartLoudnessMode.FIXED,
                strength = 1.0,
                fixedAmount = 1.0,
            ),
            dynamicBass = DspDynamicBassSettings(enabled = true, strength = 1.0, maxBoostDb = 3.0),
        )

        assertTrue(result.breakdown.bassBudgetLimited)
        assertEquals(10.0, result.breakdown.requestedAdditionalBassDb, 0.0)
        assertEquals(6.0, result.breakdown.effectiveAdditionalBassDb, 0.0)
        assertEquals(3.0, result.breakdown.dynamicBassMaxDb, 0.0)
        assertEquals(3.0, result.breakdown.macroBassDb, 0.0)
        assertEquals(0.0, result.breakdown.loudnessLowDb, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tMac02RejectsValuesOutsideTheUiRange() {
        DspSoundMacroSettings(bassImpact = 101)
    }

    private fun flatProfile(): DspAudioProfile = DspProfilePresets.find("flat")!!
}
