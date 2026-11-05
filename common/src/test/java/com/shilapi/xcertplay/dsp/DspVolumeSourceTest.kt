package com.shilapi.xcertplay.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DspVolumeSourceTest {
    @Test
    fun androidSourceFailsClosedForUntrustedOrFailedReadings() {
        val trusted = AndroidStreamVolumeSource { DspVolumeReading("route_a", 0.4, trustworthy = true) }
        val untrusted = AndroidStreamVolumeSource { DspVolumeReading("route_a", 0.4, trustworthy = false) }
        val failed = AndroidStreamVolumeSource { error("volume API unavailable") }

        assertTrue(trusted.read().trustworthy)
        assertFalse(untrusted.read().trustworthy)
        assertFalse(failed.read().trustworthy)
    }

    @Test
    fun androidSourceDoesNotSwallowFatalErrors() {
        val source = AndroidStreamVolumeSource { throw AssertionError("fatal") }

        try {
            source.read()
            throw AssertionError("Expected fatal error to propagate")
        } catch (error: AssertionError) {
            assertEquals("fatal", error.message)
        }
    }

    @Test
    fun calibrationRequiresTrustedMonotonicPointsOnSameRouteAndPreservesExistingValue() {
        val original = DspLoudnessCalibration("route_a", 0.1, 0.8)
        val controller = DspLoudnessCalibrationController(original)

        assertFalse(controller.captureQuietPoint(DspVolumeReading("route_a", 0.2, trustworthy = false)))
        assertTrue(controller.captureQuietPoint(DspVolumeReading("route_a", 0.2, trustworthy = true)))
        assertNull(controller.captureReferencePoint(DspVolumeReading("route_b", 0.9, trustworthy = true)))
        assertEquals(original, controller.calibration)
        assertTrue(controller.captureQuietPoint(DspVolumeReading("route_a", 0.7, trustworthy = true)))
        assertNull(controller.captureReferencePoint(DspVolumeReading("route_a", 0.6, trustworthy = true)))
        assertEquals(original, controller.calibration)

        assertTrue(controller.captureQuietPoint(DspVolumeReading("route_a", 0.2, trustworthy = true)))
        val calibrated = controller.captureReferencePoint(DspVolumeReading("route_a", 0.8, trustworthy = true))
        assertNotNull(calibrated)
        assertEquals(DspLoudnessCalibration("route_a", 0.2, 0.8), controller.calibration)
    }

    @Test
    fun manualAndFixedSourcesNeverClaimAutomaticTrust() {
        val manual = ManualVolumeSource(0.25)
        val fixed = FixedVolumeSource(0.75)

        assertEquals(0.25, manual.read().volumeFraction!!, 0.0)
        assertEquals(0.75, fixed.read().volumeFraction!!, 0.0)
        assertFalse(manual.read().trustworthy)
        assertFalse(fixed.read().trustworthy)
    }

    @Test
    fun trustedCalibrationProducesMonotonicCorrectionAndRouteMismatchIsUnavailable() {
        val settings = DspSmartLoudnessSettings(
            mode = DspSmartLoudnessMode.AUTO_CALIBRATED,
            strength = 1.0,
            calibration = DspLoudnessCalibration("route_a", 0.2, 0.8),
            quietListeningLevel = 0.0,
            referenceListeningLevel = 1.0,
        )
        val levels = listOf(0.2, 0.35, 0.5, 0.65, 0.8)
        val gains = levels.map { volume ->
            DspTuningResolver.resolve(
                flatProfile(),
                masterEnabled = true,
                loudness = settings,
                volumeReading = DspVolumeReading("route_a", volume, trustworthy = true),
            ).breakdown.loudnessLowDb
        }

        assertTrue(gains.zipWithNext().all { (earlier, later) -> earlier >= later })
        val mismatched = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = settings,
            volumeReading = DspVolumeReading("route_b", 0.2, trustworthy = true),
        )
        val untrusted = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = settings,
            volumeReading = DspVolumeReading("route_a", 0.2, trustworthy = false),
        )
        assertFalse(mismatched.breakdown.loudnessAvailable)
        assertFalse(untrusted.breakdown.loudnessAvailable)
        assertEquals(0.0, mismatched.breakdown.loudnessLowDb, 0.0)
        assertEquals(0.0, untrusted.breakdown.loudnessLowDb, 0.0)
    }

    @Test
    fun oemKnobChangeWithoutAndroidStreamIndexChangeLeavesAutoUnavailable() {
        val before = playbackVolumeReading(
            deviceType = 11,
            deviceAddress = "device",
            usesMusicStream = true,
            streamIndex = 4,
            streamMaximum = 16,
            volumeFixed = false,
        )
        val afterOemKnobChange = playbackVolumeReading(
            deviceType = 11,
            deviceAddress = "device",
            usesMusicStream = true,
            streamIndex = 4,
            streamMaximum = 16,
            volumeFixed = false,
        )
        val settings = DspSmartLoudnessSettings(
            mode = DspSmartLoudnessMode.AUTO_CALIBRATED,
            strength = 1.0,
            calibration = DspLoudnessCalibration(before.routeToken!!, 0.2, 0.8),
        )

        val resolution = DspTuningResolver.resolve(
            flatProfile(),
            masterEnabled = true,
            loudness = settings,
            volumeReading = afterOemKnobChange,
        )

        assertEquals(before, afterOemKnobChange)
        assertEquals(0.25, afterOemKnobChange.volumeFraction!!, 0.0)
        assertFalse(afterOemKnobChange.trustworthy)
        assertFalse(resolution.breakdown.loudnessAvailable)
        assertEquals(0.0, resolution.breakdown.loudnessLowDb, 0.0)
        assertFalse(resolution.runtimeConfig.smartLoudness.enabled)
    }

    private fun flatProfile(): DspAudioProfile = DspProfilePresets.find("flat")!!
}
