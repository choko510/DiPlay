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
import java.util.concurrent.atomic.AtomicBoolean
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
    fun processorProcessAndTrackResetStayOnTheAudioOwnerThread() {
        val anyReset = CountDownLatch(1)
        val anyProcess = CountDownLatch(1)
        val processors = CopyOnWriteArrayList<TrackingProcessor>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = DspRuntimeConfig(enabled = true),
            pipelinePreparer = { format, _ ->
                TrackingProcessor(
                    format = format,
                    onProcess = anyProcess::countDown,
                    onReset = anyReset::countDown,
                ).also { processors += it }
                    .let { DspPcmPipeline(format, it, forceInitialLatencySilence = false) }
            },
        )

        try {
            assertTrue(renderer.start())
            assertTrue(anyReset.await(5, TimeUnit.SECONDS))
            renderer.submit(lpcmRtp(IntArray(64) { 300 }), sample = 0)
            assertTrue(anyProcess.await(5, TimeUnit.SECONDS))

            assertTrue(
                "the active graph must be reset and processed by the same audio owner",
                processors.any { processor ->
                    processor.operations.any { it.first == "reset" && it.second == "carplay-audio" } &&
                        processor.operations.any { it.first == "process" && it.second == "carplay-audio" }
                },
            )
            assertFalse(processors.any(TrackingProcessor::overlapped))
        } finally {
            renderer.close()
        }
    }

    @Test
    fun dspOffLpcmProductionPathWritesTheOriginalPcm16Samples() {
        for (sampleRate in listOf(44_100, 48_000)) {
            for (channels in listOf(1, 2)) {
                val writes = CopyOnWriteArrayList<ByteArray>()
                val renderer = renderer(
                    format = audioFormat(AudioCodecKind.LPCM, channels, sampleRate = sampleRate),
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
                    assertEquals(0, renderer.trackLatencyForTesting)
                    renderer.handleRtpForTesting(lpcmRtp(IntArray(channels)), sample = 100)
                    val samples = IntArray(64 * channels) { frame -> (frame * 701 % 30_000) - 15_000 }
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
    fun lpcmDefersPreampChangeUntilANewTrackWhenSampleRampIsUnavailable() {
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

            assertEquals(0L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline === activeBeforeUpdate)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)
            assertEquals(
                "preamp sample ramp is unavailable; keeping the active pipeline until the next AudioTrack generation",
                provider.rendererApplyStatus()?.reason,
            )

            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 300 }), sample = 500)

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activeBeforeUpdate)
            assertNull(renderer.deferredDspGenerationForTesting)
            assertEquals(500L, renderer.playbackClockForTesting()?.baseRtpSample)
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
            provider.update(DspRuntimeConfig(enabled = true, stereoWidth = 1.25))
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
    fun failedHotProcessingClearsPendingGenerationAndReportsFailure() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            processorRef.get()?.failProcessing?.set(true)

            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 400),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 200L,
            )

            assertFalse(renderer.hasPendingHotApplyForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.FAILED, generation = 1L)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun disablingHotUpdatesAfterPreparationDefersWithoutEnqueueing() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            provider.setStudioHotUpdatesEnabled(false)

            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 400),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 200L,
            )

            assertEquals(0, processorRef.get()?.hotUpdateEnqueueAttempts)
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)
            assertEquals(DspRendererApplyPhase.DEFERRED, provider.rendererApplyStatus()?.phase)
            assertEquals("Native HOT updates are disabled", provider.rendererApplyStatus()?.reason)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun rejectedHotUpdateRetriesAndReportsWhyTheActivePipelineIsKept() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            val processor = checkNotNull(processorRef.get())
            processor.rejectNextHotUpdate = true

            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 400),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 200L,
            )

            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)
            assertEquals(
                "native DSP processor rejected the hot update; retrying on the next PCM block while keeping the active pipeline",
                provider.rendererApplyStatus()?.reason,
            )
            assertEquals(1, processor.hotUpdateEnqueueAttempts)

            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 500),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 264L,
            )

            assertEquals(2, processor.hotUpdateEnqueueAttempts)
            assertEquals(1L, renderer.dspGenerationForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.ACTIVE, generation = 1L)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun rendererCloseFailsAnUnacknowledgedHotGeneration() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )
            processorRef.get()?.suppressAcknowledgement = true
            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 400),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 200L,
            )
            assertTrue(renderer.hasPendingHotApplyForTesting)

            renderer.close()

            assertFalse(renderer.hasPendingHotApplyForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.FAILED, generation = 1L)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun replacedRendererCloseCannotRegressNewOwnerActiveStatus() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val oldProcessorRef = AtomicReference<HotProcessor?>()
        val oldRenderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            rendererOwnerToken = 100L,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(oldProcessorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        var newRenderer: AudioRenderer? = null
        try {
            oldRenderer.prepareLpcmPipelineForTesting()
            oldRenderer.beginTrackGenerationForTesting()
            oldRenderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )
            oldProcessorRef.get()?.suppressAcknowledgement = true
            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            oldRenderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 400),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 200L,
            )
            assertTrue(oldRenderer.hasPendingHotApplyForTesting)

            val replacement = renderer(
                format = audioFormat(AudioCodecKind.LPCM, channels = 1),
                initialConfig = provider.snapshot(),
                provider = provider,
                rendererOwnerToken = 101L,
                pipelinePreparer = { audioFormat, _ -> pipeline(audioFormat, latencyFrames = 0, gain = 1.0f) },
            )
            newRenderer = replacement
            replacement.prepareLpcmPipelineForTesting()
            assertEquals(DspRendererApplyPhase.ACTIVE, provider.rendererApplyStatus()?.phase)
            assertEquals(101L, provider.rendererApplyStatus()?.rendererOwnerToken)

            oldRenderer.close()

            assertFalse(oldRenderer.hasPendingHotApplyForTesting)
            assertEquals(
                DspRendererApplyStatus(
                    1L,
                    DspRendererApplyPhase.ACTIVE,
                    activeGeneration = 1L,
                    rendererOwnerToken = 101L,
                ),
                provider.rendererApplyStatus(),
            )
        } finally {
            oldRenderer.releaseForTesting()
            newRenderer?.releaseForTesting()
        }
    }

    @Test
    fun rendererCloseWinsWhenAckHasReadPendingHotGeneration() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set).let {
                    DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false)
                }
            },
        )
        val ackReadPending = CountDownLatch(1)
        val resumeAck = CountDownLatch(1)
        val processFailure = AtomicReference<Throwable?>()
        val processThread = Thread {
            runCatching {
                renderer.processDecodedPcmForTesting(
                    source = pcm16(frames = 64, channels = 1, sample = 400),
                    offset = 0,
                    length = 128,
                    decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                    sourceSample = 200L,
                )
            }.onFailure(processFailure::set)
        }.apply { isDaemon = true }

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )
            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            assertNotNull(processorRef.get())
            renderer.afterHotApplyPendingReadForTest = {
                ackReadPending.countDown()
                check(resumeAck.await(5, TimeUnit.SECONDS))
            }
            processThread.start()
            assertTrue(ackReadPending.await(5, TimeUnit.SECONDS))
            assertTrue(renderer.hasPendingHotApplyForTesting)

            renderer.close()
            awaitApplyStatus(provider, DspRendererApplyPhase.FAILED, generation = 1L)

            resumeAck.countDown()
            processThread.join(5_000)
            assertFalse(processThread.isAlive)
            assertNull(processFailure.get())
            assertFalse(renderer.hasPendingHotApplyForTesting)
            assertEquals(DspRendererApplyPhase.FAILED, provider.rendererApplyStatus()?.phase)
        } finally {
            resumeAck.countDown()
            processThread.join(1_000)
            renderer.releaseForTesting()
        }
    }

    @Test
    fun hotContextRebindFailsPendingGenerationAfterAckRead() {
        val initial = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initial)
        val initialProcessor = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initial,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(initialProcessor::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        val ackReadPending = CountDownLatch(1)
        val resumeAck = CountDownLatch(1)
        val processFailure = AtomicReference<Throwable?>()
        val processThread = Thread {
            runCatching {
                renderer.processDecodedPcmForTesting(
                    source = pcm16(frames = 64, channels = 1, sample = 400),
                    offset = 0,
                    length = 128,
                    decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                    sourceSample = 200L,
                )
            }.onFailure(processFailure::set)
        }.apply { isDaemon = true }

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                source = pcm16(frames = 64, channels = 1, sample = 300),
                offset = 0,
                length = 128,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 100L,
            )
            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    macroTone = DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            assertNotNull(initialProcessor.get())
            renderer.afterHotApplyPendingReadForTest = {
                ackReadPending.countDown()
                check(resumeAck.await(5, TimeUnit.SECONDS))
            }
            processThread.start()
            assertTrue(ackReadPending.await(5, TimeUnit.SECONDS))
            assertTrue(renderer.hasPendingHotApplyForTesting)

            renderer.rebindHotPipelineForTesting()

            assertFalse(renderer.hasPendingHotApplyForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.FAILED, generation = 1L)

            resumeAck.countDown()
            processThread.join(5_000)
            assertFalse(processThread.isAlive)
            assertNull(processFailure.get())
            assertEquals(DspRendererApplyPhase.FAILED, provider.rendererApplyStatus()?.phase)
            assertEquals(1L, provider.rendererApplyStatus()?.generation)
        } finally {
            resumeAck.countDown()
            processThread.join(1_000)
            renderer.releaseForTesting()
        }
    }

    @Test
    fun seatOnlyFallbackPreservesRelativeDelayWhenCommonLatencyIsZero() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val seat = DspSeatAlignmentConfig(
            enabled = true,
            nearSpeaker = DspSeatNearSpeaker.LEFT,
            distanceDifferenceCm = 34_300.0 * 1.5 / 48_000.0,
        )
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false, seatAlignment = seat),
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            pipelinePreparer = { audioFormat, _ ->
                DspPcmPipeline(
                    audioFormat,
                    StateProcessor(audioFormat, latencyFrames = 0, gain = 1.0f, failOnProcess = 1),
                    forceInitialLatencySilence = false,
                )
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            assertEquals(0, renderer.trackLatencyForTesting)

            renderer.handleRtpForTesting(lpcmRtp(intArrayOf(12_000, 0, 0, 0, 0, 0, 0, 0)), sample = 0)

            assertEquals(1, writes.size)
            val output = writes.single()
            assertTrue(abs(readSample(output, 0)) <= 2)
            assertTrue(abs(readSample(output, 2 * Short.SIZE_BYTES)) > 500)
            assertTrue(abs(readSample(output, Short.SIZE_BYTES)) <= 2)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun nativeFailureFallbackPreservesSeatTrimAndPolarity() {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                seatAlignment = DspSeatAlignmentConfig(
                    enabled = true,
                    leftTrimDb = 6.0,
                    rightTrimDb = -6.0,
                    invertLeft = true,
                    invertRight = true,
                ),
            ),
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            pipelinePreparer = { audioFormat, _ ->
                DspPcmPipeline(
                    audioFormat,
                    StateProcessor(audioFormat, latencyFrames = 0, gain = 1.0f, failOnProcess = 1),
                    forceInitialLatencySilence = false,
                )
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(intArrayOf(-4_000, 6_000)), sample = 0)

            val output = writes.single()
            val expectedLeft = 4_000 * Math.pow(10.0, 6.0 / 20.0)
            val expectedRight = -6_000 * Math.pow(10.0, -6.0 / 20.0)
            assertTrue(abs(readSample(output, 0) - expectedLeft) <= 3)
            assertTrue(abs(readSample(output, Short.SIZE_BYTES) - expectedRight) <= 3)
            assertNull(renderer.dspPipeline)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun fallbackUsesTheAcceptedSeatGenerationAfterLiveCrossfade() {
        val impulseResponse = DspImpulseResponse("seat_fallback_room", 48_000, 1, floatArrayOf(1.0f))
        val initialConfig = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            convolver = DspConvolverConfig(
                enabled = true,
                impulseResponseId = impulseResponse.id,
                impulseResponse = impulseResponse,
            ),
            seatAlignment = DspSeatAlignmentConfig(
                enabled = true,
                nearSpeaker = DspSeatNearSpeaker.LEFT,
                distanceDifferenceCm = 34_300.0 * 1.5 / 48_000.0,
            ),
        )
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                if (!config.enabled) {
                    null
                } else {
                    DspPcmPipeline(
                        audioFormat,
                        StateProcessor(audioFormat, latencyFrames = 128, gain = 1.0f),
                        forceInitialLatencySilence = false,
                    )
                }
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(128)), sample = 0)

            provider.update(DspRuntimeConfig(enabled = false, autoHeadroomEnabled = false))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            repeat(40) { block ->
                renderer.handleRtpForTesting(lpcmRtp(IntArray(128)), sample = 64 + block * 64)
            }

            assertFalse(renderer.liveFadeInProgressForTesting)
            assertNull(renderer.dspPipeline)
            assertEquals(128, renderer.trackLatencyForTesting)

            renderer.handleRtpForTesting(lpcmRtp(intArrayOf(12_000, 0) + IntArray(510)), sample = 3_000)

            val output = writes.last()
            val firstAfterCommonDelay = readSample(output, 128 * 2 * Short.SIZE_BYTES)
            assertTrue(
                "fallback must use the current graph's zero-relative-delay configuration",
                firstAfterCommonDelay > 10_000,
            )
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun liveDspOffPreservesTrackLatencyAndNextTrackReturnsToExactPcm() {
        val provider = MutableConfigProvider(DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false))
        val prepared = LinkedBlockingQueue<Unit>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = provider.versionedSnapshot().config,
            provider = provider,
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                if (!config.enabled) {
                    null
                } else {
                    DspPcmPipeline(
                        audioFormat,
                        StateProcessor(audioFormat, latencyFrames = 128, gain = 1.0f),
                        forceInitialLatencySilence = false,
                    )
                }
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(128) { 1_000 }), sample = 0)

            provider.update(DspRuntimeConfig(enabled = false, autoHeadroomEnabled = false))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            repeat(40) { block ->
                renderer.handleRtpForTesting(lpcmRtp(IntArray(128)), sample = 64 + block * 64)
            }

            assertFalse(renderer.liveFadeInProgressForTesting)
            assertNull(renderer.dspPipeline)
            assertEquals(128, renderer.trackLatencyForTesting)
            renderer.handleRtpForTesting(lpcmRtp(intArrayOf(12_000, 12_000) + IntArray(126)), sample = 3_000)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(128)), sample = 3_064)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(128)), sample = 3_128)
            assertTrue(abs(readSample(writes[writes.lastIndex], 0) - 12_000) <= 2)

            renderer.beginTrackGenerationForTesting()
            assertEquals(0, renderer.trackLatencyForTesting)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(128) { 600 }), sample = 4_000)
            assertTrue(writes.last().contentEquals(pcm16(64, 2, 600)))
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun seatOnlyLiveOffCrossfadeTargetsDryPcmInsteadOfTheOldSeatFallback() {
        val seat = DspSeatAlignmentConfig(
            enabled = true,
            nearSpeaker = DspSeatNearSpeaker.LEFT,
            distanceDifferenceCm = 34_300.0 * 1.5 / 48_000.0,
        )
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false, seatAlignment = seat)
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            writeAdapter = { bytes, offset, length, _ ->
                writes += bytes.copyOfRange(offset, offset + length)
                length
            },
            pipelinePreparer = { audioFormat, config ->
                if (config.enabled) pipeline(audioFormat, latencyFrames = 0, gain = 1.0f) else null
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 0),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 10L,
            )

            provider.update(DspRuntimeConfig.disabled())
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            val duringFade = pcm16(frames = 1_024, channels = 2, sample = 0).also { samples ->
                writeSample(samples, frame = 500, channel = 0, channels = 2, sample = 12_000)
            }
            renderer.processDecodedPcmForTesting(
                duringFade,
                offset = 0,
                length = duringFade.size,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 20L,
            )

            assertTrue(writes.size >= 2)
            assertTrue("OFF fade target must be the dry sample", readSample(writes[1], 500 * 2 * Short.SIZE_BYTES) in 11_900..12_100)
            assertNull(renderer.dspPipeline)
            assertNull(renderer.deferredDspGenerationForTesting)
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

            provider.update(DspRuntimeConfig(enabled = true, stereoWidth = 1.25))
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
            assertEquals(
                DspRendererApplyWarning.CONVOLVER_SAMPLE_RATE_MISMATCH,
                provider.rendererApplyStatus()?.warning,
            )
            renderer.handleRtpForTesting(lpcmRtp(IntArray(256) { 1_000 }), sample = 30)
            assertTrue(readSample(writes.last(), 200 * Short.SIZE_BYTES) in 990..1_010)

            provider.update(convolverConfig(irB, stereoWidth = 1.25))
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
            assertNull(provider.rendererApplyStatus()?.warning)
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
    fun peqLocalWarmUpdatesOnlyThePeqStageWhileCompressorAndConvolverStayActive() {
        val impulse = DspImpulseResponse("peq_warm_room", 48_000, 1, floatArrayOf(1.0f))
        val compressor = DspCompressorConfig(enabled = true, attackMs = 1_000.0, releaseMs = 1_000.0)
        val initialConfig = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            compressor = compressor,
            convolver = convolverConfig(impulse).convolver,
        )
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val prepared = LinkedBlockingQueue<Unit>()
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                HotProcessor(audioFormat, latencyFrames = if (config.convolver.enabled) 128 else 0)
                    .also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 10)
            val activePipeline = renderer.dspPipeline

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    autoHeadroomEnabled = false,
                    compressor = compressor,
                    convolver = initialConfig.convolver,
                    peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0)),
                ),
            )
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.handleRtpForTesting(lpcmRtp(IntArray(64) { 1_000 }), sample = 20)

            assertTrue(renderer.dspPipeline === activePipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertNull(renderer.deferredDspGenerationForTesting)
            assertEquals(DspRendererApplyPhase.ACTIVE, provider.rendererApplyStatus()?.phase)
            assertEquals(listOf(1L), processorRef.get()?.localPeqWarmEnqueuedGenerations?.toList())
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun peqLocalWarmDefersWhileTheRolloutFlagIsDisabled() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            val activePipeline = renderer.dspPipeline

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertEquals(0, processorRef.get()?.localPeqWarmEnqueueAttempts)
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(DspRendererApplyPhase.DEFERRED, provider.rendererApplyStatus()?.phase)
            assertEquals(
                "PEQ local warm-up is disabled; keeping the active pipeline until the next AudioTrack generation",
                provider.rendererApplyStatus()?.reason,
            )
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun disablingLocalWarmRolloutCancelsAnEnqueuedPeqTransition() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val prepared = LinkedBlockingQueue<Unit>()
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            val activePipeline = renderer.dspPipeline

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            val processor = checkNotNull(processorRef.get())
            processor.suppressLocalPeqWarmAcknowledgement = true
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )
            assertTrue(renderer.hasPendingHotApplyForTesting)
            assertEquals(0L, renderer.dspGenerationForTesting)

            provider.setStudioLocalWarmEnabled(false)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 700), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 30L,
            )
            assertEquals(listOf(1L), processor.localPeqWarmCancelledGenerations.toList())
            assertFalse(renderer.hasPendingHotApplyForTesting)
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 800), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 40L,
            )

            assertTrue(renderer.dspPipeline !== activePipeline)
            assertEquals(1L, renderer.dspGenerationForTesting)
            assertEquals(DspRendererApplyPhase.ACTIVE, provider.rendererApplyStatus()?.phase)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun repeatedPeqEditsPublishOnlyTheLatestUnconsumedWarmCandidate() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val prepared = LinkedBlockingQueue<Long>()
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(provider.versionedSnapshot().generation) },
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_200.0, gainDb = 5.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 2L)

            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )

            assertEquals(2L, renderer.dspGenerationForTesting)
            assertEquals(listOf(2L), processorRef.get()?.localPeqWarmEnqueuedGenerations?.toList())
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.ACTIVE, generation = 2L)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun newerPeqEditSupersedesAnUnacknowledgedWarmTransition() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            val processor = checkNotNull(processorRef.get())
            processor.suppressLocalPeqWarmAcknowledgement = true
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )
            assertTrue(renderer.hasPendingHotApplyForTesting)
            assertEquals(0L, renderer.dspGenerationForTesting)

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_200.0, gainDb = 5.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 2L)
            processor.suppressLocalPeqWarmAcknowledgement = false
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 700), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 30L,
            )
            val retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var retrySample = 800
            while (renderer.dspGenerationForTesting != 2L && System.nanoTime() < retryDeadline) {
                renderer.processDecodedPcmForTesting(
                    pcm16(frames = 64, channels = 1, sample = retrySample++), 0, 128,
                    AndroidAudioFormat.ENCODING_PCM_16BIT, retrySample.toLong(),
                )
                Thread.sleep(5)
            }

            assertEquals(listOf(1L, 2L), processor.localPeqWarmEnqueuedGenerations.toList())
            assertEquals(2L, renderer.dspGenerationForTesting)
            assertFalse(renderer.hasPendingHotApplyForTesting)
            awaitApplyStatus(provider, DspRendererApplyPhase.ACTIVE, generation = 2L)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun rejectedPeqWarmRetriesAreBoundedAndReportDeferred() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val prepared = LinkedBlockingQueue<Unit>()
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            val processor = checkNotNull(processorRef.get())
            processor.rejectLocalPeqWarmTransitionCount = 3

            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)

            repeat(3) { attempt ->
                renderer.processDecodedPcmForTesting(
                    pcm16(frames = 64, channels = 1, sample = 600 + attempt), 0, 128,
                    AndroidAudioFormat.ENCODING_PCM_16BIT, (20L + attempt),
                )
                if (attempt < 2) assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            }

            awaitApplyStatus(provider, DspRendererApplyPhase.DEFERRED, generation = 1L)
            assertEquals(listOf(1L, 1L, 1L), processor.localPeqWarmEnqueuedGenerations.toList())
            assertEquals(listOf(1L, 2L, 3L), processor.localPeqWarmPreparedHistoryEnds.toList())
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)
            assertEquals(
                "native renderer rejected the PEQ warm transition after 2 retries; keeping the active pipeline until the next AudioTrack generation",
                provider.rendererApplyStatus()?.reason,
            )
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun acknowledgedPeqGenerationRepreparesOverlappingMacroChangeFromActiveState() {
        val impulseResponse = DspImpulseResponse("peq_macro_overlap", 48_000, 1, floatArrayOf(1.0f))
        fun config(
            peq: DspEqBand,
            macro: DspMacroToneConfig = DspMacroToneConfig(),
        ) = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            compressor = DspCompressorConfig(enabled = true, thresholdDb = -30.0),
            convolver = DspConvolverConfig(
                enabled = true,
                impulseResponseId = impulseResponse.id,
                impulseResponse = impulseResponse,
            ),
            peqBands = listOf(peq),
            macroTone = macro,
        )
        val initialConfig = config(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 1.0))
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val prepared = LinkedBlockingQueue<Unit>()
        val processorRef = AtomicReference<HotProcessor?>()
        val pipelinePreparations = AtomicInteger()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ ->
                pipelinePreparations.incrementAndGet()
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            val processor = checkNotNull(processorRef.get())
            processor.suppressLocalPeqWarmAcknowledgement = true

            provider.update(config(DspEqBand(DspEqType.PEAK, 1_200.0, gainDb = 3.0)))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )
            assertTrue(renderer.hasPendingHotApplyForTesting)

            provider.update(
                config(
                    DspEqBand(DspEqType.PEAK, 1_200.0, gainDb = 3.0),
                    DspMacroToneConfig(enabled = true, bassGainDb = 1.0),
                ),
            )
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 2L)
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 700), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 30L,
            )
            assertEquals(DspRendererApplyPhase.DEFERRED, provider.rendererApplyStatus()?.phase)

            processor.suppressLocalPeqWarmAcknowledgement = false
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 800), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 40L,
            )
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 900), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 50L,
            )

            assertEquals(2L, renderer.dspGenerationForTesting)
            assertEquals(1, processor.hotUpdateEnqueueAttempts)
            assertEquals(2, pipelinePreparations.get())
            assertEquals(DspRendererApplyPhase.ACTIVE, provider.rendererApplyStatus()?.phase)
            assertEquals(2L, provider.rendererApplyStatus()?.activeGeneration)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun ownerRebindInvalidatesPreparedPeqWarmCandidate() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 500), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            val activePipeline = renderer.dspPipeline

            renderer.rebindHotPipelineForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 600), 0, 128,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertEquals(0, processorRef.get()?.localPeqWarmEnqueueAttempts)
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            assertFalse(renderer.hasPendingHotApplyForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun formatChangeDiscardsPreparedPeqWarmCandidateAndUsesNewFormatGraph() {
        val initialConfig = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val provider = MutableConfigProvider(initialConfig, localWarmEnabled = true)
        val processorRef = AtomicReference<HotProcessor?>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                HotProcessor(audioFormat).also(processorRef::set)
                    .let { DspPcmPipeline(audioFormat, it, forceInitialLatencySilence = false) }
            },
        )
        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 500), 0, 256,
                AndroidAudioFormat.ENCODING_PCM_16BIT, 10L,
            )
            provider.update(initialConfig.withPeqBandsForTest(listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0))))
            awaitApplyStatus(provider, DspRendererApplyPhase.PREPARED, generation = 1L)
            val oldPipeline = renderer.dspPipeline

            renderer.prepareDecoderPcmFormatForTesting(44_100, 2, AndroidAudioFormat.ENCODING_PCM_16BIT)

            assertFalse(renderer.hasPreparedDspUpdateForTesting)
            assertEquals(DspAudioFormat(44_100, 2), renderer.dspPipeline?.format)
            assertTrue(renderer.dspPipeline !== oldPipeline)
            assertEquals(1L, renderer.dspGenerationForTesting)
            assertEquals(0, processorRef.get()?.localPeqWarmEnqueueAttempts)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun activeSeatDelayGraphUpdatesWaitUntilANewTrackGeneration() {
        val seat = DspSeatAlignmentConfig(
            enabled = true,
            preset = DspSeatPreset.DRIVER,
            nearSpeaker = DspSeatNearSpeaker.LEFT,
            distanceDifferenceCm = 50.0,
        )
        val initialConfig = DspRuntimeConfig(enabled = true, seatAlignment = seat)
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ -> pipeline(audioFormat, latencyFrames = 0, gain = 1.0f) },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 10L,
            )
            val activePipeline = renderer.dspPipeline
            val expectedLatency = 0
            assertEquals(expectedLatency, renderer.trackLatencyForTesting)

            provider.update(DspRuntimeConfig(enabled = true, seatAlignment = seat.copy(leftTrimDb = 1.0)))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 30L,
            )

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activePipeline)
            assertEquals(expectedLatency, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun activeAdvancedMidSideGraphUpdatesWaitUntilANewTrackGeneration() {
        val initialConfig = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(enabled = true),
        )
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, _ -> pipeline(audioFormat, latencyFrames = 0, gain = 1.0f) },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 10L,
            )
            val activePipeline = renderer.dspPipeline

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    advancedMs = initialConfig.advancedMs.copy(midHighGainDb = 1.0),
                ),
            )
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertFalse(renderer.liveFadeInProgressForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 30L,
            )

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activePipeline)
            assertEquals(0, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun activeHarmonicGraphUpdatesWaitUntilANewTrackGeneration() {
        val initialConfig = DspRuntimeConfig(
            enabled = true,
            harmonic = DspHarmonicConfig(enabled = true, exciterEnabled = true),
        )
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 2),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                pipeline(audioFormat, latencyFrames = config.harmonic.algorithmicLatencyFrames, gain = 1.0f)
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 10L,
            )
            val activePipeline = renderer.dspPipeline
            assertEquals(8, renderer.trackLatencyForTesting)

            provider.update(
                DspRuntimeConfig(
                    enabled = true,
                    harmonic = initialConfig.harmonic.copy(exciterAmount = 0.3),
                ),
            )
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 2, sample = 1_000),
                offset = 0,
                length = 64 * 2 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 30L,
            )

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertTrue(renderer.dspPipeline !== activePipeline)
            assertEquals(8, renderer.dspPipeline?.algorithmicLatencyFrames)
            assertNull(renderer.deferredDspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun latencyChangingGraphStaysPendingAndBecomesActiveOnTheNextTrack() {
        val ir = DspImpulseResponse("room_next_track", 48_000, 1, floatArrayOf(1.0f))
        val initialConfig = DspRuntimeConfig(enabled = true)
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
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 1_000),
                offset = 0,
                length = 64 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 10L,
            )
            val activePipeline = renderer.dspPipeline
            assertEquals(DspRendererApplyPhase.ACTIVE, provider.rendererApplyStatus()?.phase)

            provider.update(convolverConfig(ir))
            val updateStatus = checkNotNull(provider.rendererApplyStatus())
            assertEquals(1L, updateStatus.generation)
            assertTrue(
                updateStatus.phase == DspRendererApplyPhase.PREPARING ||
                    updateStatus.phase == DspRendererApplyPhase.DEFERRED,
            )
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            awaitApplyStatus(provider, DspRendererApplyPhase.DEFERRED, generation = 1L)
            assertEquals(
                "DSP latency change is deferred until the next AudioTrack generation",
                provider.rendererApplyStatus()?.reason,
            )
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 1_000),
                offset = 0,
                length = 64 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 20L,
            )

            assertTrue(renderer.dspPipeline === activePipeline)
            assertEquals(0, renderer.trackLatencyForTesting)
            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(1L, renderer.deferredDspGenerationForTesting)
            assertEquals(DspRendererApplyPhase.DEFERRED, provider.rendererApplyStatus()?.phase)

            renderer.beginTrackGenerationForTesting()
            renderer.processDecodedPcmForTesting(
                pcm16(frames = 64, channels = 1, sample = 1_000),
                offset = 0,
                length = 64 * Short.SIZE_BYTES,
                decoderEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
                sourceSample = 30L,
            )

            assertEquals(1L, renderer.dspGenerationForTesting)
            assertEquals(128, renderer.trackLatencyForTesting)
            assertTrue(renderer.effectiveConvolverEnabledForTesting)
            assertNull(renderer.deferredDspGenerationForTesting)
            assertEquals(
                DspRendererApplyStatus(
                    1L,
                    DspRendererApplyPhase.ACTIVE,
                    activeGeneration = 1L,
                    rendererOwnerToken = 1L,
                ),
                provider.rendererApplyStatus(),
            )
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun mismatchedPreparedPipelineFormatReasonReachesRendererApplyStatus() {
        val initialConfig = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initialConfig)
        val preparationCount = AtomicInteger()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { audioFormat, _ ->
                val preparedFormat = if (preparationCount.getAndIncrement() == 0) {
                    audioFormat
                } else {
                    DspAudioFormat(audioFormat.sampleRate / 2, audioFormat.channels)
                }
                pipeline(preparedFormat, latencyFrames = 0, gain = 1.0f)
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 1.0))
            awaitApplyStatus(provider, DspRendererApplyPhase.DEFERRED, generation = 1L)

            assertEquals(
                "prepared DSP pipeline format does not match the active PCM format",
                provider.rendererApplyStatus()?.reason,
            )
            assertEquals(0L, renderer.dspGenerationForTesting)
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun nonMediaRendererDoesNotReportDspApplicationStatus() {
        val initialConfig = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initialConfig)
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1, audioType = "telephony"),
            initialConfig = initialConfig,
            provider = provider,
            pipelinePreparer = { _, _ -> error("non-MEDIA streams must not create a full DSP graph") },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            assertNull(renderer.dspPipeline)
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 3.0))

            assertEquals(
                DspRendererApplyStatus(1L, DspRendererApplyPhase.REQUESTED),
                provider.rendererApplyStatus(),
            )
        } finally {
            renderer.releaseForTesting()
        }
    }

    @Test
    fun failedLiveGraphPreparationIsReportedWithoutClaimingTheNewGenerationIsActive() {
        val ir = DspImpulseResponse("room_failed", 48_000, 1, floatArrayOf(1.0f))
        val initialConfig = DspRuntimeConfig(enabled = true)
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<Unit>()
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.LPCM, channels = 1),
            initialConfig = initialConfig,
            provider = provider,
            onPrepared = { prepared.offer(Unit) },
            pipelinePreparer = { audioFormat, config ->
                if (config.convolver.enabled) null else pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
            },
        )

        try {
            renderer.prepareLpcmPipelineForTesting()
            provider.update(convolverConfig(ir))
            assertTrue(prepared.poll(5, TimeUnit.SECONDS) != null)
            awaitApplyStatus(provider, DspRendererApplyPhase.FAILED, generation = 1L)

            assertEquals(0L, renderer.dspGenerationForTesting)
            assertEquals(
                DspRendererApplyStatus(
                    1L,
                    DspRendererApplyPhase.FAILED,
                    activeGeneration = 0L,
                    rendererOwnerToken = 1L,
                ),
                provider.rendererApplyStatus(),
            )
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
    fun staleFormatCandidateIsClosedWhenRendererFormatReturnsToItsPreviousValue() {
        val enteredOldPublish = CountDownLatch(1)
        val releaseOldPublish = CountDownLatch(1)
        val oldCandidateClosed = CountDownLatch(1)
        val oldCandidateCloseCount = AtomicInteger()
        val mono44100Preparations = AtomicInteger()
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
                        if (mono44100Preparations.incrementAndGet() == 1) {
                            processor.onClose = {
                                oldCandidateCloseCount.incrementAndGet()
                                oldCandidateClosed.countDown()
                            }
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
            renderer.prepareDecoderPcmFormatForTesting(44_100, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            releaseOldPublish.countDown()

            assertTrue(oldCandidateClosed.await(5, TimeUnit.SECONDS))
            assertEquals(1, oldCandidateCloseCount.get())
            assertEquals(DspAudioFormat(44_100, 1), renderer.dspPipeline?.format)
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
        } finally {
            releaseOldPublish.countDown()
            renderer.releaseForTesting()
        }
    }

    @Test
    fun preparedPublicationIsLinearizedWithAFormatChangeAfterTheOwnerCheck() {
        val enteredOwnerCheck = CountDownLatch(1)
        val releaseOwnerCheck = CountDownLatch(1)
        val formatChangeFinished = CountDownLatch(1)
        val oldCandidateClosed = CountDownLatch(1)
        val oldCandidateCloseCount = AtomicInteger()
        val mono44100Preparations = AtomicInteger()
        val provider = MutableConfigProvider(DspRuntimeConfig.disabled())
        val renderer = renderer(
            format = audioFormat(AudioCodecKind.AAC_LC, channels = 1),
            initialConfig = DspRuntimeConfig.disabled(),
            provider = provider,
            afterDspUpdateOwnerCheck = {
                enteredOwnerCheck.countDown()
                check(releaseOwnerCheck.await(5, TimeUnit.SECONDS))
            },
            pipelinePreparer = { audioFormat, _ ->
                if (audioFormat.sampleRate == 44_100) {
                    val processor = StateProcessor(audioFormat, 0, 1.0f, onReset = {})
                    DspPcmPipeline(audioFormat, processor, forceInitialLatencySilence = false).also {
                        if (mono44100Preparations.incrementAndGet() == 1) {
                            processor.onClose = {
                                oldCandidateCloseCount.incrementAndGet()
                                oldCandidateClosed.countDown()
                            }
                        }
                    }
                } else {
                    pipeline(audioFormat, latencyFrames = 0, gain = 1.0f)
                }
            },
        )
        val formatChange = Thread {
            renderer.prepareDecoderPcmFormatForTesting(48_000, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            formatChangeFinished.countDown()
        }.apply { isDaemon = true }

        try {
            renderer.prepareDecoderPcmFormatForTesting(44_100, 1, AndroidAudioFormat.ENCODING_PCM_16BIT)
            provider.update(DspRuntimeConfig(enabled = true, gainDb = 2.0))
            assertTrue(enteredOwnerCheck.await(5, TimeUnit.SECONDS))

            formatChange.start()
            assertFalse(formatChangeFinished.await(100, TimeUnit.MILLISECONDS))
            releaseOwnerCheck.countDown()

            assertTrue(formatChangeFinished.await(5, TimeUnit.SECONDS))
            assertTrue(oldCandidateClosed.await(5, TimeUnit.SECONDS))
            assertEquals(1, oldCandidateCloseCount.get())
            assertFalse(renderer.hasPreparedDspUpdateForTesting)
        } finally {
            releaseOwnerCheck.countDown()
            formatChange.join(1_000)
            renderer.releaseForTesting()
        }
    }

    private fun renderer(
        format: AudioFormat,
        initialConfig: DspRuntimeConfig,
        provider: MutableConfigProvider = MutableConfigProvider(initialConfig),
        writeAdapter: AudioTrackWriteAdapterForTest = AudioTrackWriteAdapterForTest { _, _, length, _ -> length },
        beforeDspUpdatePublish: () -> Unit = {},
        afterDspUpdateOwnerCheck: () -> Unit = {},
        onPrepared: () -> Unit = {},
        rendererOwnerToken: Long = 1L,
        pipelinePreparer: DspPipelinePreparer,
    ): AudioRenderer {
        val classification = AudioStreamClassifier.classify(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationRoute = NavigationAudioRoute.FULL_BAND,
        )
        val renderer = AudioRenderer(
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
            rendererOwnerToken = rendererOwnerToken,
        )
        renderer.afterDspUpdateOwnerCheckForTest = afterDspUpdateOwnerCheck
        return renderer
    }

    private fun audioFormat(
        codec: AudioCodecKind,
        channels: Int,
        audioType: String = "media",
        sampleRate: Int = 48_000,
    ): AudioFormat = AudioFormat(
        codec = codec,
        sampleRate = sampleRate,
        channels = channels,
        payloadType = if (codec == AudioCodecKind.LPCM) 100 else 96,
        audioType = audioType,
    )

    private fun DspRuntimeConfig.withPeqBandsForTest(bands: List<DspEqBand>): DspRuntimeConfig = DspRuntimeConfig(
        enabled = enabled,
        gainDb = gainDb,
        peqBands = bands,
        autoHeadroomEnabled = autoHeadroomEnabled,
        autoHeadroomMarginDb = autoHeadroomMarginDb,
        bass = bass,
        compressor = compressor,
        safetyLimiter = safetyLimiter,
        stereoWidth = stereoWidth,
        monoBass = monoBass,
        convolver = convolver,
        multiband = multiband,
        dynamicEq = dynamicEq,
        seatAlignment = seatAlignment,
        advancedMs = advancedMs,
        cabinCorrection = cabinCorrection,
        harmonic = harmonic,
        macroTone = macroTone,
        smartLoudness = smartLoudness,
        dynamicBass = dynamicBass,
    )

    private fun convolverConfig(
        ir: DspImpulseResponse,
        gainDb: Double = 0.0,
        stereoWidth: Double = 1.0,
    ): DspRuntimeConfig = DspRuntimeConfig(
        enabled = true,
        gainDb = gainDb,
        stereoWidth = stereoWidth,
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

    private inner class TrackingProcessor(
        override val format: DspAudioFormat,
        private val onProcess: () -> Unit,
        private val onReset: () -> Unit,
    ) : DspProcessor {
        override val latencyFrames = 0
        val operations = CopyOnWriteArrayList<Pair<String, String>>()
        private val activeCalls = AtomicInteger()
        @Volatile var overlapped = false
            private set

        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
            if (activeCalls.incrementAndGet() != 1) overlapped = true
            try {
                operations += "process" to Thread.currentThread().name
                repeat(frames * format.channels) { output.putFloat(input.getFloat()) }
                onProcess()
                return DspProcessResult().success(frames, latencyFrames)
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override fun reset() {
            if (activeCalls.incrementAndGet() != 1) overlapped = true
            try {
                operations += "reset" to Thread.currentThread().name
                onReset()
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

        override fun close() = Unit
    }

    private fun readSample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()

    private fun writeSample(bytes: ByteArray, frame: Int, channel: Int, channels: Int, sample: Int) {
        val offset = (frame * channels + channel) * Short.SIZE_BYTES
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }

    private fun readLastSample(bytes: ByteArray): Int = readSample(bytes, bytes.size - Short.SIZE_BYTES)

    private fun awaitApplyStatus(
        provider: MutableConfigProvider,
        phase: DspRendererApplyPhase,
        generation: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val status = provider.rendererApplyStatus()
            if (status?.generation == generation && status.phase == phase) return
            Thread.sleep(5)
        }
        assertEquals(phase, provider.rendererApplyStatus()?.phase)
    }

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

    private class HotProcessor(
        override val format: DspAudioFormat,
        override val latencyFrames: Int = 0,
    ) : DspProcessor {
        private val result = DspProcessResult()
        val failProcessing = AtomicBoolean()
        var rejectNextHotUpdate = false
        var hotUpdateEnqueueAttempts = 0
        var localPeqWarmEnqueueAttempts = 0
        var rejectLocalPeqWarmTransitionCount = 0
        var suppressLocalPeqWarmAcknowledgement = false
        val localPeqWarmEnqueuedGenerations = CopyOnWriteArrayList<Long>()
        val localPeqWarmPreparedHistoryEnds = CopyOnWriteArrayList<Long>()
        val localPeqWarmCancelledGenerations = CopyOnWriteArrayList<Long>()
        var suppressAcknowledgement = false
        private var hotContextToken = 0
        private var queuedGenerationToken = 0
        private var acknowledgedGenerationToken = 0
        private var queuedLocalPeqToken = 0
        private var acknowledgedLocalPeqToken = 0
        private var rejectedLocalPeqToken = 0
        private var localPeqHistoryEndSequence = 0L
        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
            if (failProcessing.get()) {
                return result.failure(frames, latencyFrames, DspBypassReason.NATIVE_FAILURE, errorCode = 1)
            }
            val start = input.position()
            repeat(frames * format.channels) { sample ->
                output.putFloat(input.getFloat(start + sample * Float.SIZE_BYTES))
            }
            input.position(start + frames * format.channels * Float.SIZE_BYTES)
            if (!suppressAcknowledgement) acknowledgedGenerationToken = queuedGenerationToken
            if (!suppressLocalPeqWarmAcknowledgement && queuedLocalPeqToken != 0) {
                if (rejectLocalPeqWarmTransitionCount > 0) {
                    rejectLocalPeqWarmTransitionCount--
                    rejectedLocalPeqToken = queuedLocalPeqToken
                } else {
                    acknowledgedLocalPeqToken = queuedLocalPeqToken
                }
                queuedLocalPeqToken = 0
            }
            return result.success(frames, latencyFrames)
        }

        override fun reset() = Unit

        override fun enqueueHotUpdate(update: DspPreparedHotUpdate): Boolean {
            hotUpdateEnqueueAttempts++
            if (!update.mediaRole || update.hotContextToken != hotContextToken) return false
            if (rejectNextHotUpdate) {
                rejectNextHotUpdate = false
                return false
            }
            queuedGenerationToken = update.generationToken
            return true
        }

        override fun prepareLocalPeqWarmUpdate(
            coefficients: DoubleArray,
            generation: Long,
            editorOwnerEpoch: Long,
            rendererOwnerToken: Long,
            pcmFormatEpoch: Long,
            hotContextToken: Int,
            mediaRole: Boolean,
        ): DspPreparedLocalPeqWarmUpdate? {
            if (!mediaRole || hotContextToken != this.hotContextToken || coefficients.size % 5 != 0) return null
            val bandCount = coefficients.size / 5
            val valuesPerBand = DspPreparedLocalPeqWarmUpdate.VALUES_PER_BAND
            val preparedValues = DoubleArray(bandCount * valuesPerBand)
            val historyEndSequence = ++localPeqHistoryEndSequence
            localPeqWarmPreparedHistoryEnds.add(historyEndSequence)
            repeat(bandCount) { band ->
                coefficients.copyInto(
                    preparedValues,
                    destinationOffset = band * valuesPerBand,
                    startIndex = band * 5,
                    endIndex = (band + 1) * 5,
                )
            }
            return DspPreparedLocalPeqWarmUpdate(
                generation = generation,
                editorOwnerEpoch = editorOwnerEpoch,
                rendererOwnerToken = rendererOwnerToken,
                pcmFormatEpoch = pcmFormatEpoch,
                hotContextToken = hotContextToken,
                mediaRole = mediaRole,
                bandCount = bandCount,
                historyEpochSequence = 0L,
                historyEndSequence = historyEndSequence,
                preparedValues = preparedValues,
            )
        }

        override fun enqueueLocalPeqWarmUpdate(update: DspPreparedLocalPeqWarmUpdate): Int {
            localPeqWarmEnqueueAttempts++
            if (!update.mediaRole || update.hotContextToken != hotContextToken) {
                rejectedLocalPeqToken = update.generationToken
                return 7
            }
            localPeqWarmEnqueuedGenerations.add(update.generation)
            rejectedLocalPeqToken = 0
            queuedLocalPeqToken = update.generationToken
            return 0
        }

        override fun cancelLocalPeqWarmUpdate(update: DspPreparedLocalPeqWarmUpdate): Int {
            localPeqWarmCancelledGenerations.add(update.generation)
            if (queuedLocalPeqToken == update.generationToken) queuedLocalPeqToken = 0
            rejectedLocalPeqToken = update.generationToken
            return 0
        }

        override fun bindHotUpdateContext(
            editorOwnerEpoch: Long,
            rendererOwnerToken: Long,
            pcmFormatEpoch: Long,
            hotContextToken: Int,
            mediaRole: Boolean,
        ): Boolean {
            if (!mediaRole || hotContextToken <= 0) return false
            this.hotContextToken = hotContextToken
            return true
        }

        override fun invalidateHotUpdateContext() {
            hotContextToken = 0
        }

        override fun hotUpdateContextToken(): Int = hotContextToken

        override fun hotAcknowledgedToken(): Int = acknowledgedGenerationToken

        override fun localPeqWarmAcknowledgedToken(): Int = acknowledgedLocalPeqToken

        override fun localPeqWarmRejectedToken(): Int = rejectedLocalPeqToken

        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

        override fun close() = Unit
    }

    private class MutableConfigProvider(
        initial: DspRuntimeConfig,
        localWarmEnabled: Boolean = false,
    ) : DspConfigProvider {
        private val current = AtomicReference(DspConfigSnapshot(0L, initial))
        private val applyStatus = AtomicReference<DspRendererApplyStatus?>(null)
        private val applyStatusOwner = AtomicReference<Long?>(null)
        private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()
        private val hotUpdatesEnabled = AtomicBoolean(true)
        private val localWarmUpdatesEnabled = AtomicBoolean(localWarmEnabled)

        override fun snapshot(): DspRuntimeConfig = current.get().config

        override fun studioHotUpdatesEnabled(): Boolean = hotUpdatesEnabled.get()

        override fun studioLocalWarmEnabled(): Boolean = localWarmUpdatesEnabled.get()

        override fun versionedSnapshot(): DspConfigSnapshot = current.get()

        override fun rendererApplyStatus(): DspRendererApplyStatus? = applyStatus.get()

        override fun claimRendererApplyStatusOwner(ownerToken: Long) {
            applyStatusOwner.set(ownerToken)
        }

        override fun reportRendererApplyStatus(status: DspRendererApplyStatus) {
            if (current.get().generation != status.generation || applyStatusOwner.get() != status.rendererOwnerToken) return
            while (true) {
                val previous = applyStatus.get()
                if (previous?.generation == status.generation && !canAdvance(previous.phase, status.phase)) return
                if (applyStatus.compareAndSet(previous, status)) return
            }
        }

        override fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable {
            listeners.add(listener)
            listener(current.get())
            return Closeable { listeners.remove(listener) }
        }

        fun update(config: DspRuntimeConfig) {
            val next = current.updateAndGet { DspConfigSnapshot(it.generation + 1L, config) }
            val previous = applyStatus.get()
            val activeGeneration = previous?.activeGeneration ?: previous?.generation?.takeIf {
                previous.phase == DspRendererApplyPhase.ACTIVE
            }
            applyStatus.set(
                DspRendererApplyStatus(
                    next.generation,
                    DspRendererApplyPhase.REQUESTED,
                    activeGeneration,
                    rendererOwnerToken = applyStatusOwner.get(),
                ),
            )
            listeners.forEach { it(next) }
        }

        fun setStudioHotUpdatesEnabled(enabled: Boolean) {
            hotUpdatesEnabled.set(enabled)
        }

        fun setStudioLocalWarmEnabled(enabled: Boolean) {
            localWarmUpdatesEnabled.set(enabled)
        }

        private fun canAdvance(previous: DspRendererApplyPhase, next: DspRendererApplyPhase): Boolean = when (previous) {
            DspRendererApplyPhase.REQUESTED -> true
            DspRendererApplyPhase.PREPARING -> next == DspRendererApplyPhase.PREPARED ||
                next == DspRendererApplyPhase.DEFERRED || next == DspRendererApplyPhase.ACTIVE ||
                next == DspRendererApplyPhase.FAILED
            DspRendererApplyPhase.PREPARED -> next == DspRendererApplyPhase.DEFERRED ||
                next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
            DspRendererApplyPhase.DEFERRED -> next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
            DspRendererApplyPhase.ACTIVE -> next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
            DspRendererApplyPhase.FAILED -> next == DspRendererApplyPhase.ACTIVE
        }
    }
}
