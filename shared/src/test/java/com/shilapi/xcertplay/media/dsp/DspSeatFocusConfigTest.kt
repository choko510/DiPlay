package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DspSeatFocusConfigTest {
    @Test
    fun measuredLeftRightDistancesProduceOppositeChannelDelaysWithinFixedBudget() {
        val leftFarther = DspSeatFocusConfig(
            enabled = true,
            preset = DspSeatFocusPreset.DRIVER,
            leftDistanceCm = 150.0,
            rightDistanceCm = 50.0,
        ).prepare(DspAudioFormat(48_000, 2))
        val rightFarther = DspSeatFocusConfig(
            enabled = true,
            preset = DspSeatFocusPreset.PASSENGER,
            leftDistanceCm = 50.0,
            rightDistanceCm = 150.0,
        ).prepare(DspAudioFormat(48_000, 2))

        assertTrue(leftFarther.enabled)
        assertEquals(240, leftFarther.maximumLatencyFrames)
        assertEquals(100.0583090379, leftFarther.leftDelayFrames, 0.0001)
        assertEquals(240.0, leftFarther.rightDelayFrames, 0.0)
        assertEquals(240.0, rightFarther.leftDelayFrames, 0.0)
        assertEquals(100.0583090379, rightFarther.rightDelayFrames, 0.0001)
        assertEquals(leftFarther.maximumLatencyFrames, rightFarther.maximumLatencyFrames)
    }

    @Test
    fun delayDifferenceIsCappedAtFiveMillisecondsAndFractionalSamplesAreKept() {
        val config = DspSeatFocusConfig(
            enabled = true,
            leftDistanceCm = 500.0,
            rightDistanceCm = 1.0,
        ).prepare(DspAudioFormat(44_100, 2))

        assertEquals(221, config.maximumLatencyFrames)
        assertEquals(0.0, config.leftDelayFrames, 0.0)
        assertEquals(221.0, config.rightDelayFrames, 0.0)

        val fractional = DspSeatFocusConfig(
            enabled = true,
            leftDistanceCm = 101.0,
            rightDistanceCm = 100.0,
        ).prepare(DspAudioFormat(48_000, 2))
        assertEquals(238.6005830904, fractional.leftDelayFrames, 0.0001)
        assertEquals(240.0, fractional.rightDelayFrames, 0.0)
    }

    @Test
    fun missingMeasurementsDisabledModeAndMonoFormatHaveNoSeatLatency() {
        val missingMeasurement = DspSeatFocusConfig(enabled = true, leftDistanceCm = 0.0, rightDistanceCm = 100.0)
            .prepare(DspAudioFormat(48_000, 2))
        val disabled = DspSeatFocusConfig(enabled = false, leftDistanceCm = 150.0, rightDistanceCm = 50.0)
            .prepare(DspAudioFormat(48_000, 2))
        val mono = DspSeatFocusConfig(enabled = true, leftDistanceCm = 150.0, rightDistanceCm = 50.0)
            .prepare(DspAudioFormat(48_000, 1))

        listOf(missingMeasurement, disabled, mono).forEach { prepared ->
            assertFalse(prepared.enabled)
            assertEquals(0, prepared.maximumLatencyFrames)
        }
    }

    @Test
    fun measurementSideIsExplicitAndPresetDoesNotAssumeLeftHandDrive() {
        val distances = DspSeatFocusConfig(
            enabled = true,
            preset = DspSeatFocusPreset.DRIVER,
            leftDistanceCm = 100.0,
            rightDistanceCm = 180.0,
        ).prepare(DspAudioFormat(48_000, 2))
        val sameMeasurementsForPassenger = DspSeatFocusConfig(
            enabled = true,
            preset = DspSeatFocusPreset.PASSENGER,
            leftDistanceCm = 100.0,
            rightDistanceCm = 180.0,
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(240.0, distances.leftDelayFrames, 0.0)
        assertTrue(distances.rightDelayFrames < distances.leftDelayFrames)
        assertEquals(distances.leftDelayFrames, sameMeasurementsForPassenger.leftDelayFrames, 0.0)
        assertEquals(distances.rightDelayFrames, sameMeasurementsForPassenger.rightDelayFrames, 0.0)
    }

    @Test
    fun trimAndPolarityPrepareAsIndependentChannelControls() {
        val prepared = DspSeatFocusConfig(
            enabled = true,
            leftDistanceCm = 100.0,
            rightDistanceCm = 100.0,
            leftTrimDb = 6.0,
            rightTrimDb = -6.0,
            leftPolarityInverted = true,
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(10.0.pow(6.0 / 20.0), prepared.leftTrimLinear, 1e-12)
        assertEquals(10.0.pow(-6.0 / 20.0), prepared.rightTrimLinear, 1e-12)
        assertTrue(prepared.leftPolarityInverted)
        assertFalse(prepared.rightPolarityInverted)
    }

    @Test
    fun invalidDistanceOrTrimValuesAreRejected() {
        assertTrue(runCatching { DspSeatFocusConfig(leftDistanceCm = Double.NaN) }.isFailure)
        assertTrue(runCatching { DspSeatFocusConfig(rightDistanceCm = 501.0) }.isFailure)
        assertTrue(runCatching { DspSeatFocusConfig(leftTrimDb = 6.1) }.isFailure)
        assertTrue(runCatching { DspSeatFocusConfig(rightTrimDb = Double.NEGATIVE_INFINITY) }.isFailure)
    }
}
