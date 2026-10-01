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
            DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            ),
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
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
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

    @Test
    fun nativeCompressorFollowsSteadyStateFourToOneTransferCurve() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val sampleRate = 48_000
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            compressor = DspCompressorConfig(
                enabled = true,
                thresholdDb = -20.0,
                ratio = 4.0,
                attackMs = 10.0,
                releaseMs = 100.0,
            ),
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
        )
        val processor = NativeDspProcessor.createOrNull(DspAudioFormat(sampleRate, 1), config)
        assertNotNull(processor)
        processor ?: return

        try {
            var finalSample = 0.0f
            repeat(94) {
                val input = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                    .apply { repeat(512) { putFloat(1.0f) }; flip() }
                val output = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                val result = processor.process(input, output, frames = 512)
                assertEquals(DspProcessStatus.SUCCESS, result.status)
                finalSample = output.getFloat(511 * Float.SIZE_BYTES)
            }
            assertEquals(10.0.pow(-15.0 / 20.0).toFloat(), finalSample, 0.002f)
            assertEquals(0, processor.latencyFrames)
        } finally {
            processor.close()
        }
    }

    @Test
    fun nativeLinkedLimiterCapsAntiPhaseStereoSamples() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val processor = NativeDspProcessor.createOrNull(
            DspAudioFormat(48_000, 2),
            DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false),
        )
        assertNotNull(processor)
        processor ?: return

        try {
            val input = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
                putFloat(1.2f)
                putFloat(-1.2f)
                putFloat(-1.3f)
                putFloat(1.3f)
                flip()
            }
            val output = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, frames = 2).status)
            output.flip()
            val leftFirst = output.float
            val rightFirst = output.float
            val leftSecond = output.float
            val rightSecond = output.float
            val threshold = 10.0.pow(-1.0 / 20.0).toFloat()
            assertTrue(kotlin.math.abs(leftFirst) <= threshold + 1e-6f)
            assertTrue(kotlin.math.abs(rightFirst) <= threshold + 1e-6f)
            assertTrue(kotlin.math.abs(leftSecond) <= threshold + 1e-6f)
            assertTrue(kotlin.math.abs(rightSecond) <= threshold + 1e-6f)
            assertEquals(leftFirst, -rightFirst, 1e-6f)
            assertEquals(leftSecond, -rightSecond, 1e-6f)
        } finally {
            processor.close()
        }
    }

    @Test
    fun nativeMidsideWidthAndMonoInputRulesMatchTheConfiguredBehavior() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val widthZero = NativeDspProcessor.createOrNull(
            DspAudioFormat(48_000, 2),
            DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                stereoWidth = 0.0,
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            ),
        )
        assertNotNull(widthZero)
        widthZero ?: return
        try {
            val input = floatBuffer(1.0f, -1.0f, 0.25f, 0.25f)
            val output = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, widthZero.process(input, output, frames = 2).status)
            output.flip()
            assertEquals(0.0f, output.float, 0f)
            assertEquals(0.0f, output.float, 0f)
            assertEquals(0.25f, output.float, 0f)
            assertEquals(0.25f, output.float, 0f)
        } finally {
            widthZero.close()
        }

        val mono = NativeDspProcessor.createOrNull(
            DspAudioFormat(48_000, 1),
            DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                stereoWidth = 2.0,
                monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 120),
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            ),
        )
        assertNotNull(mono)
        mono ?: return
        try {
            val input = floatBuffer(0.25f, -0.5f)
            val output = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, mono.process(input, output, frames = 2).status)
            output.flip()
            assertEquals(0.25f, output.float, 0f)
            assertEquals(-0.5f, output.float, 0f)
        } finally {
            mono.close()
        }
    }

    @Test
    fun nativeBassShelfAndMonoBassFrequencyResponsesReachTheJniEngine() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val bassConfig = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            bass = DspBassConfig(enabled = true, gainDb = 6.0, frequencyHz = 80.0),
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
        )
        assertEquals(10.0.pow(6.0 / 20.0), measureNativeToneGain(bassConfig, 1, 20.0), 0.03)
        assertEquals(1.0, measureNativeToneGain(bassConfig, 1, 20_000.0), 0.03)

        val monoBassConfig = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 120),
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
        )
        for (frequency in listOf(30.0, 60.0, 120.0, 1_000.0)) {
            val ratio = 120.0 / frequency
            val expected = 1.0 / sqrt(1.0 + ratio * ratio * ratio * ratio)
            assertEquals(expected, measureNativeToneGain(monoBassConfig, 2, frequency, antiPhase = true), 0.03)
        }
    }

    private fun measureNativeToneGain(
        config: DspRuntimeConfig,
        channels: Int,
        frequencyHz: Double,
        antiPhase: Boolean = false,
    ): Double {
        val sampleRate = 48_000
        val processor = NativeDspProcessor.createOrNull(DspAudioFormat(sampleRate, channels), config)
        assertNotNull(processor)
        processor ?: return 0.0
        try {
            val framesPerBlock = 512
            val input = ByteBuffer.allocateDirect(framesPerBlock * channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(framesPerBlock * channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            var inputSquare = 0.0
            var outputSquare = 0.0
            val totalFrames = 24_000
            var frame = 0
            while (frame < totalFrames) {
                val frames = minOf(framesPerBlock, totalFrames - frame)
                input.clear()
                output.clear()
                repeat(frames) { index ->
                    val sample = (0.1 * sin(2.0 * PI * frequencyHz * (frame + index) / sampleRate)).toFloat()
                    input.putFloat(sample)
                    if (channels == 2) input.putFloat(if (antiPhase) -sample else sample)
                }
                input.flip()
                assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, frames).status)
                output.flip()
                repeat(frames) { index ->
                    val inputSample = input.getFloat(index * channels * Float.SIZE_BYTES).toDouble()
                    val outputSample = output.getFloat(index * channels * Float.SIZE_BYTES).toDouble()
                    if (frame + index >= 2_048) {
                        inputSquare += inputSample * inputSample
                        outputSquare += outputSample * outputSample
                    }
                }
                frame += frames
            }
            return sqrt(outputSquare / inputSquare)
        } finally {
            processor.close()
        }
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() }
}
