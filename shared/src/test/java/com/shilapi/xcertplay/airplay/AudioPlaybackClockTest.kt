package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlaybackClockTest {
    @Test
    fun mapsPlayedFramesFromTheRtpBaseAtFortyEightKilohertz() {
        val mapper = AudioPlaybackClockMapper(streamType = 100)
        mapper.reset(sourceRate = 48_000, outputRate = 48_000)
        assertNull(mapper.snapshot(0, 10, "playbackHead"))
        mapper.onPcmWritten(sourceSample = 12_345)
        val clock = mapper.snapshot(48_000, 20, "audioTrackTimestamp")!!
        assertEquals(48_000, clock.playedFrames)
        assertEquals(60_345, clock.samplePosition)
        assertEquals("audioTrackTimestamp", clock.source)
    }

    @Test
    fun scalesOutputFramesToTheSourceRateAndWrapsRtpSample() {
        val mapper = AudioPlaybackClockMapper(streamType = 100)
        mapper.reset(sourceRate = 48_000, outputRate = 44_100)
        mapper.onPcmWritten(sourceSample = 0xffff_fff0L)
        val clock = mapper.snapshot(44_100, 30, "playbackHead")!!
        assertEquals(47_984L, clock.samplePosition)
    }

    @Test
    fun playbackHeadWrapExtendsAndPauseDoesNotAdvanceTheClock() {
        val head = Unsigned32FrameTracker()
        assertEquals(0xffff_fff0L, head.update(0xffff_fff0L))
        assertEquals(0x1_0000_0010L, head.update(0x10))
        assertEquals(0x1_0000_0010L, head.update(0x10))
        assertEquals(0x1_0000_0010L, head.update(0x0f))
        assertEquals(0x1_0000_0010L, head.update(0x10))
        assertEquals(0x1_0000_0011L, head.update(0x11))
    }

    @Test
    fun rebufferResumeContinuesFromTheActualPlaybackHead() {
        val mapper = AudioPlaybackClockMapper(streamType = 100)
        mapper.reset(sourceRate = 48_000, outputRate = 48_000)
        mapper.onPcmWritten(sourceSample = 1_000)
        assertEquals(2_000L, mapper.snapshot(1_000, 1, "playbackHead")?.samplePosition)
        assertEquals(2_000L, mapper.snapshot(1_000, 2, "playbackHead")?.samplePosition)
        assertEquals(2_100L, mapper.snapshot(1_100, 3, "playbackHead")?.samplePosition)
    }

    @Test
    fun rtpSampleTimestampWrapRemainsMonotonic() {
        val timestamps = RtpSampleTimestampMapper(sampleRate = 48_000)
        val before = timestamps.presentationTimeUs(0xffff_fff0.toInt())
        val after = timestamps.presentationTimeUs(0x10)
        assertEquals(32L * 1_000_000L / 48_000, after - before)
        assertEquals(16, timestamps.sampleAtPresentationTimeUs(after))
    }

    @Test
    fun frameToNanosecondConversionKeepsLongDurationsInLongArithmetic() {
        assertEquals(1_000_000_000L, framesToNanos(48_000, 48_000))
        val framesAcrossOneUnsignedWrap = 0x1_0000_0010L
        assertEquals(89_478_485_666_666L, framesToNanos(framesAcrossOneUnsignedWrap, 48_000))
    }

    @Test
    fun timestampFramePositionUsesItsOwnUnsignedExtender() {
        val timestampFrames = Unsigned32FrameTracker()
        assertEquals(0xffff_fff0L, timestampFrames.update(0xffff_fff0L))
        assertEquals(0x1_0000_0010L, timestampFrames.update(0x10L))
        assertEquals(0x1_0000_0010L, timestampFrames.update(0x10L))
        assertEquals(0x1_0000_0010L, timestampFrames.update(0x0fL))
    }

    @Test
    fun frameExtendersResetForANewTrackGeneration() {
        val frames = Unsigned32FrameTracker()
        frames.update(0xffff_fff0L)
        assertEquals(0x1_0000_0010L, frames.update(0x10L))
        frames.reset()
        assertEquals(0L, frames.update(0L))
    }

    @Test
    fun recreationResetsTheSampleBase() {
        val mapper = AudioPlaybackClockMapper(streamType = 101)
        mapper.reset(48_000, 48_000)
        mapper.onPcmWritten(900)
        assertEquals(1_000L, mapper.snapshot(100, 1, "playbackHead")?.samplePosition)
        mapper.reset(48_000, 48_000)
        assertNull(mapper.snapshot(100, 2, "playbackHead"))
        mapper.onPcmWritten(4_000)
        assertEquals(4_000L, mapper.snapshot(0, 3, "playbackHead")?.samplePosition)
    }
}
