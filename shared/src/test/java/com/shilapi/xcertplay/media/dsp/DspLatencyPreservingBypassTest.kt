package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class DspLatencyPreservingBypassTest {
    @Test
    fun seatRelativeDelayAndCommonLatencyRemainSeparateDuringBypass() {
        val seat = DspSeatAlignmentConfig(
            enabled = true,
            nearSpeaker = DspSeatNearSpeaker.LEFT,
            distanceDifferenceCm = 34300.0 * 1.5 / 48_000.0,
        )
        val bypass = DspLatencyPreservingBypass(
            format = DspAudioFormat(48_000, 2),
            commonLatencyFrames = 2,
            seatAlignment = seat,
            seed = 1L,
        )
        val source = floatPcm(floatArrayOf(1.0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

        val outputLength = bypass.process(source, 0, source.size, DspPcmEncoding.PCM_FLOAT)

        assertEquals(DspBufferSizing.pcm16ByteCount(5, 2), outputLength)
        assertEquals(1.0f, bypass.outputFloats[2 * 2 + 1], 1e-6f)
        assertEquals(1.0f / 3.0f, bypass.outputFloats[3 * 2], 1e-6f)
        assertEquals(8.0f / 9.0f, bypass.outputFloats[4 * 2], 1e-6f)
        assertEquals(0.0f, bypass.outputFloats[4 * 2 + 1], 1e-6f)
    }

    private fun floatPcm(samples: FloatArray): ByteArray {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach(bytes::putFloat)
        return bytes.array()
    }
}
