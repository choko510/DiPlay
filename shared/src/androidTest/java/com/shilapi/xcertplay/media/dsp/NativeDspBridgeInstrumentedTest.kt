package com.shilapi.xcertplay.media.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDspBridgeInstrumentedTest {
    @Test
    fun nativeProcessRejectsMisalignedDirectBufferPositions() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(sampleRate = 48_000, channels = 1)
        val prepared = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
        ).prepare(format)
        val handle = NativeDspJni.create(
            sampleRate = format.sampleRate,
            channels = format.channels,
            maxFrames = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
            gainDb = prepared.appliedPreampDb,
            eqCoefficients = prepared.eqCoefficients,
            dynamics = prepared.dynamics,
            multiband = prepared.multiband,
            dynamicEq = prepared.dynamicEq,
            bassCoefficients = prepared.bassCoefficients,
            monoBassCoefficients = prepared.monoBassCoefficients,
            spatial = prepared.spatial,
            harmonic = prepared.harmonicValues,
            convolverConfig = prepared.convolverConfig,
            convolverSamples = prepared.convolverSamples,
        )
        assertTrue(handle > 0L)
        try {
            val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

            val status = NativeDspJni.process(
                handle = handle,
                input = input,
                inputPosition = 1,
                inputRemaining = Float.SIZE_BYTES,
                output = output,
                outputPosition = 0,
                outputRemaining = Float.SIZE_BYTES,
                frames = 1,
                channels = 1,
            )

            assertEquals(3, status)
        } finally {
            NativeDspJni.destroy(handle)
        }
    }

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
    fun nativeMultibandReconstructsUnityAndCompressesOnlyItsSelectedBand() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val unity = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            multiband = DspMultibandConfig(enabled = true),
        )
        for (frequency in listOf(60.0, 1_000.0, 5_000.0, 12_000.0)) {
            assertEquals(1.0, measureNativeToneGain(unity, 1, frequency), 0.03)
        }

        val lowBandCompression = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            multiband = DspMultibandConfig(
                enabled = true,
                low = DspCompressorConfig(enabled = true, thresholdDb = -30.0, ratio = 4.0, attackMs = 0.1),
            ),
        )
        assertTrue(measureNativeToneGain(lowBandCompression, 1, 60.0) < 0.75)
        assertTrue(measureNativeToneGain(lowBandCompression, 1, 5_000.0) > 0.95)
    }

    @Test
    fun nativeDynamicEqTracksThresholdModeAndBandSelectivity() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val cut = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            dynamicEq = DspDynamicEqConfig(
                enabled = true,
                bands = listOf(
                    DspDynamicEqBandConfig(
                        enabled = true,
                        frequencyHz = 1_000.0,
                        thresholdDb = -30.0,
                        ratio = 4.0,
                        attackMs = 0.1,
                        maxCutDb = 6.0,
                        mode = DspDynamicEqMode.CUT,
                    ),
                ),
            ),
        )
        assertEquals(1.0, measureNativeToneGain(cut, 1, 1_000.0, amplitude = 0.001), 0.02)
        assertTrue(measureNativeToneGain(cut, 1, 1_000.0) < 0.65)
        assertTrue(measureNativeToneGain(cut, 1, 10_000.0) > 0.95)
        assertTrue(measureNativeToneGain(cut, 2, 1_000.0, antiPhase = true) < 0.65)

        val boost = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            dynamicEq = DspDynamicEqConfig(
                enabled = true,
                bands = listOf(
                    DspDynamicEqBandConfig(
                        enabled = true,
                        frequencyHz = 1_000.0,
                        thresholdDb = -35.0,
                        ratio = 4.0,
                        maxBoostDb = 3.0,
                        maxCutDb = 6.0,
                        mode = DspDynamicEqMode.BOOST,
                    ),
                ),
            ),
        )
        assertEquals(10.0.pow(3.0 / 20.0), measureNativeToneGain(boost, 1, 1_000.0, amplitude = 0.001), 0.03)
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
    fun nativeAdvancedMsAppliesBandWidthAndKeepsMonoInputUnchanged() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                crossoverHz = 120.0,
                low = DspAdvancedMsBandConfig(width = 0.0),
                high = DspAdvancedMsBandConfig(width = 1.5),
            ),
        )
        assertTrue(measureNativeToneGain(config, 2, 40.0, antiPhase = true) < 0.03)
        assertEquals(1.5, measureNativeToneGain(config, 2, 4_000.0, antiPhase = true), 0.03)

        val mono = NativeDspProcessor.createOrNull(DspAudioFormat(48_000, 1), config)
        assertNotNull(mono)
        mono ?: return
        try {
            val input = floatBuffer(0.25f, -0.5f)
            val output = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(0, mono.latencyFrames)
            assertEquals(DspProcessStatus.SUCCESS, mono.process(input, output, frames = 2).status)
            output.flip()
            assertEquals(0.25f, output.float, 0f)
            assertEquals(-0.5f, output.float, 0f)
        } finally {
            mono.close()
        }
    }

    @Test
    fun approvedCabinNotchUsesTheExistingNativePeqPath() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val cabinCorrection = DspCabinCorrectionConfig(
            enabled = true,
            measurement = DspCabinMeasurementSummary(
                noiseFloorDb = -60.0,
                pointCount = 120,
                minimumFrequencyHz = 20.0,
                maximumFrequencyHz = 300.0,
                clippedSamples = 0,
            ),
            notches = listOf(DspCabinNotchConfig(frequencyHz = 80.0, gainDb = -9.0, q = 6.0)),
        )
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            cabinCorrection = cabinCorrection,
        )

        assertEquals(10.0.pow(-9.0 / 20.0), measureNativeToneGain(config, 2, 80.0, antiPhase = true), 0.04)
        assertEquals(1.0, measureNativeToneGain(config, 2, 500.0, antiPhase = true), 0.03)
    }

    @Test
    fun harmonicEngineCreatesNativeGraphWithReportedOversamplingLatency() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val processor = NativeDspProcessor.createOrNull(
            DspAudioFormat(48_000, 1),
            DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                harmonic = DspHarmonicConfig(
                    virtualBass = DspVirtualBassConfig(enabled = true, amount = 0.15, crossoverHz = 80.0),
                    mode = DspHarmonicMode.EVEN,
                    quality = DspHarmonicQuality.HIGH,
                    levelMatchEnabled = false,
                ),
            ),
        )
        assertNotNull(processor)
        processor ?: return
        try {
            assertEquals(DspHarmonicQuality.HIGH.latencyFrames, processor.latencyFrames)
            val input = floatBuffer(0.2f, -0.2f)
            val output = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, frames = 2).status)
            assertEquals(2 * Float.SIZE_BYTES, output.position())
            output.flip()
            assertTrue(output.float.isFinite())
            assertTrue(output.float.isFinite())
        } finally {
            processor.close()
        }
    }

    @Test
    fun nativeCreateRejectsMalformedHarmonicArguments() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(48_000, 2)
        val prepared = DspRuntimeConfig(enabled = true).prepare(format)
        val valid = prepared.harmonicValues
        val malformed = listOf(
            valid.copyOf(valid.size - 1),
            valid.copyOf().apply { this[0] = 2.0 },
            valid.copyOf().apply { this[0] = 1.0; this[1] = Double.NaN },
            valid.copyOf().apply { this[0] = 1.0; this[1] = 0.31 },
            valid.copyOf().apply { this[0] = 1.0; this[2] = 22_000.0 },
            valid.copyOf().apply { this[3] = 1.0; this[5] = 161.0 },
            valid.copyOf().apply { this[6] = 1.5 },
            valid.copyOf().apply { this[7] = 3.0 },
            valid.copyOf().apply { this[8] = 2.0 },
        )

        malformed.forEachIndexed { index, values ->
            assertEquals("malformed harmonic argument case $index", 0L, createNativeHandle(format, prepared, values))
        }
    }

    @Test
    fun fourStep4FeaturesComposeAcrossRatesChannelsAndBlockBoundaries() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val wholeCall = intArrayOf(1_024)
        val splitCalls = intArrayOf(1, 127, 256, 512, 128)

        for (sampleRate in intArrayOf(44_100, 48_000)) {
            for (channels in 1..2) {
                val format = DspAudioFormat(sampleRate, channels)
                val input = integrationPcm16(format, frames = wholeCall.sum())
                for (featureMask in 0 until 16) {
                    val config = integrationConfig(featureMask)
                    val expected = processIntegrationPcm16(format, config, input, wholeCall)
                    val actual = processIntegrationPcm16(format, config, input, splitCalls)
                    assertArrayEquals(
                        "44.1/48 kHz, channels=$channels, featureMask=$featureMask",
                        expected,
                        actual,
                    )
                }
            }
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

    @Test
    fun nativePartitionedConvolverIsNToNAndReportsLatencyFromAnImpulse() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val partitionFrames = 128
        for (ir in listOf(floatArrayOf(1.0f), floatArrayOf(0.0f, 1.0f))) {
            val id = if (ir.size == 1) "unit" else "delayed"
            val config = DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                convolver = DspConvolverConfig(
                    enabled = true,
                    impulseResponseId = id,
                    wet = 1.0,
                    impulseResponse = DspImpulseResponse(id, 48_000, 1, ir),
                ),
            )
            val processor = NativeDspProcessor.createOrNull(DspAudioFormat(48_000, 1), config)
            assertNotNull(processor)
            processor ?: return
            try {
                assertEquals(partitionFrames, processor.latencyFrames)
                val input = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                input.putFloat(1.0f)
                repeat(511) { input.putFloat(0.0f) }
                input.flip()
                val output = ByteBuffer.allocateDirect(512 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, frames = 512).status)
                output.flip()
                repeat(partitionFrames) { assertEquals(0.0f, output.float, 0f) }
                assertEquals(ir[0], output.float, 0.001f)
                if (ir.size > 1) assertEquals(ir[1], output.float, 0.001f)
            } finally {
                processor.close()
            }
        }
    }

    @Test
    fun sampleRateMismatchedIrBypassesOnlyConvolution() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val id = "rate_mismatch"
        val config = DspRuntimeConfig(
            enabled = true,
            gainDb = 6.020599913279624,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            convolver = DspConvolverConfig(
                enabled = true,
                impulseResponseId = id,
                impulseResponse = DspImpulseResponse(id, 44_100, 1, floatArrayOf(1.0f)),
            ),
        )
        val processor = NativeDspProcessor.createOrNull(DspAudioFormat(48_000, 1), config)
        assertNotNull(processor)
        processor ?: return

        try {
            assertEquals(0, processor.latencyFrames)
            val input = ByteBuffer.allocateDirect(Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).putFloat(0.25f)
            input.flip()
            val output = ByteBuffer.allocateDirect(Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, frames = 1).status)
            assertEquals(0.5f, output.getFloat(0), 1e-6f)
        } finally {
            processor.close()
        }
    }

    private fun measureNativeToneGain(
        config: DspRuntimeConfig,
        channels: Int,
        frequencyHz: Double,
        antiPhase: Boolean = false,
        amplitude: Double = 0.1,
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
                    val sample = (amplitude * sin(2.0 * PI * frequencyHz * (frame + index) / sampleRate)).toFloat()
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

    private fun createNativeHandle(
        format: DspAudioFormat,
        prepared: DspPreparedConfig,
        harmonicValues: DoubleArray,
    ): Long = NativeDspJni.create(
        sampleRate = format.sampleRate,
        channels = format.channels,
        maxFrames = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
        gainDb = prepared.appliedPreampDb,
        eqCoefficients = prepared.eqCoefficients,
        dynamics = prepared.dynamics,
        multiband = prepared.multiband,
        dynamicEq = prepared.dynamicEq,
        bassCoefficients = prepared.bassCoefficients,
        monoBassCoefficients = prepared.monoBassCoefficients,
        spatial = prepared.spatial,
        harmonic = harmonicValues,
        convolverConfig = prepared.convolverConfig,
        convolverSamples = prepared.convolverSamples,
    )

    private fun integrationConfig(featureMask: Int): DspRuntimeConfig = DspRuntimeConfig(
        enabled = true,
        autoHeadroomEnabled = true,
        seatFocus = if (featureMask and SEAT_FOCUS_MASK != 0) {
            DspSeatFocusConfig(
                enabled = true,
                preset = DspSeatFocusPreset.DRIVER,
                leftDistanceCm = 150.0,
                rightDistanceCm = 100.0,
                leftTrimDb = 1.0,
                rightTrimDb = -1.0,
            )
        } else {
            DspSeatFocusConfig()
        },
        advancedMs = if (featureMask and ADVANCED_MS_MASK != 0) {
            DspAdvancedMsConfig(
                enabled = true,
                crossoverHz = 120.0,
                low = DspAdvancedMsBandConfig(midGainDb = 1.0, sideGainDb = -1.0, width = 0.8),
                high = DspAdvancedMsBandConfig(midGainDb = -1.0, sideGainDb = 1.5, width = 1.4),
                centerFocusDb = 0.5,
                sideCompressor = DspCompressorConfig(enabled = true, thresholdDb = -24.0, ratio = 2.0),
            )
        } else {
            DspAdvancedMsConfig()
        },
        cabinCorrection = if (featureMask and CABIN_MASK != 0) {
            DspCabinCorrectionConfig(
                enabled = true,
                measurement = DspCabinMeasurementSummary(
                    noiseFloorDb = -60.0,
                    pointCount = 120,
                    minimumFrequencyHz = 20.0,
                    maximumFrequencyHz = 300.0,
                    clippedSamples = 0,
                ),
                notches = listOf(
                    DspCabinNotchConfig(frequencyHz = 43.0, gainDb = -6.0, q = 2.5),
                    DspCabinNotchConfig(frequencyHz = 83.0, gainDb = -8.0, q = 4.0),
                    DspCabinNotchConfig(frequencyHz = 160.0, gainDb = -4.0, q = 3.0),
                ),
            )
        } else {
            DspCabinCorrectionConfig()
        },
        harmonic = if (featureMask and HARMONIC_MASK != 0) {
            DspHarmonicConfig(
                highExciter = DspHighExciterConfig(enabled = true, amount = 0.15, crossoverHz = 6_000.0),
                virtualBass = DspVirtualBassConfig(enabled = true, amount = 0.15, crossoverHz = 80.0),
                mode = DspHarmonicMode.MIXED,
                quality = DspHarmonicQuality.BALANCED,
            )
        } else {
            DspHarmonicConfig()
        },
    )

    private fun integrationPcm16(format: DspAudioFormat, frames: Int): ByteArray {
        val output = ByteArray(frames * format.channels * Short.SIZE_BYTES)
        var offset = 0
        repeat(frames) { frame ->
            val time = frame.toDouble() / format.sampleRate
            val left = (
                0.52 * sin(2.0 * PI * 43.0 * time) +
                    0.2 * sin(2.0 * PI * 440.0 * time) +
                    0.1 * sin(2.0 * PI * 6_000.0 * time)
                ).coerceIn(-0.9, 0.9)
            val right = (
                0.48 * sin(2.0 * PI * 83.0 * time) -
                    0.18 * sin(2.0 * PI * 700.0 * time) +
                    0.08 * sin(2.0 * PI * 7_000.0 * time)
                ).coerceIn(-0.9, 0.9)
            repeat(format.channels) { channel ->
                val value = ((if (channel == 0) left else right) * Short.MAX_VALUE).roundToInt()
                output[offset++] = value.toByte()
                output[offset++] = (value shr 8).toByte()
            }
        }
        return output
    }

    private fun processIntegrationPcm16(
        format: DspAudioFormat,
        config: DspRuntimeConfig,
        input: ByteArray,
        callBlockFrames: IntArray,
    ): ByteArray {
        val processor = NativeDspProcessor.createOrNull(format, config)
        assertNotNull("native interaction graph $format", processor)
        processor ?: return ByteArray(0)
        val pipeline = DspPcmPipeline(format, processor, seed = INTEGRATION_DITHER_SEED)
        val expectedLatency = config.seatFocus.prepare(format).maximumLatencyFrames +
            config.harmonic.effectiveFor(format).algorithmicLatencyFrames
        try {
            assertEquals(expectedLatency, pipeline.algorithmicLatencyFrames)
            val output = ByteArray(input.size)
            val frameBytes = format.channels * Short.SIZE_BYTES
            val frameCount = input.size / frameBytes
            var sourceOffset = 0
            var outputOffset = 0
            var callIndex = 0
            while (sourceOffset < input.size) {
                val requestedFrames = callBlockFrames[callIndex % callBlockFrames.size]
                val frames = minOf(requestedFrames, (input.size - sourceOffset) / frameBytes)
                val byteCount = frames * frameBytes
                val outputLength = pipeline.process(input, sourceOffset, byteCount, DspPcmEncoding.PCM16)
                assertEquals("PCM16 block frames=$frames", byteCount, outputLength)
                pipeline.output.copyInto(output, outputOffset, 0, outputLength)
                sourceOffset += byteCount
                outputOffset += outputLength
                callIndex++
            }
            assertEquals(frameCount * frameBytes, outputOffset)
            val diagnostics = pipeline.diagnostics()
            assertEquals(0L, diagnostics.nonFiniteInputSamples)
            assertEquals(0L, diagnostics.nonFiniteOutputSamples)
            assertTrue(diagnostics.outputPeakL <= 1.0)
            if (format.channels == 2) assertTrue(diagnostics.outputPeakR <= 1.0)
            return output
        } finally {
            pipeline.close()
        }
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() }

    private companion object {
        const val SEAT_FOCUS_MASK = 1
        const val ADVANCED_MS_MASK = 2
        const val CABIN_MASK = 4
        const val HARMONIC_MASK = 8
        const val INTEGRATION_DITHER_SEED = 0x5d15L
    }
}
