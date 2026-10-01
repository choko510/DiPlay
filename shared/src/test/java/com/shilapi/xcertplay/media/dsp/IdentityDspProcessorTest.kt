package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityDspProcessorTest {
    @Test
    fun copiesInterleavedFloatBytesWithoutChangingTheirValues() {
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
            putFloat(0.25f)
            putFloat(-0.5f)
            putFloat(0.75f)
            putFloat(-1.0f)
            flip()
        }
        val output = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)
        val result = IdentityDspProcessor(format).process(input, output, frames = 2)

        assertEquals(DspProcessStatus.SUCCESS, result.status)
        assertEquals(2, result.inputFrames)
        assertEquals(2, result.outputFrames)
        assertEquals(0, result.algorithmicLatencyFrames)
        output.flip()
        assertEquals(0.25f, output.float, 0f)
        assertEquals(-0.5f, output.float, 0f)
        assertEquals(0.75f, output.float, 0f)
        assertEquals(-1.0f, output.float, 0f)
    }

    @Test
    fun invalidOutputCapacityDoesNotConsumeTheInput() {
        val input = ByteBuffer.allocateDirect(8).apply {
            putFloat(0.25f)
            putFloat(-0.5f)
            flip()
        }
        val output = ByteBuffer.allocateDirect(4)

        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)
        val result = IdentityDspProcessor(format).process(input, output, frames = 1)

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.INVALID_BUFFER, result.bypassReason)
        assertEquals(0, input.position())
        assertEquals(0, output.position())
    }

    @Test
    fun rejectsInPlaceProcessing() {
        val buffer = ByteBuffer.allocateDirect(4).apply {
            putFloat(0.25f)
            flip()
        }

        val result = IdentityDspProcessor(DspAudioFormat(48_000, 1)).process(buffer, buffer, frames = 1)

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.INVALID_BUFFER, result.bypassReason)
        assertEquals(0, buffer.position())
    }

    @Test
    fun rejectsFrameCountThatDoesNotMatchTheChannelAwareBufferSize() {
        val input = ByteBuffer.allocateDirect(16).apply {
            repeat(4) { putFloat(0.25f) }
            flip()
        }
        val output = ByteBuffer.allocateDirect(16)
        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)

        val result = IdentityDspProcessor(format).process(input, output, frames = 1)

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.INVALID_BUFFER, result.bypassReason)
        assertEquals(0, input.position())
        assertEquals(0, output.position())
    }
}
