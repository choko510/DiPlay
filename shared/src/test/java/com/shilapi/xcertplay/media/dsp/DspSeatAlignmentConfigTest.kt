package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSeatAlignmentConfigTest {
    @Test
    fun distanceDifferenceConvertsToFiveMillisecondMaximumWithoutInferringTheSide() {
        val nearLeft = DspSeatAlignmentConfig(
            enabled = true,
            preset = DspSeatPreset.DRIVER,
            nearSpeaker = DspSeatNearSpeaker.LEFT,
            distanceDifferenceCm = DspSeatAlignmentConfig.MAX_DISTANCE_DIFFERENCE_CM,
        )
        val nearRight = nearLeft.copy(preset = DspSeatPreset.PASSENGER, nearSpeaker = DspSeatNearSpeaker.RIGHT)

        assertEquals(240.0, nearLeft.relativeDelayFrames(48_000), 0.0001)
        assertEquals(220.5, nearRight.relativeDelayFrames(44_100), 0.0001)
        assertEquals(960.0, nearLeft.relativeDelayFrames(192_000), 0.0001)
        assertEquals(DspSeatNearSpeaker.LEFT, nearLeft.nearSpeaker)
        assertEquals(DspSeatNearSpeaker.RIGHT, nearRight.nearSpeaker)
    }

    @Test
    fun centerAndDisabledConfigurationsDoNotAddAlignmentDelay() {
        assertEquals(0.0, DspSeatAlignmentConfig().relativeDelayFrames(48_000), 0.0)
        assertEquals(
            0.0,
            DspSeatAlignmentConfig(
                enabled = true,
                nearSpeaker = DspSeatNearSpeaker.CENTER,
            ).relativeDelayFrames(48_000),
            0.0,
        )
    }

    @Test
    fun driverAndPassengerPresetsNeverAssumeLeftOrRightFromSteeringSide() {
        val base = DspSeatAlignmentConfig(
            preset = DspSeatPreset.CUSTOM,
            nearSpeaker = DspSeatNearSpeaker.RIGHT,
            distanceDifferenceCm = 50.0,
        )

        assertEquals(DspSeatNearSpeaker.RIGHT, base.withPreset(DspSeatPreset.DRIVER).nearSpeaker)
        assertEquals(DspSeatNearSpeaker.RIGHT, base.withPreset(DspSeatPreset.PASSENGER).nearSpeaker)
        assertEquals(DspSeatNearSpeaker.CENTER, base.withPreset(DspSeatPreset.CENTER).nearSpeaker)
        assertEquals(0.0, base.withPreset(DspSeatPreset.CENTER).distanceDifferenceCm, 0.0)
        assertEquals(0.0, base.withNearSpeaker(DspSeatNearSpeaker.CENTER).distanceDifferenceCm, 0.0)
    }

    @Test
    fun prepareEncodesSeatControlsAndIncludesPositiveTrimInAutoHeadroom() {
        val seat = DspSeatAlignmentConfig(
            enabled = true,
            preset = DspSeatPreset.CUSTOM,
            nearSpeaker = DspSeatNearSpeaker.RIGHT,
            distanceDifferenceCm = 34.3,
            leftTrimDb = -2.0,
            rightTrimDb = 6.0,
            invertLeft = true,
        )
        val prepared = DspRuntimeConfig(enabled = true, seatAlignment = seat).prepare(DspAudioFormat(48_000, 2))
        val mono = DspRuntimeConfig(enabled = true, seatAlignment = seat).prepare(DspAudioFormat(48_000, 1))

        assertEquals(DSP_SPATIAL_NATIVE_VALUE_COUNT, prepared.spatial.size)
        assertEquals(1.0, prepared.spatial[3], 0.0)
        assertEquals(DspSeatNearSpeaker.RIGHT.nativeValue.toDouble(), prepared.spatial[4], 0.0)
        assertEquals(34.3, prepared.spatial[5], 0.0)
        assertEquals(-2.0, prepared.spatial[6], 0.0)
        assertEquals(6.0, prepared.spatial[7], 0.0)
        assertEquals(1.0, prepared.spatial[8], 0.0)
        assertEquals(0.0, prepared.spatial[9], 0.0)
        assertEquals(0.0, prepared.spatial[10], 0.0)
        assertEquals(DspAdvancedMsConfig.DEFAULT_CROSSOVER_HZ, prepared.spatial[11], 0.0)
        assertEquals(7.0, prepared.headroom.reductionDb, 0.02)
        assertEquals(0.0, mono.spatial[3], 0.0)
        assertEquals(1.0, mono.headroom.reductionDb, 0.02)
    }

    @Test
    fun invalidDistanceOrTrimIsRejected() {
        assertFalse(runCatching {
            DspSeatAlignmentConfig(distanceDifferenceCm = DspSeatAlignmentConfig.MAX_DISTANCE_DIFFERENCE_CM + 0.01)
        }.isSuccess)
        assertFalse(runCatching { DspSeatAlignmentConfig(leftTrimDb = 6.01) }.isSuccess)
        assertFalse(runCatching { DspSeatAlignmentConfig(rightTrimDb = Double.NaN) }.isSuccess)
        assertTrue(DspSeatAlignmentConfig(leftTrimDb = -6.0, rightTrimDb = 6.0).enabled.not())
    }
}
