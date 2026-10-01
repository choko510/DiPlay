package com.shilapi.xcertplay.media.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDspBridgeInstrumentedTest {
    @Test
    fun loadCreateProcessResetDestroyAndRejectInvalidHandle() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        assertTrue(NativeDspJni.reset(0L) != 0)
        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)
        val processor = NativeDspProcessor.createOrNull(
            format,
            DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false),
        )
        assertNotNull(processor)
        processor ?: return

        try {
            val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
                putFloat(0.25f)
                putFloat(-0.5f)
                putFloat(0.75f)
                putFloat(-1.0f)
                flip()
            }
            val output = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

            val result = processor.process(input, output, frames = 2)

            assertEquals(DspProcessStatus.SUCCESS, result.status)
            assertEquals(2, result.outputFrames)
            output.flip()
            assertEquals(0.25f, output.float, 0f)
            assertEquals(-0.5f, output.float, 0f)
            assertEquals(0.75f, output.float, 0f)
            assertEquals(-1.0f, output.float, 0f)
            assertEquals(2L, processor.diagnostics().processedFrames)
            processor.reset()
            assertEquals(0L, processor.diagnostics().processedFrames)
        } finally {
            processor.close()
        }
    }

    @Test
    fun nativePeakFilterAppliesConfiguredGainToOneKilohertzSine() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val sampleRate = 48_000
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 6.0, q = 1.0)),
        )
        val processor = NativeDspProcessor.createOrNull(DspAudioFormat(sampleRate, 1), config)
        assertNotNull(processor)
        processor ?: return

        try {
            var inputSquare = 0.0
            var outputSquare = 0.0
            var frame = 0
            repeat(16) {
                val input = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                val output = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                repeat(512) {
                    input.putFloat(sin(2.0 * PI * 1_000.0 * frame / sampleRate).toFloat())
                    frame++
                }
                input.flip()
                val result = processor.process(input, output, frames = 512)
                assertEquals(DspProcessStatus.SUCCESS, result.status)
                output.flip()
                val blockStart = frame - 512
                repeat(512) { index ->
                    val inputSample = input.getFloat(index * Float.SIZE_BYTES).toDouble()
                    val outputSample = output.getFloat(index * Float.SIZE_BYTES).toDouble()
                    if (blockStart + index >= 1_024) {
                        inputSquare += inputSample * inputSample
                        outputSquare += outputSample * outputSample
                    }
                }
            }
            assertEquals(10.0.pow(6.0 / 20.0), sqrt(outputSquare / inputSquare), 0.01)
        } finally {
            processor.close()
        }
    }
}
