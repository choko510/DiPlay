package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPcmCrossfadeTest {
    @Test
    fun gainTransitionRampsWithoutAFullScaleStep() {
        val crossfade = DspPcmCrossfade(channels = 1, durationFrames = 20)
        val from = FloatArray(20) { 0.8f }
        val to = FloatArray(20) { 0.4f }
        val output = FloatArray(from.size)
        crossfade.begin()

        assertTrue(crossfade.blend(from, to, output, output.size))

        for (frame in 1 until output.size) assertTrue(output[frame] <= output[frame - 1])
        assertEquals(from.first(), output.first(), 0f)
        assertEquals(to.last(), output.last(), 0f)
        assertFalse(crossfade.isActive)
    }

    @Test
    fun stereoTransitionUsesTheSameMixForBothChannelsAndRejectsBadRanges() {
        val crossfade = DspPcmCrossfade(channels = 2, durationFrames = 4)
        val from = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f)
        val to = floatArrayOf(0.25f, -0.25f, 0.25f, -0.25f, 0.25f, -0.25f, 0.25f, -0.25f)
        val output = FloatArray(from.size)
        crossfade.begin()

        assertFalse(crossfade.blend(from, to, FloatArray(2), 4))
        crossfade.begin()
        assertTrue(crossfade.blend(from, to, output, 4))
        for (frame in 0 until 4) assertEquals(output[frame * 2], -output[frame * 2 + 1], 0f)
        assertEquals(0.25f, output[6], 0f)
    }
}
