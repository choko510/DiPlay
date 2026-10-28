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
    fun nativeDynamicsAndDynamicEqHotPayloadsAreAtomicFencedAndAcknowledgedAfterTransition() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(48_000, 2)
        val initial = DspRuntimeConfig(
            enabled = true,
            gainDb = -8.0,
            compressor = DspCompressorConfig(enabled = true),
            dynamicEq = DspDynamicEqConfig(enabled = true, bands = DspDynamicEqConfig.defaultBands()
                .mapIndexed { index, band -> if (index == 0) band.copy(enabled = true) else band }),
        )
        val prepared = initial.prepare(format)
        val handle = NativeDspJni.createWithStudio(
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
            convolverConfig = prepared.convolverConfig,
            convolverSamples = prepared.convolverSamples,
            harmonicValues = prepared.harmonic,
            macroValues = prepared.macroTone,
            smartLoudnessValues = prepared.smartLoudness,
            dynamicBassValues = prepared.dynamicBass,
        )
        assertTrue(handle > 0L)
        try {
            assertTrue(NativeDspJni.bindHotUpdateContext(handle, format, 7L, 9L, 3L, 1, mediaRole = true))
            fun update(config: DspRuntimeConfig, generation: Long, mask: Int =
                DspPreparedHotUpdate.HOT_FIELD_DYNAMICS or DspPreparedHotUpdate.HOT_FIELD_DYNAMIC_EQ) =
                config.prepareHotUpdate(format, generation, 7L, 9L, 3L, 1, true, mask)
            fun processBlock(frames: Int = 512) {
                val input = ByteBuffer.allocateDirect(frames * 2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                repeat(frames) { input.putFloat(0.25f).putFloat(0.25f) }
                input.flip()
                val output = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.nativeOrder())
                assertEquals(0, NativeDspJni.process(handle, input, 0, input.remaining(), output, 0,
                    output.capacity(), frames, 2))
            }

            val safeTarget = DspRuntimeConfig(
                enabled = true,
                gainDb = -8.0,
                compressor = initial.compressor.copy(thresholdDb = -28.0, ratio = 5.0,
                    attackMs = 14.0, releaseMs = 140.0, kneeDb = 4.0, makeupDb = 2.0),
                dynamicEq = DspDynamicEqConfig(enabled = true, bands = initial.dynamicEq.bands.mapIndexed { index, band ->
                    if (index == 0) band.copy(thresholdDb = -30.0, ratio = 3.0, attackMs = 12.0,
                        releaseMs = 130.0, maxCutDb = 4.0) else band
                }),
            )
            val safeUpdate = update(safeTarget, 100L)
            assertEquals(10, safeUpdate.dynamicsValues.size)
            assertEquals(51, safeUpdate.dynamicEqValues.size)
            assertEquals(5, safeUpdate.dynamicsCoefficients.size)
            assertEquals(10, safeUpdate.dynamicEqCoefficients.size)
            assertEquals(5, NativeDspJni.applyHotConfig(handle, safeUpdate.copy(rendererOwnerToken = 10L)))
            assertEquals(5, NativeDspJni.applyHotConfig(handle, safeUpdate.copy(pcmFormatEpoch = 4L)))
            assertEquals(2, NativeDspJni.applyHotConfig(handle, safeUpdate.copy(hotPayloadVersion = 2)))
            assertEquals(2, NativeDspJni.applyHotConfig(handle,
                safeUpdate.copy(dynamicEqValues = safeUpdate.dynamicEqValues.copyOf(50))))
            val nonFinite = safeUpdate.dynamicEqValues.copyOf().also { it[5] = Double.NaN }
            assertEquals(2, NativeDspJni.applyHotConfig(handle, safeUpdate.copy(dynamicEqValues = nonFinite)))
            assertEquals(0, NativeDspJni.applyHotConfig(handle, safeUpdate))
            assertEquals(0, NativeDspJni.hotAckToken(handle))
            repeat(12) { if (NativeDspJni.hotAckToken(handle) != safeUpdate.generationToken) processBlock() }
            assertEquals(safeUpdate.generationToken, NativeDspJni.hotAckToken(handle))

            fun targetWithThreshold(thresholdDb: Double) = DspRuntimeConfig(
                enabled = true,
                gainDb = -8.0,
                compressor = safeTarget.compressor.copy(thresholdDb = thresholdDb),
                dynamicEq = safeTarget.dynamicEq,
            )
            val next = update(targetWithThreshold(-32.0), 101L)
            val latest = update(targetWithThreshold(-34.0), 102L)
            assertEquals(0, NativeDspJni.applyHotConfig(handle, next))
            assertEquals(0, NativeDspJni.applyHotConfig(handle, latest))
            repeat(12) { if (NativeDspJni.hotAckToken(handle) != latest.generationToken) processBlock() }
            assertEquals(latest.generationToken, NativeDspJni.hotAckToken(handle))
            assertTrue(NativeDspJni.hotAckToken(handle) != next.generationToken)

            val capTarget = DspRuntimeConfig(
                enabled = true,
                gainDb = -8.0,
                compressor = safeTarget.compressor,
                dynamicEq = DspDynamicEqConfig(enabled = true, bands = safeTarget.dynamicEq.bands.mapIndexed { index, band ->
                    if (index == 0) band.copy(maxCutDb = 8.0) else band
                }),
            )
            val capUpdate = update(capTarget, 103L, DspPreparedHotUpdate.HOT_FIELD_DYNAMIC_EQ)
            assertEquals(0, NativeDspJni.applyHotConfig(handle, capUpdate))
            processBlock()
            assertEquals(capUpdate.generationToken, NativeDspJni.hotDeferredToken(handle))
            assertEquals(5, NativeDspJni.hotDeferredReason(handle))
            assertTrue(NativeDspJni.hotAckToken(handle) != capUpdate.generationToken)
        } finally {
            NativeDspJni.destroy(handle)
        }
    }

    @Test
    fun nativePreampHotUpdateRampsPcmAndAcknowledgesOnlyTheCompletedTarget() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(48_000, 2)
        val processor = NativeDspProcessor.createOrNull(
            format,
            DspRuntimeConfig(enabled = true, gainDb = -8.0),
        )
        assertNotNull(processor)
        processor ?: return
        try {
            val update = DspPreparedPreampHotUpdate(
                generation = 42L,
                editorOwnerEpoch = 7L,
                rendererOwnerToken = 9L,
                pcmFormatEpoch = 3L,
                hotContextToken = 1,
                mediaRole = true,
                appliedPreampDb = -12.0,
                appliedPreampLinear = 10.0.pow(-12.0 / 20.0),
            )
            assertTrue(processor.bindHotUpdateContext(7L, 9L, 3L, 1, mediaRole = true))
            assertTrue(processor.enqueuePreampHotUpdate(update))

            val firstInput = ByteBuffer.allocateDirect(512 * 2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            repeat(512) { firstInput.putFloat(0.5f).putFloat(0.5f) }
            firstInput.flip()
            val firstOutput = ByteBuffer.allocateDirect(firstInput.remaining()).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, processor.process(firstInput, firstOutput, 512).status)
            val start = firstOutput.getFloat(0)
            val middle = firstOutput.getFloat(510 * 2 * Float.SIZE_BYTES)
            assertTrue(middle < start && middle > 0.5f * update.appliedPreampLinear.toFloat())
            assertEquals(middle, firstOutput.getFloat(510 * 2 * Float.SIZE_BYTES + Float.SIZE_BYTES), 1e-6f)
            assertEquals(0, processor.preampHotAcknowledgedToken())

            val secondInput = ByteBuffer.allocateDirect(448 * 2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            repeat(448) { secondInput.putFloat(0.5f).putFloat(0.5f) }
            secondInput.flip()
            val secondOutput = ByteBuffer.allocateDirect(secondInput.remaining()).order(ByteOrder.nativeOrder())
            assertEquals(DspProcessStatus.SUCCESS, processor.process(secondInput, secondOutput, 448).status)
            assertEquals(42, processor.preampHotAcknowledgedToken())
            assertEquals(0.5f * update.appliedPreampLinear.toFloat(), secondOutput.getFloat(447 * 2 * Float.SIZE_BYTES), 1e-4f)
        } finally {
            processor.close()
        }
    }

    @Test
    fun nativePreampHotJniRejectsStaleOwnerAndFormatAndKeepsLastGoodAfterRebind() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(48_000, 2)
        val prepared = DspRuntimeConfig(enabled = true, gainDb = -8.0).prepare(format)
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
            convolverConfig = prepared.convolverConfig,
            convolverSamples = prepared.convolverSamples,
            harmonicValues = prepared.harmonic,
        )
        assertTrue(handle > 0L)
        try {
            assertTrue(NativeDspJni.bindHotUpdateContext(handle, format, 7L, 9L, 3L, 1, mediaRole = true))
            val update = DspPreparedPreampHotUpdate(
                generation = 42L,
                editorOwnerEpoch = 7L,
                rendererOwnerToken = 9L,
                pcmFormatEpoch = 3L,
                hotContextToken = 1,
                mediaRole = true,
                appliedPreampDb = -12.0,
                appliedPreampLinear = 10.0.pow(-12.0 / 20.0),
            )
            assertEquals(5, NativeDspJni.applyPreampHot(handle, update.copy(rendererOwnerToken = 10L)))
            assertEquals(5, NativeDspJni.applyPreampHot(handle, update.copy(pcmFormatEpoch = 4L)))
            val mismatchedGain = update.copy(appliedPreampDb = -120.0, appliedPreampLinear = 0.5)
            assertTrue(!mismatchedGain.gainPairIsConsistent)
            assertEquals(2, NativeDspJni.applyPreampHot(handle, mismatchedGain))
            assertEquals(0, NativeDspJni.applyPreampHot(handle, update))
            assertTrue(NativeDspJni.bindHotUpdateContext(handle, format, 8L, 10L, 4L, 2, mediaRole = true))

            val input = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                .putFloat(0.5f).putFloat(0.5f).apply { flip() }
            val output = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            assertEquals(
                DspProcessStatus.SUCCESS,
                NativeDspJni.process(handle, input, 0, input.remaining(), output, 0, output.capacity(), 1, 2).let {
                    if (it == 0) DspProcessStatus.SUCCESS else DspProcessStatus.ERROR
                },
            )
            assertEquals(0, NativeDspJni.preampHotAckToken(handle))
            val lastGood = 0.5f * 10.0.pow(-8.0 / 20.0).toFloat()
            assertEquals(lastGood, output.getFloat(0), 1e-6f)
            assertEquals(lastGood, output.getFloat(Float.SIZE_BYTES), 1e-6f)
        } finally {
            NativeDspJni.destroy(handle)
        }
    }

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
            convolverConfig = prepared.convolverConfig,
            convolverSamples = prepared.convolverSamples,
            harmonicValues = prepared.harmonic,
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
    fun nativeCreateRejectsMalformedHarmonicArguments() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)
        val prepared = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false).prepare(format)
        val malformedHarmonic = prepared.harmonic.copyOf().apply { this[4] = Double.NaN }

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
            convolverConfig = prepared.convolverConfig,
            convolverSamples = prepared.convolverSamples,
            harmonicValues = malformedHarmonic,
        )

        assertEquals(0L, handle)
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
    fun nativeBassShelfAndMonoBassFrequencyResponsesReachTheJniEngine() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val bassConfig = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            bass = DspBassConfig(enabled = true, gainDb = 6.0, frequencyHz = 80.0),
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
        )
        assertEquals(
            DspEqDesigner.COEFFICIENTS_PER_BAND,
            bassConfig.prepare(DspAudioFormat(48_000, 1)).eqCoefficients.size,
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
    fun nativeLocalWarmAcceptsFifteenPeqBandsPlusStaticBassShelf() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val bands = (0 until DspEqDesigner.MAX_BANDS).map { index ->
            DspEqBand(DspEqType.PEAK, 200.0 + index * 100.0, gainDb = 0.5)
        }
        val initial = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            peqBands = bands,
        )
        val target = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            peqBands = bands,
            bass = DspBassConfig(enabled = true, gainDb = 3.0, frequencyHz = 90.0),
        )
        val format = DspAudioFormat(48_000, 1)
        val targetCoefficients = target.prepare(format).eqCoefficients
        assertEquals(
            DspEqDesigner.MAX_GRAPH_FILTERS * DspEqDesigner.COEFFICIENTS_PER_BAND,
            targetCoefficients.size,
        )
        val processor = NativeDspProcessor.createOrNull(format, initial)
        assertNotNull(processor)
        processor ?: return

        try {
            assertTrue(processor.bindHotUpdateContext(1L, 1L, 1L, 1, mediaRole = true))
            val historyFrames = 8_192
            val warmInput = ByteBuffer.allocateDirect(historyFrames * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            repeat(historyFrames) { frame ->
                warmInput.putFloat((0.1 * sin(2.0 * PI * 200.0 * frame / format.sampleRate)).toFloat())
            }
            warmInput.flip()
            val warmOutput = ByteBuffer.allocateDirect(historyFrames * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            var processedFrames = 0
            while (processedFrames < historyFrames) {
                val chunkFrames = minOf(DspBufferSizing.PROCESSING_CHUNK_FRAMES, historyFrames - processedFrames)
                val startByte = processedFrames * Float.SIZE_BYTES
                val endByte = startByte + chunkFrames * Float.SIZE_BYTES
                val inputChunk = warmInput.duplicate().apply { position(startByte); limit(endByte) }
                val outputChunk = warmOutput.duplicate().apply { position(startByte); limit(endByte) }
                assertEquals(DspProcessStatus.SUCCESS, processor.process(inputChunk, outputChunk, chunkFrames).status)
                processedFrames += chunkFrames
            }

            val update = processor.prepareLocalPeqWarmUpdate(
                coefficients = targetCoefficients,
                generation = 1L,
                editorOwnerEpoch = 1L,
                rendererOwnerToken = 1L,
                pcmFormatEpoch = 1L,
                hotContextToken = 1,
                mediaRole = true,
            )
            assertNotNull(update)
            update ?: return
            assertEquals(0, processor.enqueueLocalPeqWarmUpdate(update))

            val transitionFrames = 512
            val transitionInput = ByteBuffer.allocateDirect(transitionFrames * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            repeat(transitionFrames) { frame ->
                transitionInput.putFloat((0.1 * sin(2.0 * PI * 200.0 * frame / format.sampleRate)).toFloat())
            }
            transitionInput.flip()
            val transitionOutput = ByteBuffer.allocateDirect(transitionFrames * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            assertEquals(
                DspProcessStatus.SUCCESS,
                processor.process(transitionInput, transitionOutput, transitionFrames).status,
            )
            assertEquals(update.generationToken, processor.localPeqWarmAcknowledgedToken())
        } finally {
            processor.close()
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

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() }
}
