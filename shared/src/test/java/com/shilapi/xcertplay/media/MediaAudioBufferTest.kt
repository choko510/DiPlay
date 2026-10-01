package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan("media", 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan("telephony", 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        assertEquals(plan, MediaAudioBuffer.plan("alert", 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan("media", 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
        assertEquals(1_024, MediaAudioBuffer.startBytesFor(2_048, 1_024, 2_048))
    }

    @Test
    fun `plans from effective output rate channels and bytes per sample`() {
        val stereo44k = MediaAudioBuffer.plan("media", 44_100, 2, 4_096, 300)
        assertEquals(52_920, stereo44k.startBytes)

        val mono = MediaAudioBuffer.plan("media", 48_000, 1, 4_096, 300)
        assertEquals(28_800, mono.startBytes)

        val floatOutput = MediaAudioBuffer.plan("media", 48_000, 2, 4_096, 500, bytesPerSample = 4)
        assertEquals(192_000, floatOutput.startBytes)
    }

    @Test
    fun `navigation does not inherit media delay presets`() {
        val lowLatency = MediaAudioBuffer.plan("navigation", 48_000, 2, 4_096, 1000)
        assertEquals(lowLatency, MediaAudioBuffer.plan("navigation", 48_000, 2, 4_096, 300))
        assertEquals(lowLatency, MediaAudioBuffer.plan("navigation", 48_000, 2, 4_096, 500))
    }
}
