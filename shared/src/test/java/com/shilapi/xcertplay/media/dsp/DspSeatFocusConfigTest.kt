package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSeatFocusConfigTest {
    @Test
    fun defaultsAreNeutralAndPresetsDoNotAssumeVehicleGeometry() {
        val config = DspSeatFocusConfig()

        assertFalse(config.enabled)
        assertEquals(DspSeatPreset.CUSTOM, config.preset)
        assertEquals(DspSeatDistanceDelays(0.0, 0.0), config.effectiveDelays())
        assertEquals(config.effectiveDelays(), config.copy(preset = DspSeatPreset.DRIVER).effectiveDelays())
        assertEquals(config.effectiveDelays(), config.copy(preset = DspSeatPreset.PASSENGER).effectiveDelays())
        assertEquals(config.effectiveDelays(), config.copy(preset = DspSeatPreset.CENTER).effectiveDelays())
    }

    @Test
    fun manualDelayAndTrimBoundariesAreAccepted() {
        val config = DspSeatFocusConfig(
            enabled = true,
            leftDelayMs = 0.0,
            rightDelayMs = DspSeatFocusConfig.MAX_DELAY_MS,
            leftTrimDb = -6.0,
            rightTrimDb = 6.0,
        )

        assertEquals(0.0, config.effectiveDelays().leftDelayMs, 0.0)
        assertEquals(5.0, config.effectiveDelays().rightDelayMs, 0.0)
        assertTrue(runCatching { config.copy(leftDelayMs = -0.01) }.isFailure)
        assertTrue(runCatching { config.copy(rightDelayMs = 5.01) }.isFailure)
        assertTrue(runCatching { config.copy(leftTrimDb = -6.01) }.isFailure)
        assertTrue(runCatching { config.copy(rightTrimDb = Double.NaN) }.isFailure)
    }

    @Test
    fun distanceCalculationDelaysTheNearSpeakerWithExplicitCentimeterConversion() {
        val leftNear = DspSeatFocusConfig.calculateDistanceDelays(leftDistanceCm = 80.0, rightDistanceCm = 120.0)
        val rightNear = DspSeatFocusConfig.calculateDistanceDelays(leftDistanceCm = 120.0, rightDistanceCm = 80.0)

        assertEquals(40.0 * 10.0 / 343.0, leftNear.leftDelayMs, 1e-12)
        assertEquals(0.0, leftNear.rightDelayMs, 0.0)
        assertEquals(0.0, rightNear.leftDelayMs, 0.0)
        assertEquals(40.0 * 10.0 / 343.0, rightNear.rightDelayMs, 1e-12)
        assertEquals(0.65, DspSeatFocusConfig(leftDelayMs = 0.65).effectiveDelays().leftDelayMs, 0.0)
    }

    @Test
    fun distanceModeRejectsMissingOutOfRangeAndOverBudgetMeasurements() {
        assertTrue(runCatching { DspSeatFocusConfig(distanceMode = true) }.isFailure)
        assertTrue(
            runCatching {
                DspSeatFocusConfig(distanceMode = true, leftDistanceCm = 20.0, rightDistanceCm = 300.0)
            }.isFailure,
        )
        assertTrue(runCatching { DspSeatFocusConfig.calculateDistanceDelays(19.0, 80.0) }.isFailure)
        assertTrue(runCatching { DspSeatFocusConfig.calculateDistanceDelays(80.0, Double.POSITIVE_INFINITY) }.isFailure)

        val edge = DspSeatFocusConfig(distanceMode = true, leftDistanceCm = 20.0, rightDistanceCm = 191.5)
        assertTrue(edge.effectiveDelays().withinSupportedRange)
        assertEquals(5.0, edge.effectiveDelays().leftDelayMs, 1e-12)
    }
}
