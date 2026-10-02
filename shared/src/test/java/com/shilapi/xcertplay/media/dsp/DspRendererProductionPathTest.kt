package com.shilapi.xcertplay.media.dsp

import android.media.AudioFormat as AndroidAudioFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.AudioRenderer
import com.shilapi.xcertplay.media.AudioStreamClassifier
import com.shilapi.xcertplay.media.AudioTrackWriteAdapterForTest
import com.shilapi.xcertplay.media.NavigationAudioRoute
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspRendererProductionPathTest {
    @Test
    fun dspOffLpcmProductionPathWritesTheOriginalPcm16Samples() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig.disabled(),
            writeAdapter = { bytes, offset, length, _ ->
                writes.add(bytes.copyOfRange(offset, offset + length))
                length
            },
            pipelinePreparer = { _, _ -> error("DSP-OFF must not prepare a pipeline") },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(intArrayOf(0)), sample = 100)
            val samples = IntArray(64) { frame -> (frame * 701 % 30_000) - 15_000 }
            renderer.handleRtpForTesting(lpcmRtp(samples), sample = 200)

            assertNull(renderer.dspPipeline)
            assertEquals(2, writes.size)
            val actual = writes[1]
            samples.forEachIndexed { index, sample ->
                assertEquals(sample.toByte(), actual[index * 2])
                assertEquals((sample shr 8).toByte(), actual[index * 2 + 1])
            }
            assertEquals(100L, renderer.playbackClockForTesting()?.baseRtpSample)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun aacAndOpusInitialConvolverGraphsAreInstalledBeforeFirstDecodedPcm() {
        for (codec in listOf(AudioCodecKind.AAC_LC, AudioCodecKind.OPUS)) {
            val config = DspRuntimeConfig(
                enabled = true,
                convolver = DspConvolverConfig(
                    enabled = true,
                    impulseResponseId = "room_$codec",
                    impulseResponse = DspImpulseResponse("room_$codec", 48_000, 1, floatArrayOf(1.0f)),
                ),
            )
            val format = audioFormat(codec, channels = 2)
            val written = CopyOnWriteArrayList<ByteArray>()
            val renderer = renderer(
                format = format,
                initialConfig = config,
                writeAdapter = { bytes, offset, length, _ ->
                    written.add(bytes.copyOfRange(offset, offset + length))
                    length
                },
                pipelinePreparer = { audioFormat, runtimeConfig ->
                    pipeline(audioFormat, latencyFrames = if (runtimeConfig.convolver.enabled) 128 else 0, gain = 2.0f)
                },
            )
            try {
                renderer.prepareDecoderPcmFormatForTesting(48_000, 2, AndroidAudioFormat.ENCODING_PCM_16BIT)
                assertNotNull(renderer.dspPipeline)
                assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
                assertNull(renderer.deferredDspGenerationForTesting)

                renderer.beginTrackGenerationForTesting()
                val source = pcm16(frames = 4096, channels = 2, sample = 800)
                renderer.processDecodedPcmForTesting(
                    source = source,
                    offset = 0,
                    length = source.size,
                    decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                    sourceSample = 1234L,
                )

                assertTrue(written.isNotEmpty())
                val lastOutput = written.last()
                assertTrue(readLastSample(lastOutput) > 1_500)
            } finally {
                renderer.releaseForTesting()
            }
        }
    }

    @Test
    fun lpcmAdoptsOffToOnPreparedGraphThroughRtpAndCrossfades() {
        val provider = MutableConfigProvider(DspRuntimeConfig.disabled())
        val prepared = LinkedBlockingQueue<Unit>()
        val written = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig.disabled(),
            provider = provider,
            writeAdapter = { bytes, offset, length, _ ->
                written.add(bytes.copyOfRange(offset, offset + length))
                length
            },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                if (!config.enabled) null else pipeline(audioFormat, latencyFrames = 0, gain = 2.0f)
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(1) { 700 }), sample = 1_000)
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 6.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)

            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 700 }), sample = 2_000)

            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            assertEquals(1L, renderer.dspGenerationForTesting)
            assertNotNull(renderer.dspPipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1_000L, renderer.playbackClockForTesting()?.baseRtpSample)
            assertTrue(written.size >= 2)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun lpcmAdoptsOnToOffPreparedGraphThroughRtpAndCompletesCrossfade() {
        val provider = MutableConfigProvider(DspRuntimeConfig(enabled = true, gainDb = 3.0))
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = provider.versionedSnapshot().config,
            provider = provider,
            writeAdapter = { _, _, length, _ -> length },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                if (!config.enabled) null else pipeline(audioFormat, latencyFrames = 0, gain = 1.5f)
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(1) { 400 }), sample = 40)
            provider.update(DspRuntimeConfig.disabled())
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)

            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 400 }), sample = 80)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertNull(renderer.dspPipeline)
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            assertFalse(renderer.liveFadeInProgressForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun lpcmAppliesProfileAToBPreparedGraphThroughTheLiveCrossfadePath() {
        val configA = DspRuntimeConfig(enabled = true, gainDb = 0.0)
        val provider = MutableConfigProvider(configA)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = configA,
            provider = provider,
            writeAdapter = { _, _, length, _ -> length },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                pipeline(audioFormat, latencyFrames = 0, gain = 10.0.pow(config.gainDb / 20.0).toFloat())
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(1) { 300 }), sample = 300)
            val activeBeforeUpdate = renderer.dspPipeline
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 3.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)

            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 300 }), sample = 400)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertNotNull(renderer.dspPipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            assertTrue(renderer.dspPipeline !== activeBeforeUpdate)
            assertEquals(300L, renderer.playbackClockForTesting()?.baseRtpSample)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun rendererStartAndRtpSubmitReturnWhileWorkerPipelinePreparationIsBlocked() {
        val preparationEntered = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val format = audioFormat(AudioCodecKind.LPCM, channels = 1)
        val renderer = renderer(
            format = format,
            initialConfig = DspRuntimeConfig(enabled = true),
            pipelinePreparer = { audioFormat, _ ->
                preparationEntered.countDown()
                check(releasePreparation.await(5, TimeUnit.SECONDS))
                pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
            },
        )
        try {
            assertTrue(renderer.start())
            assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))
            renderer.submit(lpcmRtp(IntArray(1) { 1 }), sample = 0)
            assertEquals(1, renderer.queuedPacketsForTesting)
        } finally {
            releasePreparation.countDown()
            renderer.close()
        }
    }

    @Test
    fun trackGenerationResetClearsDspTemporalStateAndPlaybackClock() {
        val resetCount = AtomicInteger()
        val processorRef = AtomicReference<StateProcessor?>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig(enabled = true),
            writeAdapter = { bytes, offset, length, _ ->
                writes.add(bytes.copyOfRange(offset, offset + length))
                length
            },
            pipelinePreparer = { audioFormat, _ ->
                StateProcessor(audioFormat, latencyFrames = 0, gain = 1.0f, onReset = { resetCount.incrementAndGet() })
                    .also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 300 }), sample = 100)
            assertEquals(64, processorRef.get()?.processedFrames)
            assertEquals(100L, renderer.playbackClockForTesting()?.baseRtpSample)

            renderer.beginTrackGenerationForTesting()

            assertEquals(0, processorRef.get()?.processedFrames)
            assertTrue(resetCount.get() >= 2)
            assertNull(renderer.playbackClockForTesting())
            assertEquals(0, renderer.trackLatencyForTesting)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 300 }), sample = 200)
            assertEquals(200L, renderer.playbackClockForTesting()?.baseRtpSample)
            assertEquals(2, writes.size)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun trackGenerationChangeCompletesAndResetsAnInProgressLiveCrossfade() {
        val provider = MutableConfigProvider(DspRuntimeConfig(enabled = true, gainDb = 0.0))
        val prepared = LinkedBlockingQueue<Unit>()
        val processors = CopyOnWriteArrayList<StateProcessor>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = provider.versionedSnapshot().config,
            provider = provider,
            writeAdapter = { _, _, length, _ -> length },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                StateProcessor(audioFormat, 0, 10.0.pow(config.gainDb / 20.0).toFloat()).also(processors::add)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(1) { 250 }), sample = 10)
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 4.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 250 }), sample = 20)

            assertTrue(renderer.liveFadeInProgressForTesting)
            val replacementProcessor = processors.last()
            assertEquals(64, replacementProcessor.processedFrames)

            renderer.beginTrackGenerationForTesting()

            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.dspGenerationForTesting)
            assertNotNull(renderer.dspPipeline)
            assertEquals(0, replacementProcessor.processedFrames)
            assertFalse(renderer.trackContentCommittedForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun nonzeroLatencyProcessFailureKeepsClockLatencyAndContinuesOnDelayedDryAudio() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig(enabled = true),
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            pipelinePreparer = { audioFormat, _ ->
                DspPcmPipeline(
                    audioFormat,
                    StateProcessor(audioFormat, latencyFrames = 128, gain = 1.0f, failOnProcess = 4),
                    forceInitialLatencySilence = false,
                )
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            repeat(4) { block ->
                renderer.handleRtpForTesting(
                    lpcmRtp(IntArray(64) { frame -> 400 + block * 64 + frame }),
                    sample = 0xffff_ff00.toInt() + block * 64,
                )
            }

            assertNull(renderer.dspPipeline)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertEquals(128, renderer.playbackClockForTesting(64)?.algorithmicLatencyFrames)
            assertEquals(4, writes.size)
            for (frame in 0 until 64) {
                val expected = readSample(writes[1], frame * Short.SIZE_BYTES)
                val actual = readSample(writes[3], frame * Short.SIZE_BYTES)
                assertTrue("latency-preserving fallback frame $frame", abs(expected - actual) <= 2)
            }

            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 900 }), sample = 100)
            assertEquals(5, writes.size)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertTrue(renderer.trackContentCommittedForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun recoveryCrossfadeUsesDelayedDryFloatsAsItsOldSignal() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val provider = MutableConfigProvider(DspRuntimeConfig(enabled = true))
        val prepared = LinkedBlockingQueue<Unit>()
        val pipelineCreations = AtomicInteger()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig(enabled = true),
            provider = provider,
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ ->
                if (pipelineCreations.getAndIncrement() == 0) {
                    val processor = StateProcessor(audioFormat, latencyFrames = 128, gain = 1.0f, failOnProcess = 5)
                    DspPcmPipeline(audioFormat, processor, forceInitialLatencySilence = false)
                } else {
                    pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
                }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(480)), sample = 0)
            repeat(4) { block ->
                renderer.handleRtpForTesting(
                    lpcmRtp(IntArray(64) { frame -> 1_000 + block * 1_000 + frame }),
                    sample = block * 64,
                )
            }
            assertNull(renderer.dspPipeline)
            assertEquals(128, renderer.trackLatencyForTesting)

            provider.update(DspRuntimeConfig(enabled = true, gainDb = 1.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            Thread.sleep(50)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { frame -> 5_000 + frame }), sample = 256)

            assertTrue(renderer.liveFadeInProgressForTesting)
            assertEquals(6, writes.size)
            assertTrue(abs(readSample(writes.last(), 0) - 3_000) <= 2)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun oversizedDecodedPcmUsesTheSameBoundedChunksForDspAndLatencyFallback() {
        val frameCount = DspBufferSizing.MAX_DECODER_PCM_FRAMES + 1
        val sourceSamples = IntArray(frameCount) { (it % 12_000) - 6_000 }
        val source = ByteArray(frameCount * Short.SIZE_BYTES)
        sourceSamples.forEachIndexed { index, sample ->
            source[index * 2] = sample.toByte()
            source[index * 2 + 1] = (sample shr 8).toByte()
        }
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.AAC_LC, channels = 1),
            initialConfig = DspRuntimeConfig(enabled = true),
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            pipelinePreparer = { audioFormat, _ ->
                val processor = StateProcessor(audioFormat, latencyFrames = 128, gain = 1.0f, failOnProcess = 1)
                DspPcmPipeline(audioFormat, processor, forceInitialLatencySilence = false)
            },
        )
        try {
            renderer.prepareDecoderPcmFormatForTesting(48_000, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source,
                0,
                source.size,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 900,
            )

            assertTrue(writes.size >= 2)
            assertEquals(128, renderer.trackLatencyForTesting)
            val output = ByteArray(source.size)
            var outputOffset = 0
            writes.forEach { chunk ->
                chunk.copyInto(output, outputOffset)
                outputOffset += chunk.size
            }
            assertEquals(source.size, outputOffset)
            for (frame in 0 until frameCount) {
                val sourceFrame = frame - 128
                val expected = when {
                    sourceFrame < 0 -> 0
                    frame < 480 -> sourceSamples[sourceFrame].toLong() * (frame + 1) / 480
                    else -> sourceSamples[sourceFrame].toLong()
                }.toInt()
                val actual = readSample(output, frame * Short.SIZE_BYTES)
                assertTrue("delayed frame $frame expected=$expected actual=$actual", abs(actual - expected) <= 2)
            }
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun mismatchedIrKeepsLatencyAndDefersValidConvolverUntilANewTrack() {
        val irA = DspImpulseResponse("room_a", 48_000, 1, floatArrayOf(1.0f))
        val irB = DspImpulseResponse("room_b", 44_100, 1, floatArrayOf(1.0f))
        val irC = DspImpulseResponse("room_c", 48_000, 1, floatArrayOf(1.0f))
        val configA = convolverConfig(irA)
        val provider = MutableConfigProvider(configA)
        val prepared = LinkedBlockingQueue<Unit>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = configA,
            provider = provider,
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                val enabled = config.convolver.enabled
                val gain = when (config.convolver.impulseResponseId) {
                    "room_a" -> 2.0f
                    "room_c" -> 3.0f
                    else -> 1.0f
                }
                pipeline(audioFormat, latencyFrames = if (enabled) 128 else 0, gain = gain)
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(1) { 1_000 }), sample = 10)

            provider.update(convolverConfig(irB))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 1_000 }), sample = 20)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertFalse(renderer.effectiveConvolverEnabledForTesting)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(256) { 1_000 }), sample = 30)
            assertTrue(readSample(writes.last(), 200 * Short.SIZE_BYTES) in 990..1_010)

            provider.update(convolverConfig(irB, gainDb = 4.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            Thread.sleep(50)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 1_000 }), sample = 35)

            assertEquals(2L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertFalse(renderer.effectiveConvolverEnabledForTesting)
            assertNull(renderer.deferredDspGenerationForTesting)

            provider.update(convolverConfig(irC))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            Thread.sleep(50)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(2_400) { 1_000 }), sample = 40)

            assertEquals(2L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertFalse(renderer.effectiveConvolverEnabledForTesting)
            assertEquals(3L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 50)

            assertEquals(3L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertTrue(renderer.effectiveConvolverEnabledForTesting)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun dynamicsGraphUpdatesWaitUntilANewTrackGeneration() {
        val compressor = DspCompressorConfig(enabled = true, attackMs = 1_000.0, releaseMs = 1_000.0)
        val initialConfig = DspRuntimeConfig(enabled = true, compressor = compressor)
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                pipeline(audioFormat, latencyFrames = 0, gain = if (config.gainDb == 0.0) 1.0f else 0.5f)
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 10)
            val activePipeline = renderer.dspPipeline

            provider.update(DspRuntimeConfig(enabled = true, gainDb = 6.0, compressor = compressor))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 20)

            assertTrue(renderer.dspPipeline === activePipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 30)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activePipeline)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun activeConvolverGraphUpdatesWaitToPreserveItsFirHistory() {
        val impulse = DspImpulseResponse("room_a", 48_000, 1, floatArrayOf(1.0f))
        val initialConfig = convolverConfig(impulse)
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                pipeline(audioFormat, latencyFrames = if (config.convolver.enabled) 128 else 0, gain = 1.0f)
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 10)
            val activePipeline = renderer.dspPipeline

            provider.update(convolverConfig(impulse, gainDb = 4.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 20)

            assertTrue(renderer.dspPipeline === activePipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 30)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activePipeline)
            assertEquals(128, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun decoderFormatChangeCancelsAnInFlightFadeAndRetiresItsOldFormatGraph() {
        val oldCandidateClosed = CountDownLatch(1)
        val prepared = LinkedBlockingQueue<Unit>()
        val provider = MutableConfigProvider(DspRuntimeConfig.disabled())
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.AAC_LC, channels = 1),
            initialConfig = DspRuntimeConfig.disabled(),
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ ->
                if (audioFormat.sampleRate == 44_100) {
                    val processor = StateProcessor(audioFormat, 0, 1.0f)
                    DspPcmPipeline(audioFormat, processor, forceInitialLatencySilence = false).also {
                        processor.onClose = oldCandidateClosed::countDown
                    }
                } else {
                    pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
                }
            },
        )
        try {
            renderer.prepareDecoderPcmFormatForTesting(44_100, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(pcm16(64, 1, 100), 0, 128, AndroidAudioFormat.ENCODING_PCM_16BIT, 10)

            provider.update(DspRuntimeConfig(enabled = true, gainDb = 2.0))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(pcm16(64, 1, 200), 0, 128, AndroidAudioFormat.ENCODING_PCM_16BIT, 15)
            assertTrue(renderer.liveFadeInProgressForTesting)

            renderer.prepareDecoderPcmFormatForTesting(48_000, 2, AndroidAudioFormat.ENCODING_PCM_16BIT)

            assertTrue(oldCandidateClosed.await(5, TimeUnit.SECONDS))
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(DspAudioFormat(48_000, 2), renderer.dspPipeline?.format)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(pcm16(64, 2, 200), 0, 256, AndroidAudioFormat.ENCODING_PCM_16BIT, 20)
            assertEquals(DspAudioFormat(48_000, 2), renderer.dspPipeline?.format)
            assertFalse(renderer.liveFadeInProgressForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun staleFormatCandidateIsClosedAndCannotReplaceTheCurrentFormat() {
        val enteredOldPublish = CountDownLatch(1)
        val releaseOldPublish = CountDownLatch(1)
        val oldCandidateClosed = CountDownLatch(1)
        val oldCandidateCloseCount = AtomicInteger()
        val provider = MutableConfigProvider(DspRuntimeConfig.disabled())
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.AAC_LC, channels = 1),
            initialConfig = DspRuntimeConfig.disabled(),
            provider = provider,
            beforeDspUpdatePublish = {
                enteredOldPublish.countDown()
                check(releaseOldPublish.await(5, TimeUnit.SECONDS))
            },
            pipelinePreparer = { audioFormat, _ ->
                if (audioFormat.sampleRate == 44_100) {
                    val processor = StateProcessor(audioFormat, 0, 1.0f, onReset = {})
                    DspPcmPipeline(audioFormat, processor, forceInitialLatencySilence = false).also {
                        processor.onClose = {
                            oldCandidateCloseCount.incrementAndGet()
                            oldCandidateClosed.countDown()
                        }
                    }
                } else {
                    pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
                }
            },
        )
        try {
            renderer.prepareDecoderPcmFormatForTesting(44_100, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 2.0))
            assertTrue(enteredOldPublish.await(5, TimeUnit.SECONDS))

            renderer.prepareDecoderPcmFormatForTesting(48_000, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            releaseOldPublish.countDown()

            assertTrue(oldCandidateClosed.await(5, TimeUnit.SECONDS))
            assertEquals(1, oldCandidateCloseCount.get())
            assertEquals(DspAudioFormat(48_000, 1), renderer.dspPipeline?.format)
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
        } finally {
            releaseOldPublish.countDown()
            renderer.releaseForTesting()
        }
    }

    private fun renderer(
        format: AudioFormat,
        initialConfig: DspRuntimeConfig,
        provider: MutableConfigProvider = MutableConfigProvider(initialConfig),
        writeAdapter: AudioTrackWriteAdapterForTest = AudioTrackWriteAdapterForTest { _, _, length, _ -> length },
        beforeDspUpdatePublish: () -> Unit = {},
        onPrepared: () -> Unit = {},
        pipelinePreparer: DspPipelinePreparer,
    ): AudioRenderer {
        val classification = AudioStreamClassifier.classify(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationRoute = NavigationAudioRoute.FULL_BAND,
        )
        return AudioRenderer(
            format = format,
            classification = classification,
            dspRuntimeConfig = initialConfig,
            advancedAudioChannelMapping = false,
            navigationAudioRoute = NavigationAudioRoute.FULL_BAND,
            transport = "wired",
            audioManager = null,
            mediaBufferMillis = 300,
            report = {},
            dspConfigProvider = provider,
            dspPipelinePreparer = pipelinePreparer,
            audioTrackWriteAdapterForTest = writeAdapter,
            beforeDspUpdatePublishForTest = beforeDspUpdatePublish,
            onDspUpdatePreparedForTest = onPrepared,
        )
    }

    private fun audioFormat(codec: AudioCodecKind, channels: Int): AudioFormat = AudioFormat(
        codec = codec,
        sampleRate = 48_000,
        channels = channels,
        payloadType = if (codec == AudioCodecKind.LPCM) 100 else 96,
        audioType = "media",
    )

    private fun convolverConfig(ir: DspImpulseResponse, gainDb: Double = 0.0): DspRuntimeConfig = DspRuntimeConfig(
        enabled = true,
        gainDb = gainDb,
        autoHeadroomEnabled = false,
        convolver = DspConvolverConfig(
            enabled = true,
            impulseResponseId = ir.id,
            impulseResponse = ir,
        ),
    )

    private fun pipeline(format: DspAudioFormat, latencyFrames: Int, gain: Float): DspPcmPipeline =
        DspPcmPipeline(
            format = format,
            processor = StateProcessor(format, latencyFrames, gain),
            forceInitialLatencySilence = false,
        )

    private fun pcm16(frames: Int, channels: Int, sample: Int): ByteArray =
        ByteArray(frames * channels * Short.SIZE_BYTES).also { bytes ->
            var offset = 0
            repeat(frames * channels) {
                bytes[offset++] = sample.toByte()
                bytes[offset++] = (sample shr 8).toByte()
            }
        }

    private fun lpcmRtp(samples: IntArray): ByteArray = ByteArray(12 + samples.size * Short.SIZE_BYTES).also { rtp ->
        var offset = 12
        samples.forEach { sample ->
            rtp[offset++] = (sample shr 8).toByte()
            rtp[offset++] = sample.toByte()
        }
    }

    private fun readSample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()

    private fun readLastSample(bytes: ByteArray): Int = readSample(bytes, bytes.size - Short.SIZE_BYTES)

    private class StateProcessor(
        override val format: DspAudioFormat,
        override val latencyFrames: Int,
        private val gain: Float,
        private val failOnProcess: Int? = null,
        private val onReset: () -> Unit = {},
    ) : DspProcessor {
        private val result = DspProcessResult()
        var processedFrames = 0
            private set
        private var processCalls = 0
        var onClose: () -> Unit = {}

        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
            processCalls++
            if (processCalls == failOnProcess) {
                return result.failure(frames, latencyFrames, DspBypassReason.NATIVE_FAILURE, errorCode = 1)
            }
            val start = input.position()
            repeat(frames * format.channels) { sample ->
                output.putFloat(input.getFloat(start + sample * Float.SIZE_BYTES) * gain)
            }
            input.position(start + frames * format.channels * Float.SIZE_BYTES)
            processedFrames += frames
            return result.success(frames, latencyFrames)
        }

        override fun reset() {
            processCalls = 0
            processedFrames = 0
            onReset()
        }

        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

        override fun close() = onClose()
    }

    private class MutableConfigProvider(initial: DspRuntimeConfig) : DspConfigProvider {
        private val current = AtomicReference(DspConfigSnapshot(0L, initial))
        private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()

        override fun snapshot(): DspRuntimeConfig = current.get().config

        override fun versionedSnapshot(): DspConfigSnapshot = current.get()

        override fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable {
            listeners.add(listener)
            listener(current.get())
            return Closeable { listeners.remove(listener) }
        }

        fun update(config: DspRuntimeConfig) {
            val next = current.updateAndGet { DspConfigSnapshot(it.generation + 1L, config) }
            listeners.forEach { it(next) }
        }
    }
}
