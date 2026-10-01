package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPcmCrossfadeTest {
    @Test
    fun gainTransitionRampsWithoutAFullScaleStep() {
        val crossfade = DspPcmCrossfade(channels = 1, durationFrames = 20)
        val from = pcm16(FloatArray(20) { 0.8f })
        val to = pcm16(FloatArray(20) { 0.4f })
        val output = ByteArray(from.size)
        crossfade.begin()

        assertTrue(crossfade.blend(from, 0, to, 0, output, 0, output.size))

        val values = samples(output)
        assertTrue(values.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(values.first(), samples(from).first())
        assertEquals(samples(to).last(), values.last())
        assertFalse(crossfade.isActive)
    }

    @Test
    fun stereoTransitionUsesTheSameMixForBothChannelsAndRejectsBadRanges() {
        val crossfade = DspPcmCrossfade(channels = 2, durationFrames = 4)
        val from = pcm16(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f))
        val to = pcm16(floatArrayOf(0.25f, -0.25f, 0.25f, -0.25f, 0.25f, -0.25f, 0.25f, -0.25f))
        val output = ByteArray(from.size)
        crossfade.begin()

        assertFalse(crossfade.blend(from, -1, to, 0, output, 0, output.size))
        crossfade.begin()
        assertTrue(crossfade.blend(from, 0, to, 0, output, 0, output.size))
        val values = samples(output)
        for (frame in 0 until 4) assertEquals(values[frame * 2], -values[frame * 2 + 1])
    }

    private fun pcm16(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { samples.forEach { putShort((it * Short.MAX_VALUE).toInt().toShort()) } }
        .array()

    private fun samples(bytes: ByteArray): List<Int> = ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .run { List(bytes.size / Short.SIZE_BYTES) { short.toInt() } }
}
