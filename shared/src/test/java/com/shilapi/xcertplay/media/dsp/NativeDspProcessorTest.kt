package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDspProcessorTest {
    @Test
    fun loaderFailureAndNativeCreateFailureFallBackWithoutThrowing() {
        val format = DspAudioFormat(48_000, 2)
        assertFalse(NativeDspLibrary.ensureLoaded { throw UnsatisfiedLinkError("missing test library") })
        assertNull(NativeDspProcessor.createOrNull(format, 0.0, loadLibrary = { false }))
        assertNull(
            NativeDspProcessor.createOrNull(
                format,
                0.0,
                loadLibrary = { true },
                bindings = FakeNativeDspBindings(createHandle = 0L),
            ),
        )
    }

    @Test
    fun nativeProcessFailureReturnsAnErrorWithoutConsumingBuffers() {
        val format = DspAudioFormat(48_000, 2)
        val bindings = FakeNativeDspBindings(processStatus = 3)
        val processor = NativeDspProcessor(format, 42L, bindings, latencyFrames = 0)
        val input = floatBuffer(0.25f, -0.5f)
        val output = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())

        val result = processor.process(input, output, frames = 1)

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.NATIVE_FAILURE, result.bypassReason)
        assertEquals(0, input.position())
        assertEquals(0, output.position())
        assertEquals(1, bindings.processCalls)
        assertEquals(1L, processor.diagnostics().nativeErrorCount)
        processor.close()
        assertEquals(42L, bindings.destroyedHandle)
    }

    @Test
    fun successfulNativeBlockHonorsFramesChannelsAndAdvancesDirectBufferPositions() {
        val format = DspAudioFormat(48_000, 2)
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor(format, 7L, bindings, latencyFrames = 0)
        val input = floatBuffer(0.25f, -0.5f, 0.75f, -1.0f)
        val output = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder())

        val result = processor.process(input, output, frames = 2)

        assertEquals(DspProcessStatus.SUCCESS, result.status)
        assertEquals(16, input.position())
        assertEquals(16, output.position())
        output.flip()
        assertEquals(0.25f, output.float, 0f)
        assertEquals(-0.5f, output.float, 0f)
        assertEquals(0.75f, output.float, 0f)
        assertEquals(-1.0f, output.float, 0f)
        assertEquals(2, bindings.lastFrames)
        assertEquals(2, bindings.lastChannels)
        processor.close()
    }

    @Test
    fun invalidNativeBufferIsRejectedBeforeCallingBindings() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor(DspAudioFormat(48_000, 2), 9L, bindings, latencyFrames = 0)

        val result = processor.process(
            input = ByteBuffer.allocate(8),
            output = ByteBuffer.allocateDirect(8),
            frames = 1,
        )

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.INVALID_BUFFER, result.bypassReason)
        assertEquals(0, bindings.processCalls)
        processor.close()
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() }

    private class FakeNativeDspBindings(
        private val createHandle: Long = 42L,
        private val processStatus: Int = 0,
    ) : NativeDspBindings {
        var processCalls = 0
        var lastFrames = 0
        var lastChannels = 0
        var destroyedHandle: Long? = null

        override fun create(sampleRate: Int, channels: Int, maxFrames: Int, gainDb: Double): Long = createHandle

        override fun process(
            handle: Long,
            input: ByteBuffer,
            inputPosition: Int,
            inputRemaining: Int,
            output: ByteBuffer,
            outputPosition: Int,
            outputRemaining: Int,
            frames: Int,
            channels: Int,
        ): Int {
            processCalls++
            lastFrames = frames
            lastChannels = channels
            if (processStatus != 0) return processStatus
            val sampleCount = frames * channels
            for (index in 0 until sampleCount) {
                output.putFloat(outputPosition + index * Float.SIZE_BYTES, input.getFloat(inputPosition + index * Float.SIZE_BYTES))
            }
            return 0
        }

        override fun reset(handle: Long): Int = 0
        override fun latencyFrames(handle: Long): Int = 0
        override fun diagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int = 0
        override fun destroy(handle: Long) {
            destroyedHandle = handle
        }
    }
}
