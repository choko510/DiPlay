package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioOwnerToken
import com.shilapi.xcertplay.airplay.AudioPlaybackClock
import com.shilapi.xcertplay.airplay.AudioPlaybackClockMapper
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.RtpSampleTimestampMapper
import com.shilapi.xcertplay.airplay.Unsigned32FrameTracker
import com.shilapi.xcertplay.airplay.framesToNanos
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspConfigSnapshot
import com.shilapi.xcertplay.media.dsp.DspAudioFormat
import com.shilapi.xcertplay.media.dsp.DspBufferSizing
import com.shilapi.xcertplay.media.dsp.DspLiveUpdateController
import com.shilapi.xcertplay.media.dsp.DspLatencyPreservingBypass
import com.shilapi.xcertplay.media.dsp.DspPcmCrossfade
import com.shilapi.xcertplay.media.dsp.DspPcmEncoding
import com.shilapi.xcertplay.media.dsp.DspPcmConverter
import com.shilapi.xcertplay.media.dsp.DspPcmPipeline
import com.shilapi.xcertplay.media.dsp.DspTpdfDither
import com.shilapi.xcertplay.media.dsp.DspPreparedUpdate
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.media.dsp.DspRendererApplyWarning
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspStreamRole
import com.shilapi.xcertplay.media.dsp.DspStreamPolicy
import com.shilapi.xcertplay.media.dsp.DspUpdatePlan
import com.shilapi.xcertplay.media.dsp.DspUpdatePlanner
import com.shilapi.xcertplay.media.dsp.DspPipelinePreparer
import com.shilapi.xcertplay.media.dsp.DspSeatAlignmentConfig
import com.shilapi.xcertplay.media.dsp.failureFallbackPreampDb
import com.shilapi.xcertplay.media.dsp.createNativePipeline
import com.shilapi.xcertplay.media.dsp.prepare
import com.shilapi.xcertplay.media.dsp.prepareHotUpdate
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

private const val VIDEO_RECOVERY_WORKER_COUNT = 2
private const val VIDEO_RECOVERY_QUEUE_CAPACITY = 16
private val playbackRouteOwnerTokens = AtomicLong()

internal fun interface AudioTrackWriteAdapterForTest {
    fun write(data: ByteArray, offset: Int, length: Int, writeMode: Int): Int
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val dspConfigProvider: DspConfigProvider,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val navigationAudioRoute: NavigationAudioRoute = NavigationAudioRoute.FULL_BAND,
    private val transport: String = "wired",
    private val audioManager: AudioManager? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    onVideoFrameSubmittedToSurface: ((Int) -> Unit)? = null,
    private val onPlaybackRouteChanged: ((Long, Int?, String?, Boolean) -> Unit)? = null,
) : MediaSink {
    constructor(
        surface: Surface? = null,
        videoWidth: Int = 1280,
        videoHeight: Int = 720,
        preferSoftwareHevcDecoder: Boolean = false,
        advancedAudioChannelMapping: Boolean = false,
        navigationAudioRoute: NavigationAudioRoute = NavigationAudioRoute.FULL_BAND,
        transport: String = "wired",
        audioManager: AudioManager? = null,
        onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
        mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
        onAudioDiagnostic: (String) -> Unit = {},
        onPlaybackRouteChanged: ((Long, Int?, String?, Boolean) -> Unit)? = null,
    ) : this(
        dspConfigProvider = DspConfigProvider { DspRuntimeConfig.disabled() },
        surface = surface,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = preferSoftwareHevcDecoder,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        navigationAudioRoute = navigationAudioRoute,
        transport = transport,
        audioManager = audioManager,
        onScreenStreamActiveChanged = onScreenStreamActiveChanged,
        mediaBufferMillis = mediaBufferMillis,
        onAudioDiagnostic = onAudioDiagnostic,
        onPlaybackRouteChanged = onPlaybackRouteChanged,
    )
    private data class VideoRecoveryHandler(val generation: Long, val callback: () -> Unit)
    private class PendingVideoRecovery(val generation: Long)
    private data class PendingVideoConfig(val generation: Long, val codec: VideoCodec, val codecData: ByteArray)
    private data class AudioRendererEntry(val token: AudioOwnerToken, val renderer: AudioRenderer)
    private data class MicrophoneEntry(val token: AudioOwnerToken, val uplink: MicrophoneUplinkController)

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val videoStreamGenerations = VideoStreamGenerations()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    @Volatile private var videoFrameSubmittedToSurface = onVideoFrameSubmittedToSurface
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val surfaceUnavailableTypes = ConcurrentHashMap.newKeySet<Int>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val retiringVideoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val videoDecoderLocks = ConcurrentHashMap<Int, Any>()
    private val pendingVideoConfigs = ConcurrentHashMap<Int, PendingVideoConfig>()
    private val audioTypeLocks = ConcurrentHashMap<Int, Any>()
    private val activeAudioOwners = ConcurrentHashMap<Int, AudioOwnerToken>()
    private val nextAudioRendererOwnerToken = AtomicLong()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRendererEntry>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneEntry>()
    private val closed = AtomicBoolean(false)
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, VideoRecoveryHandler>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = ConcurrentHashMap<Int, PendingVideoRecovery>()
    private val recoveryWorkerIds = AtomicInteger()
    private val recoveryQueueDrops = AtomicLong()
    private val recoveryExecutor = ThreadPoolExecutor(
        VIDEO_RECOVERY_WORKER_COUNT,
        VIDEO_RECOVERY_WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(VIDEO_RECOVERY_QUEUE_CAPACITY),
        { task -> Thread(task, "carplay-video-recovery-${recoveryWorkerIds.incrementAndGet()}").apply { isDaemon = true } },
    )
    internal var audioRendererThreadFactoryForTest: ((Runnable, String) -> Thread)? = null
    internal var dspPipelinePreparerForTest: DspPipelinePreparer? = null
    internal var audioTrackWriteAdapterForTest: AudioTrackWriteAdapterForTest? = null
    internal var beforeDspUpdatePublishForTest: (() -> Unit)? = null
    internal var afterDspUpdateOwnerCheckForTest: (() -> Unit)? = null
    internal var onDspUpdatePreparedForTest: (() -> Unit)? = null
    internal var microphoneUplinkFactoryForTest:
        ((MicrophoneConfig, () -> Boolean) -> MicrophoneUplinkController)? = null
    internal var beforeVideoDecoderStopForTest: (() -> Unit)? = null
    internal var videoCodecReleaseForTest: ((MediaCodec) -> Unit)? = null

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
            videoRecoveryHandlers[type] = VideoRecoveryHandler(videoStreamGenerations.current(type), handler)
        }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int, generation: Long) {
        if (closed.get() || !videoStreamGenerations.isCurrent(type, generation)) return
        val pending = PendingVideoRecovery(generation)
        while (true) {
            val current = recoveryPending[type]
            when {
                current == null -> if (recoveryPending.putIfAbsent(type, pending) == null) break
                current.generation >= generation -> return
                recoveryPending.replace(type, current, pending) -> break
            }
        }
        try {
            recoveryExecutor.execute {
                try {
                    val callback = synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
                        val handler = videoRecoveryHandlers[type]
                        if (!closed.get() && videoStreamGenerations.isCurrent(type, generation) &&
                            handler?.generation == generation
                        ) {
                            handler.callback
                        } else null
                    }
                    callback?.invoke()
                } catch (error: Exception) {
                    Log.w("xcertplay-usb", "Video keyframe request failed error=${error.javaClass.simpleName}")
                }
                finally { recoveryPending.remove(type, pending) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recoveryQueueDrops.incrementAndGet()
            recoveryPending.remove(type, pending)
        }
    }

    internal fun videoStreamGenerationForTest(type: Int): Long = videoStreamGenerations.current(type)
    internal fun videoRecoveryQueueDepthForTest(): Int = recoveryExecutor.queue.size
    internal fun videoRecoveryQueueDropsForTest(): Long = recoveryQueueDrops.get()

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
            surfaceUnavailableTypes.remove(type)
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface, onDetached: () -> Unit = {}) {
        val (active, retiring, nextSurface) = synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
            val currentSurface = surfaces[type]
            val removed = surfaces.remove(type, surface)
            val clearedDefault = currentSurface == null && defaultSurface === surface &&
                type !in surfaceUnavailableTypes
            if (removed || clearedDefault) surfaceUnavailableTypes.add(type)
            val nextSurface = surfaces[type] ?: if (type in surfaceUnavailableTypes) null else defaultSurface
            val active = videoDecoders[type]
            val retiring = retiringVideoDecoders[type]?.takeIf { it !== active }
            Triple(active, retiring, nextSurface)
        }

        val waitingFor = (if (active == null) 0 else 1) + (if (retiring == null) 0 else 1)
        if (waitingFor == 0) {
            onDetached()
            return
        }
        val remaining = AtomicInteger(waitingFor)
        val complete = { if (remaining.decrementAndGet() == 0) onDetached() }
        active?.setSurface(nextSurface, listOf(VideoSurfaceRetirement(surface, complete)))
        retiring?.afterStopped { safelyReleased -> if (safelyReleased) complete() }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    fun setVideoFrameSubmittedToSurfaceListener(listener: ((Int) -> Unit)?) {
        videoFrameSubmittedToSurface = listener
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        val generation = videoStreamGenerations.current(type)
        val savedConfig = PendingVideoConfig(generation, codec, codecData.copyOf())
        pendingVideoConfigs[type] = savedConfig
        videoDecoder(type, restorePendingConfig = false)?.configure(codec, savedConfig.codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type)?.submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
            videoStreamGenerations.advance(type)
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                pendingVideoConfigs.remove(type)
                pendingVideoCodec.remove(type)
                videoDecoders.remove(type)?.let { decoder ->
                    retiringVideoDecoders[type] = decoder
                    decoder.close()
                }
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun claimAudioOwner(token: AudioOwnerToken) {
        if (closed.get()) return
        activeAudioOwners[token.type] = token
        if (closed.get()) activeAudioOwners.remove(token.type, token)
    }

    override fun releaseAudioOwner(token: AudioOwnerToken) {
        activeAudioOwners.remove(token.type, token)
    }

    override fun onAudioStarted(token: AudioOwnerToken, format: AudioFormat, firstSample: Int) {
        if (closed.get()) return
        val retired = mutableListOf<AudioRenderer>()
        var startFailure: Exception? = null
        synchronized(audioTypeLock(token.type)) {
            if (closed.get() || activeAudioOwners[token.type] != token) return
            val existing = audioRenderers[token.type]
            if (existing?.token == token && existing.renderer.format == format) {
                try {
                    if (existing.renderer.start()) return
                } catch (error: Exception) {
                    audioRenderers.remove(token.type, existing)
                    retired.add(existing.renderer)
                    Log.w(
                        "xcertplay-usb",
                        "Audio renderer restart failed type=${token.type} error=${error.javaClass.simpleName}",
                    )
                    startFailure = error
                    return@synchronized
                }
            }
            val renderer = createAudioRenderer(format)
            val entry = AudioRendererEntry(token, renderer)
            val previous = audioRenderers.put(token.type, entry)
            try {
                check(renderer.start()) { "audio renderer closed before worker start" }
            } catch (error: Exception) {
                audioRenderers.remove(token.type, entry)
                retired.add(renderer)
                previous?.let { retired.add(it.renderer) }
                Log.w(
                    "xcertplay-usb",
                    "Audio renderer start failed type=${token.type} error=${error.javaClass.simpleName}",
                )
                startFailure = error
                return@synchronized
            }
            if (activeAudioOwners[token.type] != token) {
                audioRenderers.remove(token.type, entry)
                retired.add(renderer)
            }
            previous?.let { retired.add(it.renderer) }
        }
        retired.forEach(AudioRenderer::close)
        startFailure?.let { throw it }
    }

    override fun onAudioRtp(token: AudioOwnerToken, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (closed.get() || activeAudioOwners[token.type] != token) return
        val entry = audioRenderers[token.type] ?: return
        if (entry.token == token && entry.renderer.format == format) entry.renderer.submit(rtp, sample)
    }

    override fun audioPlaybackClock(token: AudioOwnerToken): AudioPlaybackClock? {
        if (closed.get() || activeAudioOwners[token.type] != token) return null
        val entry = audioRenderers[token.type] ?: return null
        return if (entry.token == token) entry.renderer.playbackClock() else null
    }

    override fun onAudioStopped(token: AudioOwnerToken) {
        val lock = audioTypeLock(token.type)
        var renderer: AudioRenderer? = null
        var microphone: MicrophoneUplinkController? = null
        synchronized(lock) {
            audioRenderers[token.type]?.takeIf { it.token == token }?.let { entry ->
                if (audioRenderers.remove(token.type, entry)) renderer = entry.renderer
            }
            microphoneUplinks[token.type]?.takeIf { it.token == token }?.let { entry ->
                if (microphoneUplinks.remove(token.type, entry)) microphone = entry.uplink
            }
        }
        renderer?.close()
        microphone?.close()
    }

    override fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean {
        if (closed.get()) return false
        val lock = audioTypeLock(token.type)
        lateinit var entry: MicrophoneEntry
        val retired = mutableListOf<MicrophoneUplinkController>()
        synchronized(lock) {
            if (closed.get() || activeAudioOwners[token.type] != token) return false
            val current = microphoneUplinks[token.type]
            if (current?.token == token) {
                if (current.uplink.isActive) return true
                if (microphoneUplinks.remove(token.type, current)) retired.add(current.uplink)
            }
            val isCurrentOwner = {
                !closed.get() && activeAudioOwners[token.type] == token
            }
            val uplink = microphoneUplinkFactoryForTest?.invoke(config, isCurrentOwner)
                ?: MicrophoneUplink(config, isCurrentOwner)
            entry = MicrophoneEntry(token, uplink)
            microphoneUplinks.put(token.type, entry)?.let { retired.add(it.uplink) }
        }
        retired.forEach(MicrophoneUplinkController::close)
        if (!entry.uplink.start()) {
            synchronized(lock) { microphoneUplinks.remove(token.type, entry) }
            entry.uplink.close()
            return false
        }
        val activated = synchronized(lock) {
            if (!closed.get() && activeAudioOwners[token.type] == token && microphoneUplinks[token.type] === entry) {
                entry.uplink.activate()
                true
            } else {
                microphoneUplinks.remove(token.type, entry)
                false
            }
        }
        if (!activated) entry.uplink.close()
        return activated
    }

    override fun isMicrophoneActive(token: AudioOwnerToken): Boolean {
        if (closed.get() || activeAudioOwners[token.type] != token) return false
        val entry = microphoneUplinks[token.type] ?: return false
        return entry.token == token && entry.uplink.isActive
    }

    override fun onMicrophoneStopped(token: AudioOwnerToken) {
        val lock = audioTypeLock(token.type)
        val microphone = synchronized(lock) {
            microphoneUplinks[token.type]?.takeIf { it.token == token }?.let { entry ->
                if (microphoneUplinks.remove(token.type, entry)) entry.uplink else null
            }
        }
        microphone?.close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        (videoDecoders.values + retiringVideoDecoders.values).distinct().forEach(VideoDecoder::close)
        videoDecoders.clear()
        retiringVideoDecoders.clear()
        pendingVideoConfigs.clear()
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        recoveryPending.clear()
        val types = (audioTypeLocks.keys + activeAudioOwners.keys + audioRenderers.keys + microphoneUplinks.keys).toSet()
        val renderers = mutableListOf<AudioRenderer>()
        val microphones = mutableListOf<MicrophoneUplinkController>()
        types.forEach { type ->
            synchronized(audioTypeLock(type)) {
                activeAudioOwners.remove(type)
                audioRenderers.remove(type)?.let { renderers.add(it.renderer) }
                microphoneUplinks.remove(type)?.let { microphones.add(it.uplink) }
            }
        }
        renderers.forEach(AudioRenderer::close)
        microphones.forEach(MicrophoneUplinkController::close)
    }

    private fun videoDecoder(type: Int): VideoDecoder? = videoDecoder(type, restorePendingConfig = true)

    private fun releaseVideoCodec(codec: MediaCodec) {
        val releaseForTest = videoCodecReleaseForTest
        if (releaseForTest == null) codec.release() else releaseForTest(codec)
    }

    private fun videoDecoder(type: Int, restorePendingConfig: Boolean): VideoDecoder? =
        synchronized(videoDecoderLocks.computeIfAbsent(type) { Any() }) {
            if (closed.get()) return@synchronized null
            videoDecoders[type]?.let { return@synchronized it }
            retiringVideoDecoders[type]?.let { retiring ->
                if (!retiring.isStopped() || !retiring.surfaceReleaseSafe) return@synchronized null
                retiringVideoDecoders.remove(type, retiring)
            }
            val generation = videoStreamGenerations.current(type)
            val decoderReference = AtomicReference<VideoDecoder?>()
            val diagnosticHandler = videoDiagnosticHandlers[type]
            val decoder = VideoDecoder(
                surfaces[type] ?: if (type in surfaceUnavailableTypes) null else defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { requestVideoRecovery(type, generation) },
                report = { diagnosticHandler?.invoke(it) },
                beforeStop = { beforeVideoDecoderStopForTest?.invoke() },
                releaseCodec = ::releaseVideoCodec,
                onFirstFrameSubmittedToSurface = {
                    val currentDecoder = decoderReference.get()
                    if (!closed.get() && videoStreamGenerations.isCurrent(type, generation) &&
                        currentDecoder != null && videoDecoders[type] === currentDecoder
                    ) {
                        videoFrameSubmittedToSurface?.invoke(type)
                    }
                },
                onStopped = { safelyReleased ->
                    if (safelyReleased) {
                        decoderReference.get()?.let { retiringVideoDecoders.remove(type, it) }
                    } else {
                        diagnosticHandler?.invoke(
                            "decoder retirement blocked type=$type generation=$generation; " +
                                "codec and surface references retained",
                        )
                    }
                },
                streamType = type,
                streamGeneration = generation,
            )
            decoderReference.set(decoder)
            videoDecoders[type] = decoder
            decoder.start()
            if (restorePendingConfig) {
                pendingVideoConfigs[type]?.takeIf { it.generation == videoStreamGenerations.current(type) }
                    ?.let { decoder.configure(it.codec, it.codecData) }
            }
            decoder
        }

    private fun createAudioRenderer(format: AudioFormat): AudioRenderer {
        val mappingMode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val classification = AudioStreamClassifier.classify(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mappingMode = mappingMode,
            navigationRoute = navigationAudioRoute,
        )
        val configSnapshot = snapshotDspConfig()
        return AudioRenderer(
            format = format,
            classification = classification,
            dspRuntimeConfig = configSnapshot.config,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            navigationAudioRoute = navigationAudioRoute,
            transport = transport,
            audioManager = audioManager,
            mediaBufferMillis = mediaBufferMillis,
            report = onAudioDiagnostic,
            threadFactory = audioRendererThreadFactoryForTest ?: { task, name -> Thread(task, name) },
            dspConfigProvider = dspConfigProvider,
            dspConfigGeneration = configSnapshot.generation,
            dspPipelinePreparer = dspPipelinePreparerForTest ?: { targetFormat, config ->
                createNativePipeline(classification.dspRole, targetFormat, config)
            },
            audioTrackWriteAdapterForTest = audioTrackWriteAdapterForTest,
            onPlaybackRouteChanged = onPlaybackRouteChanged,
            beforeDspUpdatePublishForTest = beforeDspUpdatePublishForTest,
            afterDspUpdateOwnerCheckForTest = afterDspUpdateOwnerCheckForTest,
            onDspUpdatePreparedForTest = onDspUpdatePreparedForTest,
            rendererOwnerToken = nextAudioRendererOwnerToken.incrementAndGet().also {
                check(it > 0L) { "audio renderer owner token exhausted" }
            },
        )
    }

    private fun snapshotDspConfig(): DspConfigSnapshot = try {
        dspConfigProvider.versionedSnapshot()
    } catch (_: Exception) {
        runCatching {
            onAudioDiagnostic(
                "DSP unavailable: reason=CONFIG_SNAPSHOT_FAILED fallback=LEGACY_PCM",
            )
        }
        DspConfigSnapshot(0L, DspRuntimeConfig.disabled())
    }

    private fun audioTypeLock(type: Int): Any = audioTypeLocks.computeIfAbsent(type) { Any() }

}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val beforeStop: () -> Unit,
    private val releaseCodec: (MediaCodec) -> Unit,
    private val onFirstFrameSubmittedToSurface: () -> Unit,
    private val onStopped: (Boolean) -> Unit,
    private val streamType: Int,
    private val streamGeneration: Long,
) : Closeable {
    private val queue = VideoDecodeQueue()
    private val stopped = CountDownLatch(1)
    private val stoppedCallbackLock = Any()
    private val stoppedCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val failedDecoderReleases = mutableListOf<MediaCodec>()
    private val pendingSurfaceRetirements = mutableListOf<VideoSurfaceRetirement>()
    @Volatile private var running = true
    @Volatile var surfaceReleaseSafe: Boolean = false
        private set
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var submittedFrameToSurfaceLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private val stats = VideoStats()
    private var lastSurfaceRetirementDiagnosticNs = 0L
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        synchronized(stoppedCallbackLock) {
            if (running) queue.offer(VideoJob.Config(codec, codecData))
        }
    }

    fun submit(nalus: ByteArray) {
        synchronized(stoppedCallbackLock) {
            if (!running) return
            stats.onReceived(nalus.size)
            queue.offer(VideoJob.Frame(nalus))
        }
    }

    fun setSurface(surface: Surface?, retirements: List<VideoSurfaceRetirement> = emptyList()) {
        val runImmediately = synchronized(stoppedCallbackLock) {
            when {
                stopped.count == 0L -> surfaceReleaseSafe
                !running -> {
                    if (retirements.isNotEmpty()) {
                        stoppedCallbacks += { safe -> if (safe) retirements.forEach { it.onApplied() } }
                    }
                    false
                }
                else -> {
                    queue.offer(VideoJob.SurfaceChanged(surface, retirements))
                    false
                }
            }
        }
        if (runImmediately) retirements.forEach { it.onApplied() }
    }

    fun afterStopped(callback: (Boolean) -> Unit) {
        val completion = synchronized(stoppedCallbackLock) {
            if (stopped.count == 0L) {
                true to surfaceReleaseSafe
            } else {
                stoppedCallbacks += callback
                false to false
            }
        }
        if (completion.first) callback(completion.second)
    }

    override fun close() {
        synchronized(stoppedCallbackLock) {
            if (!running) return
            running = false
            thread.interrupt()
        }
    }

    fun awaitStopped(timeoutMillis: Long): Boolean = stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun isStopped(): Boolean = stopped.count == 0L

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> {
                            pendingSurfaceRetirements += queue.takeSurfaceRetirements()
                            if (changeSurface(job.surface)) completeRetiredSurfaceChanges()
                            reportSurfaceRetirementBacklogIfDue()
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(
                        TAG,
                        "video decoder job failed: ${job?.javaClass?.simpleName} " +
                            "error=${error.javaClass.simpleName}",
                    )
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    if (releaseDecoder()) {
                        outputSurface = null
                        completeRetiredSurfaceChanges()
                    }
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            try {
                beforeStop()
            } finally {
                try {
                    surfaceReleaseSafe = releaseDecoder()
                } finally {
                    pendingSurfaceRetirements += queue.drainSurfaceRetirements()
                    reportSurfaceRetirementBacklogIfDue()
                    if (surfaceReleaseSafe) {
                        outputSurface = null
                        lastConfig = null
                        completeRetiredSurfaceChanges()
                    }
                    val callbacks = synchronized(stoppedCallbackLock) {
                        stopped.countDown()
                        stoppedCallbacks.toList().also { stoppedCallbacks.clear() }
                    }
                    callbacks.forEach { callback -> runCatching { callback(surfaceReleaseSafe) } }
                    runCatching { onStopped(surfaceReleaseSafe) }
                }
            }
        }
    }

    private fun completeRetiredSurfaceChanges() {
        val iterator = pendingSurfaceRetirements.iterator()
        while (iterator.hasNext()) {
            val retirement = iterator.next()
            if (failedDecoderReleases.isEmpty() && outputSurface !== retirement.surface) {
                iterator.remove()
                runCatching(retirement.onApplied)
            }
        }
    }

    private fun reportSurfaceRetirementBacklogIfDue() {
        val queuedCount = queue.queuedSurfaceRetirementCount
        val pendingCount = pendingSurfaceRetirements.size
        val count = queuedCount + pendingCount
        if (count == 0) {
            lastSurfaceRetirementDiagnosticNs = 0L
            return
        }
        val now = System.nanoTime()
        if (now - lastSurfaceRetirementDiagnosticNs < SURFACE_RETIREMENT_DIAGNOSTIC_INTERVAL_NS) return
        lastSurfaceRetirementDiagnosticNs = now
        val oldestCreatedNs = pendingSurfaceRetirements.firstOrNull()?.createdNs
            ?: queue.oldestQueuedSurfaceRetirementNs()
            ?: now
        val oldestAgeMs = (now - oldestCreatedNs).coerceAtLeast(0L) / 1_000_000L
        report("surface retirement backlog count=$count oldestAgeMs=$oldestAgeMs")
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        if (!releaseDecoder()) return
        completeRetiredSurfaceChanges()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(CodecSpecificData(0, it)) } ?: emptyList()
        } else {
            MediaCodecSupport.avcCodecSpecificData(codecData)
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = videoDecoderAttemptPlan(softwareDecoderName(mime))
        val attemptedFormats = mutableSetOf<DecoderAttemptKey>()
        val next = firstSuccessfulDecoderAttempt(
            attempts = attempts,
            configure = { attempt -> tryConfigure(mime, csd, surface, attempt, attemptedFormats) },
            canContinueAfterFailure = { failedDecoderReleases.isEmpty() },
        )
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        submittedFrameToSurfaceLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private fun buildFormat(mime: String, csd: List<CodecSpecificData>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEach { data ->
                setByteBuffer("csd-${data.index}", ByteBuffer.wrap(data.bytes))
            }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<CodecSpecificData>,
        surface: Surface,
        attempt: DecoderAttempt,
        attemptedFormats: MutableSet<DecoderAttemptKey>,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (!recordDecoderAttempt(attemptedFormats, codec.name, attempt.tuned)) {
                releaseFailedCandidate(codec, "duplicate decoder candidate")
                candidate = null
                return null
            }
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            candidate?.let { releaseFailedCandidate(it, "decoder configure failure") }
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height " +
                    "error=${error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun releaseFailedCandidate(codec: MediaCodec, reason: String) {
        try {
            releaseCodec(codec)
        } catch (error: Exception) {
            failedDecoderReleases.add(codec)
            Log.e(
                TAG,
                "$reason could not release decoder error=${error.javaClass.simpleName}",
            )
            report(
                "$reason blocked fallback type=$streamType generation=$streamGeneration " +
                    "retained=${failedDecoderReleases.size} surfaceRetained=true",
            )
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(
                        TAG,
                        "software HEVC decoder unavailable name=${software.name} " +
                            "error=${error.javaClass.simpleName}",
                    )
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?): Boolean {
        if (failedDecoderReleases.isNotEmpty() && !releaseDecoder()) return false
        if (outputSurface === surface) return true
        if (surface == null) {
            outputSurface = null
            if (!releaseDecoder()) return false
            Log.i(TAG, "video decoder detached from surface")
            return true
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                outputSurface = surface
                Log.i(TAG, "video decoder output surface updated")
                return true
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "video decoder output surface update failed; reconfiguring " +
                        "error=${error.javaClass.simpleName}",
                )
            }
        }
        outputSurface = surface
        if (!releaseDecoder()) return false
        lastConfig?.let(::configureDecoder)
        return true
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val submitToSurface = outputSurface != null
                    codec.releaseOutputBuffer(index, submitToSurface)
                    if (submitToSurface) stats.onSubmittedToSurface()
                    if (submitToSurface && !submittedFrameToSurfaceLogged) {
                        submittedFrameToSurfaceLogged = true
                        onFirstFrameSubmittedToSurface()
                        report("first frame submitted to surface")
                        Log.i(TAG, "video decoder submitted first frame to surface bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder(): Boolean {
        val previouslyFailed = failedDecoderReleases.toList()
        failedDecoderReleases.clear()
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (error: Exception) {
                Log.w(TAG, "video decoder stop failed error=${error.javaClass.simpleName}")
            }
            try {
                releaseCodec(codec)
            } catch (error: Exception) {
                failedDecoderReleases.add(codec)
                Log.w(TAG, "video decoder release failed error=${error.javaClass.simpleName}")
                report(
                    "decoder release failed type=$streamType generation=$streamGeneration " +
                        "retained=${failedDecoderReleases.size} surfaceRetained=true",
                )
            }
        }
        previouslyFailed.forEach { failed ->
            try {
                releaseCodec(failed)
            } catch (error: Exception) {
                failedDecoderReleases.add(failed)
                Log.w(TAG, "retired video decoder release retry failed error=${error.javaClass.simpleName}")
            }
        }
        return failedDecoderReleases.isEmpty()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        const val SURFACE_RETIREMENT_DIAGNOSTIC_INTERVAL_NS = 5_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
internal class AudioRenderer(
    val format: AudioFormat,
    private val classification: AudioStreamClassification,
    private val dspRuntimeConfig: DspRuntimeConfig,
    private val advancedAudioChannelMapping: Boolean,
    private val navigationAudioRoute: NavigationAudioRoute,
    private val transport: String,
    private val audioManager: AudioManager?,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    threadFactory: (Runnable, String) -> Thread = { task, name -> Thread(task, name) },
    private val dspConfigProvider: DspConfigProvider = DspConfigProvider { dspRuntimeConfig },
    private val dspConfigGeneration: Long = 0L,
    private val dspPipelinePreparer: DspPipelinePreparer = { targetFormat, config ->
        createNativePipeline(classification.dspRole, targetFormat, config)
    },
    private val audioTrackWriteAdapterForTest: AudioTrackWriteAdapterForTest? = null,
    private val onPlaybackRouteChanged: ((Long, Int?, String?, Boolean) -> Unit)? = null,
    private val beforeDspUpdatePublishForTest: (() -> Unit)? = null,
    internal var afterDspUpdateOwnerCheckForTest: (() -> Unit)? = null,
    internal var afterHotApplyPendingReadForTest: (() -> Unit)? = null,
    private val onDspUpdatePreparedForTest: (() -> Unit)? = null,
    private val rendererOwnerToken: Long = 1L,
) : Closeable {
    private val playbackRouteOwnerToken = playbackRouteOwnerTokens.incrementAndGet()
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)
    private data class PreparedRendererDspUpdate(
        val update: DspPreparedUpdate,
        val graphConfig: DspRuntimeConfig,
        val latencyPreservingBypass: DspLatencyPreservingBypass?,
        val preparedStatus: DspRendererApplyStatus,
        val activeStatus: DspRendererApplyStatus,
        val deferredStatus: DspRendererApplyStatus,
        val failedStatus: DspRendererApplyStatus,
    )
    private data class PendingRendererHotApply(
        val prepared: PreparedRendererDspUpdate,
        val pipeline: DspPcmPipeline,
        val hotContextToken: Int,
    )
    private data class LiveUpdateTransition(
        val epoch: Long,
        val previousController: DspLiveUpdateController?,
        val discardedUpdate: PreparedRendererDspUpdate?,
        val disabled: Boolean,
    )
    private data class DecoderPcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val encoding: Int,
        val fallbackReason: String? = null,
    )
    private data class TimestampAnchor(val framePosition: Long, val nanoTime: Long)
    private class AudioTrackWriteException(val result: Int) :
        IllegalStateException("AudioTrack.write returned $result")
    private class AudioTrackWriteStallException(val stalledForNs: Long) :
        IllegalStateException("AudioTrack.write accepted no frames for ${stalledForNs / 1_000_000L} ms")

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    private val lifecycle = AudioRendererLifecycle()
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var trackRoutingListener: AudioRouting.OnRoutingChangedListener? = null
    private val playbackRouteListenerGate = PlaybackRouteListenerGate<AudioTrack>()
    @Volatile internal var dspPipeline: DspPcmPipeline? = null
    private val pendingDspUpdate = AtomicReference<PreparedRendererDspUpdate?>(null)
    private val pendingHotApply = AtomicReference<PendingRendererHotApply?>(null)
    private val dspUpdatePublicationLock = Any()
    private var liveUpdateController: DspLiveUpdateController? = null
    private val liveUpdateControllerEpoch = AtomicLong()
    private var pcmFormatEpoch = 0L
    private var liveUpdatePcmFormat: DspAudioFormat? = null
    @Volatile private var hotBoundPipeline: DspPcmPipeline? = null
    @Volatile private var hotBoundContextToken = 0
    private val nextHotContextToken = AtomicInteger()
    @Volatile private var liveUpdateFormat: DspAudioFormat? = null
    private var liveUpdateLatencyFrames = AtomicInteger(0)
    private var trackGenerationLatencyFrames = 0
    @Volatile private var trackGenerationContentCommitted = false
    private var latencyPreservingBypass: DspLatencyPreservingBypass? = null
    private var liveFadeLatencyPreservingBypass: DspLatencyPreservingBypass? = null
    private var liveFadeApplyStatus: DspRendererApplyStatus? = null
    private var liveFadeFailureStatus: DspRendererApplyStatus? = null
    private var liveFadeFrom: DspPcmPipeline? = null
    private var liveFadeTo: DspPcmPipeline? = null
    private var liveFadeGeneration = dspConfigGeneration
    private var liveFadeConfig = dspRuntimeConfig
    private var liveFadeInProgress = false
    private var liveCrossfade = DspPcmCrossfade(
        format.channels.coerceIn(1, 2),
        maxOf(1, format.sampleRate / LIVE_CROSSFADE_DIVISOR),
    )
    private var liveCrossfadeFloats = FloatArray(
        DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels.coerceIn(1, 2),
    )
    private var liveCrossfadeSourceFloats = FloatArray(
        DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels.coerceIn(1, 2),
    )
    private val liveCrossfadeDither = DspTpdfDither(System.nanoTime())
    @Volatile private var activeDspGeneration = dspConfigGeneration
    @Volatile private var activeDspConfig = dspRuntimeConfig
    @Volatile private var activeDspGraphConfig = DspRuntimeConfig.disabled()
    @Volatile private var rendererReleased = false
    @Volatile private var livePreparationPending = false
    @Volatile private var deferredDspGeneration: Long? = null
    @Volatile private var requestedDspGeneration = dspConfigGeneration
    private val reportsDspApplyStatus = classification.dspRole == DspStreamRole.MEDIA
    init {
        if (reportsDspApplyStatus) dspConfigProvider.claimRendererApplyStatusOwner(rendererOwnerToken)
    }
    private var pcm = ByteArray(DspBufferSizing.MAX_DECODER_PCM_BYTES)
    private var normalizedPcm = ByteArray(64 * 1024)
    private var activeDecoderPcmFormat: DecoderPcmFormat? = null
    // Pending follows the decoder output; active describes the initialized AudioTrack.
    private var pendingDecoderPcmFormat: DecoderPcmFormat? = null
    private val trackCreationRetry = TrackCreationRetryPolicy<DecoderPcmFormat>()
    private var lastTrackFailureLogNs = 0L
    private var lastTrackFailureLogFormat: DecoderPcmFormat? = null
    private var lastTrackRetryLogNs: Long? = null
    private var lastTrackOperationFailureLogNs = 0L
    private var lastTrackOperationFailureLogKey: String? = null
    private val trackOperationRecovery = TrackOperationRecoveryPolicy()
    private val writeStallPolicy = AudioTrackWriteStallPolicy()
    private var lastAudioRouteLogNs = 0L
    private var lastAudioRouteDiagnostic: String? = null
    private var activeTrackAttributes: AudioAttributes? = null
    private var playbackRouteLogged = false
    private var rejectedDecoderOutputFormat: String? = null
    @Volatile private var playbackClockSnapshot: AudioPlaybackClock? = null
    private val playbackClockMapper = AudioPlaybackClockMapper(format.payloadType)
    private val playbackHeadTracker = Unsigned32FrameTracker()
    private val timestampFrameTracker = Unsigned32FrameTracker()
    private val timestampPollPolicy = AudioTimestampPollPolicy()
    private var lastObservedPlaybackFrame: Long? = null
    private var timestampAnchor: TimestampAnchor? = null
    private var timestampAcquiredLogged = false
    private var timestampQueryFailureLogged = false
    private var timestampUnavailableLogged = false
    private val sampleTimestampMapper = RtpSampleTimestampMapper(format.sampleRate)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var packetFailureLogged = false
    private var firstAacPayloadLogged = false
    private var lastAacInputMode: String? = null
    private var aacAdtsFallback = false
    private var fragmentedAacDiagnosticLogged = false
    private val aacFallbackPolicy = AacDecoderFallbackPolicy()
    private val aacStartupCache = AacStartupReplayCache(
        maxAccessUnits = AAC_STARTUP_MAX_AUS - 1,
        maxBytes = AAC_STARTUP_MAX_BYTES - AAC_STARTUP_LIVE_AU_RESERVE_BYTES,
    )
    private var aacStartupCacheEnabled = false
    private var aacDecoderUnavailableLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var decoderOutputFailureLogged = false
    private var firstPcmLogged = false
    private var latencyFallbackFailureLogged = false
    private var unalignedPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0L
    private var frameBytes = format.channels.coerceIn(1, 2) * 2
    private var prebufferWriteChunkBytes = PREBUFFER_WRITE_CHUNK_BYTES
    private var bufferProgress = AudioBufferProgress(frameBytes)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private val audioTimestamp = AudioTimestamp()
    private val thread = threadFactory(::run, "carplay-audio").apply { isDaemon = true }

    fun playbackClock(): AudioPlaybackClock? = playbackClockSnapshot

    internal val dspGenerationForTesting: Long
        get() = activeDspGeneration

    internal val deferredDspGenerationForTesting: Long?
        get() = deferredDspGeneration

    internal val hasPreparedDspUpdateForTesting: Boolean
        get() = pendingDspUpdate.get() != null

    internal val hasPendingHotApplyForTesting: Boolean
        get() = pendingHotApply.get() != null

    internal val trackLatencyForTesting: Int
        get() = trackGenerationLatencyFrames

    internal val liveFadeInProgressForTesting: Boolean
        get() = liveFadeInProgress

    internal val effectiveConvolverEnabledForTesting: Boolean
        get() = activeDspGraphConfig.convolver.enabled

    internal val trackContentCommittedForTesting: Boolean
        get() = trackGenerationContentCommitted

    internal val queuedPacketsForTesting: Int
        get() = queue.size

    internal fun prepareDecoderPcmFormatForTesting(sampleRate: Int, channels: Int, encoding: Int) {
        val decoderFormat = DecoderPcmFormat(sampleRate = sampleRate, channels = channels, encoding = encoding)
        pendingDecoderPcmFormat = decoderFormat
        prepareDspPipeline(decoderFormat)
    }

    internal fun prepareLpcmPipelineForTesting() {
        val decoderFormat = DecoderPcmFormat(
            sampleRate = format.sampleRate,
            channels = format.channels.coerceIn(1, 2),
            encoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        pendingDecoderPcmFormat = decoderFormat
        prepareDspPipeline(decoderFormat)
    }

    internal fun handleRtpForTesting(rtp: ByteArray, sample: Int) {
        handle(AudioPacket(rtp, sample))
    }

    internal fun processDecodedPcmForTesting(
        source: ByteArray,
        offset: Int,
        length: Int,
        decoderEncoding: Int,
        sourceSample: Long,
    ) {
        writeDecodedPcm(source, offset, length, decoderEncoding, sourceSample)
    }

    internal fun playbackClockForTesting(framePosition: Long = 0L): AudioPlaybackClock? =
        playbackClockMapper.snapshot(
            framePosition,
            System.nanoTime(),
            "test",
            trackGenerationLatencyFrames,
        )

    internal fun beginTrackGenerationForTesting() {
        val decoderFormat = pendingDecoderPcmFormat ?: activeDecoderPcmFormat
        val trackFormat = DspAudioFormat(
            decoderFormat?.sampleRate ?: format.sampleRate,
            decoderFormat?.channels ?: format.channels.coerceIn(1, 2),
        )
        completeLiveCrossfadeForTrackGeneration(trackFormat)
        beginTrackGeneration(trackFormat)
        playbackClockMapper.reset(format.sampleRate, trackFormat.sampleRate)
        playbackClockSnapshot = null
    }

    internal fun rebindHotPipelineForTesting() {
        dspPipeline?.let { bindHotPipeline(it, liveUpdateControllerEpoch.get(), it.format) }
    }

    internal fun releaseForTesting() = release()

    fun start(): Boolean {
        val started = lifecycle.start(thread) {
            report(
                "Audio: stream start transport=$transport type=${format.payloadType} audioType=${format.audioType} " +
                    "codec=${format.codec} sampleRate=${format.sampleRate} channels=${format.channels} " +
                    "routingPolicy=${if (advancedAudioChannelMapping) "AUTOMOTIVE_BUS" else navigationAudioRoute}",
            )
            report(
                "DSP: configuredEnabled=${dspRuntimeConfig.enabled} role=${classification.dspRole} " +
                    "fullDspAllowed=${DspStreamPolicy.allowsFullDsp(classification.dspRole)} active=false",
            )
        }
        return started || lifecycle.isRunning
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!lifecycle.isRunning) return
        packetsReceived.incrementAndGet()
        val now = System.nanoTime()
        val previous = lastArrivalNs.getAndSet(now)
        if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        val packet = AudioPacket(rtp, sample)
        if (queue.offer(packet)) {
            if (!lifecycle.isRunning) queue.remove(packet)
            return
        }
        if (lifecycle.isRunning) {
            packetsDropped.incrementAndGet()
            if (!droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        val activePipeline = synchronized(dspUpdatePublicationLock) {
            rendererReleased = true
            liveUpdateControllerEpoch.incrementAndGet()
            hotBoundPipeline = null
            hotBoundContextToken = 0
            dspPipeline
        }
        activePipeline?.invalidateHotUpdateContext()
        failPendingHotApply()
        lifecycle.close(thread) { queue.clear() }
    }

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (error: SecurityException) {
            Log.w(TAG, "audio renderer priority request rejected error=${error.javaClass.simpleName}")
        }
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> {
                    val pcmFormat = DecoderPcmFormat(
                        format.sampleRate,
                        format.channels.coerceIn(1, 2),
                        AndroidAudioFormat.ENCODING_PCM_16BIT,
                    )
                    prepareDspPipeline(pcmFormat)
                    createTrack(pcmFormat)
                }
            }
            while (lifecycle.isRunning) {
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maybeSwitchAacToAdtsFallback()
                maybeRetryTrackCreation()
                maintainPlaybackBuffer()
                updatePlaybackClock()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (lifecycle.isRunning) {
                    Log.e(TAG, "audio renderer worker failed error=${error.javaClass.simpleName}")
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
            lifecycle.stopped()
        }
    }

    private fun configureCodec(mime: String, useAdts: Boolean = false) {
        releaseCodec()
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            if (useAdts) {
                aacFallbackPolicy.beginFallback()
                aacStartupCacheEnabled = false
            } else {
                aacFallbackPolicy.reset()
                aacStartupCache.clear()
                aacStartupCacheEnabled = true
                aacDecoderUnavailableLogged = false
            }
        }
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, if (useAdts) 1 else 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config mode=${if (useAdts) "adts-fallback" else "raw"} " +
                    "rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        var candidate: MediaCodec? = null
        try {
            val created = MediaCodec.createDecoderByType(mime)
            candidate = created
            created.configure(mediaFormat, null, null, 0)
            created.start()
            Log.i(TAG, "audio decoder configured mime=$mime name=${created.name}")
            codec = created
            candidate = null
        } catch (error: Exception) {
            candidate?.let { failed ->
                runCatching { failed.stop() }
                runCatching { failed.release() }
            }
            Log.e(TAG, "audio decoder configuration failed mime=$mime error=${error.javaClass.simpleName}")
            report("Audio: decoder configuration failed mime=$mime error=${error.javaClass.simpleName}")
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && !useAdts) {
                startAacAdtsFallback("raw decoder configuration failed")
            }
            return
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            aacAdtsFallback = useAdts
            if (useAdts) {
                reportAacInputMode("adts-fallback")
            }
        }
    }

    private fun createTrack(output: DecoderPcmFormat) {
        if (!shouldCreateTrack(track != null, activeDecoderPcmFormat, output)) return
        if (track != null) releaseTrack()
        val sampleRate = output.sampleRate.takeIf { it in MIN_OUTPUT_SAMPLE_RATE..MAX_OUTPUT_SAMPLE_RATE }
            ?: format.sampleRate
        val channels = output.channels.takeIf { it in 1..2 } ?: format.channels.coerceIn(1, 2)
        val effective = output.copy(sampleRate = sampleRate, channels = channels)
        pendingDecoderPcmFormat = effective
        val nowNs = System.nanoTime()
        val attempt = trackCreationRetry.beginAttempt(effective, nowNs)
        if (!attempt.allowed) return
        if (attempt.attempt == 1) {
            val message = "Audio: AudioTrack create attempt=1 rate=$sampleRate channels=$channels"
            Log.i(TAG, message)
            report(message)
        } else if (lastTrackRetryLogNs == null || nowNs - lastTrackRetryLogNs!! >= TRACK_RETRY_LOG_INTERVAL_NS) {
            lastTrackRetryLogNs = nowNs
            val message = "Audio: retrying AudioTrack creation attempt=${attempt.attempt} " +
                "rate=$sampleRate channels=$channels"
            Log.i(TAG, message)
            report(message)
        }
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = try {
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        } catch (error: Exception) {
            recordTrackCreateFailure(effective, attempt.attempt, "getMinBufferSize:${error.javaClass.simpleName}")
            return
        }
        if (minBuffer <= 0) {
            recordTrackCreateFailure(effective, attempt.attempt, "getMinBufferSize=$minBuffer")
            return
        }
        val plan = MediaAudioBuffer.plan(
            format.audioType,
            sampleRate,
            channels,
            minBuffer,
            mediaBufferMillis,
            bytesPerSample = 2,
        )
        val nextFrameBytes = channels * 2
        val attributes = try {
            audioAttributes()
        } catch (error: Exception) {
            recordTrackCreateFailure(effective, attempt.attempt, "audioAttributes:${error.javaClass.simpleName}", error)
            return
        }
        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: Exception) {
            recordTrackCreateFailure(effective, attempt.attempt, "builder:${error.javaClass.simpleName}", error)
            return
        }
        val stateResult = runCatching { built.state }
        if (stateResult.getOrNull() != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            val error = stateResult.exceptionOrNull()
            val reason = error?.let { "state:${it.javaClass.simpleName}" }
                ?: "state=${stateResult.getOrNull()}"
            recordTrackCreateFailure(effective, attempt.attempt, reason, error as? Exception)
            return
        }
        val recoveredAttempts = trackCreationRetry.recordSuccess(effective)
        lastTrackFailureLogFormat = null
        lastTrackFailureLogNs = 0L
        lastTrackRetryLogNs = null
        track = built
        DspStreamPolicy.reportPlaybackSampleRate(
            classification.dspRole,
            this,
            effective.sampleRate,
            dspConfigProvider::reportPlaybackSampleRate,
        )
        val trackDspFormat = DspAudioFormat(sampleRate, channels)
        completeLiveCrossfadeForTrackGeneration(trackDspFormat)
        beginTrackGeneration(trackDspFormat)
        activeTrackAttributes = attributes
        val usesMusicStream = attributes.usage == AudioAttributes.USAGE_MEDIA ||
            classification.route.useLegacyMusicStream
        val routeGeneration = playbackRouteListenerGate.bind(built)
        val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
            if (routing === built) {
                playbackRouteListenerGate.publishCurrentIfCurrent(
                    built,
                    routeGeneration,
                    { runCatching { built.routedDevice }.getOrNull() },
                ) { device ->
                    onPlaybackRouteChanged?.invoke(
                        playbackRouteOwnerToken,
                        device?.type,
                        device?.address,
                        usesMusicStream,
                    )
                }
            }
        }
        trackRoutingListener = routingListener
        runCatching { built.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper())) }
        playbackRouteListenerGate.publishCurrentIfCurrent(
            built,
            routeGeneration,
            { runCatching { built.routedDevice }.getOrNull() },
        ) { device ->
            onPlaybackRouteChanged?.invoke(
                playbackRouteOwnerToken,
                device?.type,
                device?.address,
                usesMusicStream,
            )
        }
        playbackRouteLogged = false
        statsLastUnderruns = runCatching { built.underrunCount }.getOrDefault(0)
        frameBytes = nextFrameBytes
        bytesPerSecond = sampleRate.toLong() * frameBytes
        val capacityBytes = runCatching { built.bufferSizeInFrames * frameBytes }
            .getOrDefault(plan.trackBufferBytes)
        prebufferWriteChunkBytes = minOf(PREBUFFER_WRITE_CHUNK_BYTES, capacityBytes)
            .coerceAtLeast(frameBytes)
        prebufferWriteChunkBytes -= prebufferWriteChunkBytes % frameBytes
        if (prebufferWriteChunkBytes == 0) prebufferWriteChunkBytes = frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(
            plan.startBytes,
            capacityBytes,
            prebufferWriteChunkBytes,
        )
        bufferProgress = AudioBufferProgress(frameBytes)
        playbackClockMapper.reset(format.sampleRate, sampleRate)
        playbackHeadTracker.reset()
        timestampFrameTracker.reset()
        lastObservedPlaybackFrame = null
        timestampAnchor = null
        timestampPollPolicy.reset(System.nanoTime())
        timestampAcquiredLogged = false
        timestampQueryFailureLogged = false
        timestampUnavailableLogged = false
        playbackClockSnapshot = null
        activeDecoderPcmFormat = effective
        pendingDecoderPcmFormat = effective
        val nativeRate = runCatching { audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }
            .getOrNull() ?: "unavailable"
        val nativeFrames = runCatching { audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            .getOrNull() ?: "unavailable"
        val actualTrackSampleRate = runCatching { built.sampleRate }.getOrDefault(sampleRate)
        val actualTrackChannels = runCatching { built.channelCount }.getOrDefault(channels)
        val bufferMs = capacityBytes * 1000L / bytesPerSecond.coerceAtLeast(1L)
        val startMs = startThresholdBytes * 1000L / bytesPerSecond.coerceAtLeast(1L)
        val diagnostic = "Audio: ready transport=$transport type=${format.payloadType} audioType=${format.audioType} " +
            "negotiatedCodec=${format.codec} negotiatedSampleRate=${format.sampleRate} " +
            "negotiatedChannels=${format.channels} decoder=${codec?.name ?: "LPCM"} " +
            "decoderOutputSampleRate=${effective.sampleRate} decoderOutputChannels=${effective.channels} " +
            "decoderOutputEncoding=${encodingName(effective.encoding)} " +
            "decoderOutputFallback=${effective.fallbackReason ?: "none"} " +
            "audioTrackSampleRate=$actualTrackSampleRate audioTrackChannels=$actualTrackChannels audioTrackEncoding=PCM16 " +
            "audioTrackRequestedBufferBytes=${plan.trackBufferBytes} audioTrackBufferBytes=$capacityBytes " +
            "audioTrackBufferMs=$bufferMs startThresholdMs=$startMs " +
            "usage=${usageName(attributes.usage)}(${attributes.usage}) " +
            "contentType=${contentTypeName(attributes.contentType)}(${attributes.contentType}) " +
            "routingPolicy=${if (advancedAudioChannelMapping) "AUTOMOTIVE_BUS" else navigationAudioRoute} " +
            "nativeSampleRate=$nativeRate framesPerBuffer=$nativeFrames " +
            "timestampFirstQueryMs=${TIMESTAMP_FIRST_QUERY_NS / 1_000_000L} " +
            "timestampPollingMs=${timestampPollPolicy.pollingIntervalMs}"
        Log.i(TAG, diagnostic)
        report(diagnostic)
        if (recoveredAttempts > 1) {
            val message = "Audio: AudioTrack recovered after $recoveredAttempts attempts"
            Log.i(TAG, message)
            report(message)
        }
    }

    private fun recordTrackCreateFailure(
        failedFormat: DecoderPcmFormat,
        attempt: Int,
        reason: String,
        error: Exception? = null,
    ) {
        val nowNs = System.nanoTime()
        val retryInMs = trackCreationRetry.recordFailure(failedFormat, nowNs)
        val shouldLog = failedFormat != lastTrackFailureLogFormat ||
            nowNs - lastTrackFailureLogNs >= TRACK_RETRY_LOG_INTERVAL_NS
        if (!shouldLog) return
        lastTrackFailureLogFormat = failedFormat
        lastTrackFailureLogNs = nowNs
        val message = "Audio: AudioTrack creation failed attempt=$attempt reason=$reason retryInMs=$retryInMs"
        Log.w(TAG, if (error != null) "$message error=${error.javaClass.simpleName}" else message)
        report(message)
    }

    private fun maybeRetryTrackCreation() {
        if (track != null) return
        if (!trackOperationRecovery.shouldAttempt(System.nanoTime())) return
        pendingDecoderPcmFormat?.let(::createTrack)
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = classification.route
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        val builder = AudioAttributes.Builder()
        if (selection.useLegacyMusicStream) {
            builder.setLegacyStreamType(AudioManager.STREAM_MUSIC)
        } else {
            builder.setUsage(usage).setContentType(contentType)
        }
        return builder.build().also {
            val message = "audio route transport=$transport type=${format.payloadType} " +
                "audioType=${format.audioType} mode=$mode navigationRoute=$navigationAudioRoute " +
                "channel=${selection.channel} usage=${usageName(it.usage)}(${it.usage}) " +
                "contentType=${contentTypeName(it.contentType)}(${it.contentType}) " +
                "legacyMusicStream=${selection.useLegacyMusicStream}"
            val nowNs = System.nanoTime()
            if (message != lastAudioRouteDiagnostic || nowNs - lastAudioRouteLogNs >= TRACK_RETRY_LOG_INTERVAL_NS) {
                lastAudioRouteDiagnostic = message
                lastAudioRouteLogNs = nowNs
                Log.i(TAG, message)
                report(message)
            }
        }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    private fun usageName(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "USAGE_MEDIA"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "USAGE_VOICE_COMMUNICATION"
        AudioAttributes.USAGE_ASSISTANT -> "USAGE_ASSISTANT"
        else -> "USAGE_UNKNOWN"
    }

    private fun contentTypeName(contentType: Int): String = when (contentType) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "CONTENT_TYPE_MUSIC"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "CONTENT_TYPE_SPEECH"
        else -> "CONTENT_TYPE_UNKNOWN"
    }

    /** Minimal OpusHead CSD for the negotiated sample rate and channel count. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        try {
            val rtp = packet.rtp
            val payloadSize = rtp.size - RTP_HEADER_BYTES
            if (payloadSize <= 0) return
            when (format.codec) {
                AudioCodecKind.LPCM -> {
                    if (payloadSize % frameBytes != 0) {
                        if (!unalignedPcmLogged) {
                            unalignedPcmLogged = true
                            report("Audio: dropping unaligned LPCM payload bytes=$payloadSize frameBytes=$frameBytes")
                        }
                        return
                    }
                    val pcmBytes = byteSwapS16(rtp.copyOfRange(RTP_HEADER_BYTES, rtp.size))
                    val sourceSample = packet.sample.toLong() and 0xffff_ffffL
                    if (!writePcmWithLiveUpdates(
                            source = pcmBytes,
                            offset = 0,
                            length = pcmBytes.size,
                            dspEncoding = DspPcmEncoding.PCM16,
                            sourceSample = sourceSample,
                        )
                    ) {
                        if (latencyPreservingBypass != null) {
                            reportLatencyFallbackFailure()
                        } else {
                            writePcm(pcmBytes, sourceSample = sourceSample)
                        }
                    }
                }
                AudioCodecKind.AAC_LC -> {
                    val payload = rtp.copyOfRange(RTP_HEADER_BYTES, rtp.size)
                    val parsed = AacRtpPayloadParser.parse(payload)
                    if (parsed == null) {
                        report("Audio: dropping empty AAC payload")
                        return
                    }
                    if (parsed.possibleFragmentation && !fragmentedAacDiagnosticLogged) {
                        fragmentedAacDiagnosticLogged = true
                        val message = "Audio: possible fragmented RFC3640 AAC packet; preserving payload as raw"
                        Log.w(TAG, message)
                        report(message)
                    }
                    reportAacInputMode(
                        if (aacAdtsFallback) "adts-fallback" else parsed.mode.name.lowercase(),
                    )
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        val firstUnit = parsed.accessUnits.first()
                        Log.i(
                            TAG,
                            "audio AAC input mode=${if (aacAdtsFallback) "adts-fallback" else parsed.mode.name.lowercase()} " +
                                "accessUnits=${parsed.accessUnits.size} bytes=${firstUnit.size}",
                        )
                    }
                    parsed.accessUnits.forEachIndexed { index, accessUnit ->
                        val sample = (packet.sample.toLong() and 0xffff_ffffL) +
                            index * AAC_SAMPLES_PER_ACCESS_UNIT
                        val timestampUs = sampleTimestampMapper.presentationTimeUs(sample.toInt())
                        if (aacAdtsFallback) {
                            feedCodec(
                                MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                                timestampUs,
                            )
                        } else if (aacFallbackPolicy.wasAttempted && codec == null) {
                            if (!aacDecoderUnavailableLogged) {
                                aacDecoderUnavailableLogged = true
                                report("Audio: AAC decoder unavailable after compatibility fallback")
                            }
                        } else {
                            val pending = PendingAacAu(
                                bytes = accessUnit,
                                presentationTimeUs = timestampUs,
                                sourceSample = sample and RTP_SAMPLE_MASK,
                            )
                            if (aacStartupCacheEnabled && !aacStartupCache.offer(pending)) {
                                startAacAdtsFallback("startup cache limit reached", pending)
                            } else {
                                feedCodec(
                                    accessUnit,
                                    timestampUs,
                                    countRawAacFallbackSubmission = true,
                                )
                            }
                        }
                    }
                }
                AudioCodecKind.OPUS -> {
                    val accessUnit = rtp.copyOfRange(RTP_HEADER_BYTES, rtp.size)
                    if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                        if (!firstOpusShortPacketLogged) {
                            firstOpusShortPacketLogged = true
                            Log.i(
                                TAG,
                                "audio Opus skipping short packet bytes=${accessUnit.size}",
                            )
                        }
                        return
                    }
                    feedCodec(accessUnit, sampleTimestampMapper.presentationTimeUs(packet.sample))
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!packetFailureLogged) {
                packetFailureLogged = true
                Log.w(TAG, "audio packet dropped codec=${format.codec} error=${error.javaClass.simpleName}")
                report("Audio: packet dropped codec=${format.codec} error=${error.javaClass.simpleName}")
            }
        }
    }

    private fun reportAacInputMode(mode: String) {
        if (lastAacInputMode == mode) return
        lastAacInputMode = mode
        val message = "Audio: AAC input mode=$mode"
        Log.i(TAG, message)
        report(message)
    }

    private fun feedCodec(
        payload: ByteArray,
        presentationTimeUs: Long,
        countRawAacFallbackSubmission: Boolean = false,
    ): Boolean {
        val currentCodec = codec ?: return false
        val index = currentCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return false
        }
        val input = currentCodec.getInputBuffer(index) ?: return false
        input.clear()
        val queued = payload.size <= input.remaining()
        if (queued) {
            input.put(payload)
            currentCodec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (countRawAacFallbackSubmission) {
                aacFallbackPolicy.onAccessUnitsSubmitted(1, System.nanoTime())
            }
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            currentCodec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(currentCodec)
        return queued
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (lifecycle.isRunning) {
            var outputIndex = -1
            try {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index >= 0) outputIndex = index
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = runCatching { codec.outputFormat }.getOrNull()
                        applyDecoderOutputFormat(outputFormat)
                    }
                    index >= 0 -> {
                        val size = info.size
                        val pcmOutput = size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        if (pcmOutput) {
                            outputBuffers++
                            if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                                Log.i(
                                    TAG,
                                    "audio decoder output codec=${format.codec} " +
                                        "buffers=$outputBuffers bytes=$size " +
                                        "queued=$inputQueued dropped=$inputDropped",
                                )
                            }
                        }
                        if (pcmOutput) {
                            val output = codec.getOutputBuffer(index)
                            if (output != null && track == null && pendingDecoderPcmFormat == null &&
                                rejectedDecoderOutputFormat == null
                            ) {
                                applyDecoderOutputFormat(runCatching { codec.outputFormat }.getOrNull())
                            }
                            if (output != null) {
                                if (format.codec == AudioCodecKind.AAC_LC && !aacAdtsFallback) {
                                    markRawAacDecoderOutput()
                                }
                                if (track != null) {
                                    if (size > pcm.size) pcm = ByteArray(size)
                                    output.position(info.offset)
                                    output.limit(info.offset + size)
                                    output.get(pcm, 0, size)
                                    val decoderEncoding = pendingDecoderPcmFormat?.encoding
                                        ?: activeDecoderPcmFormat?.encoding
                                        ?: AndroidAudioFormat.ENCODING_PCM_16BIT
                                    val sourceSample =
                                        sampleTimestampMapper.sampleAtPresentationTimeUs(info.presentationTimeUs)
                                    writeDecodedPcm(
                                        source = pcm,
                                        offset = 0,
                                        length = size,
                                        decoderEncoding = decoderEncoding,
                                        sourceSample = sourceSample,
                                    )
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        outputIndex = -1
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            } catch (error: Exception) {
                if (outputIndex >= 0) runCatching { codec.releaseOutputBuffer(outputIndex, false) }
                if (lifecycle.isRunning && !decoderOutputFailureLogged) {
                    decoderOutputFailureLogged = true
                    Log.w(
                        TAG,
                        "audio decoder output failed codec=${format.codec} " +
                            "error=${error.javaClass.simpleName}",
                    )
                    report("Audio: decoder output failed codec=${format.codec} error=${error.javaClass.simpleName}")
                }
                return
            }
        }
    }

    private fun applyDecoderOutputFormat(output: MediaFormat?) {
        val fingerprint = output?.toString() ?: "missing"
        if (fingerprint == rejectedDecoderOutputFormat) return
        val reasons = ArrayList<String>(3)
        val decoderRate = output?.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val sampleRate = decoderRate?.takeIf { it > 0 } ?: format.sampleRate.also {
            reasons += if (decoderRate == null) "sample_rate_missing" else "sample_rate_invalid"
        }
        val decoderChannels = output?.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        val channels = when {
            decoderChannels == null || decoderChannels <= 0 -> format.channels.coerceIn(1, 2).also {
                reasons += if (decoderChannels == null) "channel_count_missing" else "channel_count_invalid"
            }
            decoderChannels > 2 -> {
                releaseTrack()
                pendingDecoderPcmFormat = null
                rejectedDecoderOutputFormat = fingerprint
                val message = "Audio: unsupported decoder output channels=$decoderChannels; waiting for stereo or mono output"
                Log.w(TAG, message)
                report(message)
                return
            }
            else -> decoderChannels
        }
        val decoderEncoding = output?.intOrNull(MediaFormat.KEY_PCM_ENCODING)
        val encoding = decoderEncoding ?: AndroidAudioFormat.ENCODING_PCM_16BIT.also {
            reasons += "pcm_encoding_missing_default_pcm16"
        }
        if (decoderEncoding != null && !isSupportedDecoderPcmEncoding(encoding)) {
            releaseTrack()
            pendingDecoderPcmFormat = null
            rejectedDecoderOutputFormat = fingerprint
            val message = "Audio: unsupported decoder PCM encoding=$decoderEncoding"
            Log.w(TAG, message)
            report(message)
            return
        }
        if (encoding != AndroidAudioFormat.ENCODING_PCM_16BIT) {
            reasons += "${encodingName(encoding)}_normalized_to_pcm16"
        }
        val next = DecoderPcmFormat(
            sampleRate = sampleRate,
            channels = channels,
            encoding = encoding,
            fallbackReason = reasons.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none",
        )
        if (pendingDecoderPcmFormat?.hasSameTrackFormat(next) == true) return
        pendingDecoderPcmFormat = next
        prepareDspPipeline(next)
        trackOperationRecovery.reset()
        rejectedDecoderOutputFormat = null
        val message = "Audio: decoder output format sampleRate=$sampleRate channels=$channels " +
            "encoding=${encodingName(encoding)} fallback=${next.fallbackReason}"
        Log.i(TAG, message)
        report(message)
        createTrack(next)
    }

    private fun DecoderPcmFormat.hasSameTrackFormat(other: DecoderPcmFormat): Boolean =
        sampleRate == other.sampleRate && channels == other.channels && encoding == other.encoding

    private fun prepareDspPipeline(pcmFormat: DecoderPcmFormat) {
        val nextFormat = runCatching { DspAudioFormat(pcmFormat.sampleRate, pcmFormat.channels) }.getOrNull()
        if (nextFormat != liveUpdatePcmFormat) {
            pcmFormatEpoch++
            if (pcmFormatEpoch <= 0L) pcmFormatEpoch = 1L
            liveUpdatePcmFormat = nextFormat
        }
        if (nextFormat != liveUpdateFormat) {
            trackGenerationContentCommitted = false
            trackGenerationLatencyFrames = 0
            liveUpdateLatencyFrames.set(0)
        }
        if (liveFadeInProgress && (nextFormat == null || liveUpdateFormat != nextFormat ||
                liveFadeFrom?.let { it.format != nextFormat } == true ||
                liveFadeTo?.let { it.format != nextFormat } == true)
        ) {
            abandonLiveFade()
        }
        if (dspPcmEncoding(pcmFormat.encoding) == null) {
            runCatching { dspPipeline?.close() }
            dspPipeline = null
            activeDspGraphConfig = DspRuntimeConfig.disabled()
            reportDsp("DSP unavailable: reason=UNSUPPORTED_PCM_ENCODING fallback=LEGACY_PCM")
            reportCurrentDspApplyStatus(DspRendererApplyPhase.FAILED)
            return
        }
        if (nextFormat == null) {
            runCatching { dspPipeline?.close() }
            dspPipeline = null
            activeDspGraphConfig = DspRuntimeConfig.disabled()
            reportDsp("DSP unavailable: reason=UNSUPPORTED_AUDIO_FORMAT fallback=LEGACY_PCM")
            reportCurrentDspApplyStatus(DspRendererApplyPhase.FAILED)
            return
        }
        if (liveUpdateFormat == nextFormat && liveUpdateController != null) {
            val providerGeneration = runCatching { dspConfigProvider.versionedSnapshot().generation }.getOrNull()
            if (dspPipeline?.format == nextFormat || livePreparationPending || pendingDspUpdate.get() != null ||
                deferredDspGeneration == providerGeneration
            ) {
                return
            }
        }
        val latestSnapshot = runCatching { dspConfigProvider.versionedSnapshot() }.getOrElse {
            reportDsp("DSP unavailable: reason=CONFIG_SNAPSHOT_FAILED fallback=LEGACY_PCM")
            reportCurrentDspApplyStatus(DspRendererApplyPhase.FAILED)
            DspConfigSnapshot(activeDspGeneration, activeDspConfig)
        }
        activeDspConfig = latestSnapshot.config
        activeDspGeneration = latestSnapshot.generation
        runCatching { dspPipeline?.close() }
        dspPipeline = null
        if (!DspStreamPolicy.shouldProcess(activeDspConfig.enabled, classification.dspRole)) {
            activeDspGraphConfig = DspRuntimeConfig.disabled()
            configureLiveUpdates(nextFormat)
            reportDspApplyStatus(
                DspRendererApplyStatus(
                    activeDspGeneration,
                    DspRendererApplyPhase.ACTIVE,
                    activeDspGeneration,
                    convolverApplyWarning(nextFormat, activeDspConfig),
                ),
            )
            return
        }
        val graphConfig = configuredDspGraph(nextFormat, activeDspConfig)
        val preparedPipeline = try {
            dspPipelinePreparer(nextFormat, graphConfig)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (preparedPipeline == null) {
            activeDspGraphConfig = DspRuntimeConfig.disabled()
            reportDsp("DSP unavailable: reason=NATIVE_CREATE_FAILED fallback=LEGACY_PCM")
            configureLiveUpdates(nextFormat, forcePrepareCurrent = true)
            return
        }
        val requiredLatency = liveUpdateLatencyFrames.get()
        if (preparedPipeline.algorithmicLatencyFrames < requiredLatency) {
            preparedPipeline.preserveLatencyWithDelay(requiredLatency - preparedPipeline.algorithmicLatencyFrames)
        }
        preparedPipeline.configGeneration = activeDspGeneration
        dspPipeline = preparedPipeline.also {
            reportDsp("DSP: Float32 pipeline ready sampleRate=${nextFormat.sampleRate} channels=${nextFormat.channels}")
        }
        activeDspGraphConfig = effectiveDspGraphConfig(nextFormat, activeDspConfig, preparedPipeline)
        liveUpdateLatencyFrames.set(preparedPipeline.algorithmicLatencyFrames)
        configureLiveUpdates(nextFormat)
        reportDspApplyStatus(
            DspRendererApplyStatus(
                activeDspGeneration,
                DspRendererApplyPhase.ACTIVE,
                activeDspGeneration,
                convolverApplyWarning(nextFormat, activeDspConfig),
            ),
        )
    }

    private fun effectiveDspGraphConfig(
        format: DspAudioFormat,
        config: DspRuntimeConfig,
        pipeline: DspPcmPipeline?,
    ): DspRuntimeConfig = if (pipeline == null) DspRuntimeConfig.disabled()
    else configuredDspGraph(format, config)

    private fun reportCurrentDspApplyStatus(phase: DspRendererApplyPhase) {
        if (!reportsDspApplyStatus) return
        val generation = runCatching { dspConfigProvider.versionedSnapshot().generation }
            .getOrDefault(activeDspGeneration)
        reportDspApplyStatus(DspRendererApplyStatus(generation, phase, activeDspGeneration))
    }

    private fun reportDspApplyStatus(status: DspRendererApplyStatus) {
        if (reportsDspApplyStatus) {
            dspConfigProvider.reportRendererApplyStatus(status.copy(rendererOwnerToken = rendererOwnerToken))
        }
    }

    private fun convolverApplyWarning(
        targetFormat: DspAudioFormat,
        config: DspRuntimeConfig,
    ): DspRendererApplyWarning? {
        val convolver = config.convolver
        if (!config.enabled || !convolver.enabled || convolver.wet <= 0.0) return null
        val impulseResponse = convolver.impulseResponse ?: return DspRendererApplyWarning.CONVOLVER_IR_UNAVAILABLE
        return if (impulseResponse.sampleRate != targetFormat.sampleRate) {
            DspRendererApplyWarning.CONVOLVER_SAMPLE_RATE_MISMATCH
        } else {
            null
        }
    }

    private fun prepareLatencyPreservingBypass(update: DspPreparedUpdate): DspLatencyPreservingBypass? {
        val graphConfig = effectiveDspGraphConfig(update.format, update.config, update.pipeline)
        val seatAlignment = graphConfig.seatAlignment
        val relativeDelayFrames = if (update.format.channels == 2) {
            seatAlignment.relativeDelayFrames(update.format.sampleRate)
        } else {
            0.0
        }
        val hasSeatControls = seatAlignment.enabled && (
            seatAlignment.leftTrimDb != 0.0 || seatAlignment.rightTrimDb != 0.0 ||
                seatAlignment.invertLeft || seatAlignment.invertRight
            )
        return if (update.latencyFrames > 0 || relativeDelayFrames > 0.0 || hasSeatControls) {
            DspLatencyPreservingBypass(
                update.format,
                update.latencyFrames,
                seatAlignment,
                failurePreampDb = failureFallbackPreampDb(update.format, update.config),
            )
        } else {
            null
        }
    }

    private fun failureFallbackPreampDb(format: DspAudioFormat, config: DspRuntimeConfig): Double {
        return config.failureFallbackPreampDb(format)
    }

    private fun configuredDspGraph(format: DspAudioFormat, config: DspRuntimeConfig): DspRuntimeConfig {
        if (!config.enabled) return DspRuntimeConfig.disabled()
        val convolver = config.convolver
        val impulse = convolver.impulseResponse
        val convolverAvailable = convolver.enabled && convolver.wet > 0.0 && impulse != null &&
            impulse.sampleRate == format.sampleRate
        return if (convolver.enabled && !convolverAvailable) {
            config.withConvolver(convolver.copy(enabled = false, impulseResponse = null))
        } else {
            config
        }
    }

    private fun DspRuntimeConfig.requiresStateContinuity(): Boolean = enabled && (
        convolver.enabled && convolver.wet > 0.0 && convolver.impulseResponse != null ||
            compressor.enabled || multiband.enabled || dynamicEq.enabled && dynamicEq.bands.any { it.enabled } ||
            seatAlignment.requiresStateContinuity || advancedMs.requiresStateContinuity ||
            harmonic.requiresStateContinuity
        )

    private fun configureLiveUpdates(format: DspAudioFormat, forcePrepareCurrent: Boolean = false) {
        val enabledForRole = DspStreamPolicy.allowsFullDsp(classification.dspRole)
        val transition = synchronized(dspUpdatePublicationLock) {
            if (enabledForRole && liveUpdateFormat == format && liveUpdateController != null) return
            val previousController = liveUpdateController
            val discardedUpdate = pendingDspUpdate.getAndSet(null)
            liveUpdateController = null
            livePreparationPending = false
            deferredDspGeneration = null
            if (!enabledForRole) {
                liveUpdateFormat = null
                LiveUpdateTransition(
                    epoch = liveUpdateControllerEpoch.incrementAndGet(),
                    previousController = previousController,
                    discardedUpdate = discardedUpdate,
                    disabled = true,
                )
            } else {
                val epoch = liveUpdateControllerEpoch.incrementAndGet()
                liveUpdateFormat = format
                requestedDspGeneration = activeDspGeneration
                LiveUpdateTransition(epoch, previousController, discardedUpdate, disabled = false)
            }
        }
        transition.previousController?.close()
        transition.discardedUpdate?.update?.pipeline?.let { runCatching { it.close() } }
        if (transition.disabled) return
        val controllerEpoch = transition.epoch
        bindHotPipeline(dspPipeline, controllerEpoch, format)
        liveCrossfade = DspPcmCrossfade(format.channels, maxOf(1, format.sampleRate / LIVE_CROSSFADE_DIVISOR))
        liveCrossfadeFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
        liveCrossfadeSourceFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
        val newController = DspLiveUpdateController(
            format = format,
            role = classification.dspRole,
            provider = dspConfigProvider,
            initialGeneration = if (forcePrepareCurrent) activeDspGeneration - 1 else activeDspGeneration,
            activeLatencyFrames = { liveUpdateLatencyFrames.get() },
            onPreparing = { snapshot ->
                if (isCurrentLiveUpdateController(controllerEpoch, format)) {
                    requestedDspGeneration = snapshot.generation
                    livePreparationPending = true
                    deferredDspGeneration = null
                    reportDspApplyStatus(
                        DspRendererApplyStatus(
                            snapshot.generation,
                            DspRendererApplyPhase.PREPARING,
                            activeDspGeneration,
                        ),
                    )
                }
            },
            onDeferred = { generation, reason ->
                if (isCurrentLiveUpdateController(controllerEpoch, format) &&
                    requestedDspGeneration == generation
                ) {
                    livePreparationPending = false
                    deferredDspGeneration = generation
                    reportDspApplyStatus(
                        DspRendererApplyStatus(
                            generation,
                            DspRendererApplyPhase.DEFERRED,
                            activeDspGeneration,
                            reason = reason,
                        ),
                    )
                }
            },
            onFailed = { snapshot, error ->
                if (isCurrentLiveUpdateController(controllerEpoch, format) &&
                    requestedDspGeneration == snapshot.generation
                ) {
                    livePreparationPending = false
                    reportDsp("DSP unavailable: reason=PREPARE_FAILED error=${error.javaClass.simpleName}")
                    reportDspApplyStatus(
                        DspRendererApplyStatus(
                            snapshot.generation,
                            DspRendererApplyPhase.FAILED,
                            activeDspGeneration,
                        ),
                    )
                }
            },
            canAdoptLatencyChange = { !trackGenerationContentCommitted },
            onPrepared = {},
            onPreparedResult = { update ->
                runCatching { beforeDspUpdatePublishForTest?.invoke() }
                var previousPipeline: DspPcmPipeline? = null
                var discardPipeline: DspPcmPipeline? = null
                var preparedStatus: DspRendererApplyStatus? = null
                var failedStatus: DspRendererApplyStatus? = null
                val accepted = synchronized(dspUpdatePublicationLock) {
                    if (!isCurrentLiveUpdateController(controllerEpoch, format) ||
                        update.generation < requestedDspGeneration
                    ) {
                        false
                    } else {
                        runCatching { afterDspUpdateOwnerCheckForTest?.invoke() }
                        if (update.generation == requestedDspGeneration) livePreparationPending = false
                        if (update.config.enabled && update.pipeline == null && update.hotUpdate == null) {
                            failedStatus = DspRendererApplyStatus(
                                update.generation,
                                DspRendererApplyPhase.FAILED,
                                activeDspGeneration,
                            )
                        } else {
                            val warning = convolverApplyWarning(update.format, update.config)
                            val prepared = PreparedRendererDspUpdate(
                                update = update,
                                graphConfig = if (update.hotUpdate != null) {
                                    configuredDspGraph(update.format, update.config)
                                } else {
                                    effectiveDspGraphConfig(update.format, update.config, update.pipeline)
                                },
                                latencyPreservingBypass = if (update.hotUpdate == null) {
                                    prepareLatencyPreservingBypass(update)
                                } else {
                                    null
                                },
                                preparedStatus = DspRendererApplyStatus(
                                    update.generation,
                                    DspRendererApplyPhase.PREPARED,
                                    activeDspGeneration,
                                    warning,
                                ),
                                activeStatus = DspRendererApplyStatus(
                                    update.generation,
                                    DspRendererApplyPhase.ACTIVE,
                                    update.generation,
                                    warning,
                                ),
                                deferredStatus = DspRendererApplyStatus(
                                    update.generation,
                                    DspRendererApplyPhase.DEFERRED,
                                    activeDspGeneration,
                                    warning,
                                ),
                                failedStatus = DspRendererApplyStatus(
                                    update.generation,
                                    DspRendererApplyPhase.FAILED,
                                    activeDspGeneration,
                                ),
                            )
                            previousPipeline = pendingDspUpdate.getAndSet(prepared)?.update?.pipeline
                            if (rendererReleased && pendingDspUpdate.compareAndSet(prepared, null)) {
                                discardPipeline = update.pipeline
                            } else if (pendingDspUpdate.get() === prepared) {
                                preparedStatus = prepared.preparedStatus
                            }
                        }
                        true
                    }
                }
                previousPipeline?.let { runCatching { it.close() } }
                discardPipeline?.let { runCatching { it.close() } }
                failedStatus?.let { runCatching { reportDspApplyStatus(it) } }
                preparedStatus?.let { runCatching { reportDspApplyStatus(it) } }
                if (accepted) runCatching { onDspUpdatePreparedForTest?.invoke() }
                accepted
            },
            pipelinePreparer = { candidateFormat, config ->
                val graphConfig = configuredDspGraph(candidateFormat, config)
                val pipeline = dspPipelinePreparer(candidateFormat, graphConfig)
                val requiredLatency = liveUpdateLatencyFrames.get()
                if (pipeline != null && pipeline.algorithmicLatencyFrames < requiredLatency) {
                    pipeline.preserveLatencyWithDelay(requiredLatency - pipeline.algorithmicLatencyFrames)
                }
                pipeline
            },
            preparedLatencyFrames = { pipeline ->
                pipeline?.algorithmicLatencyFrames
                    ?: if (trackGenerationContentCommitted) liveUpdateLatencyFrames.get() else 0
            },
            onRetirementFailed = { pipeline, error ->
                if (!rendererReleased && liveUpdateControllerEpoch.get() == controllerEpoch) {
                    val generation = runCatching { dspConfigProvider.versionedSnapshot().generation }
                        .getOrDefault(activeDspGeneration)
                    reportDsp(
                        "DSP retirement failed retiredPipelineId=${System.identityHashCode(pipeline)} " +
                            "retiredGeneration=${pipeline.configGeneration ?: "unknown"} " +
                            "activeGeneration=$activeDspGeneration requestedGeneration=$generation " +
                            "rendererEpoch=$controllerEpoch updatesBlocked=true " +
                            "error=${error.javaClass.simpleName}",
                    )
                    reportDspApplyStatus(
                        DspRendererApplyStatus(
                            generation = generation,
                            phase = DspRendererApplyPhase.ACTIVE,
                            activeGeneration = activeDspGeneration,
                            retiredGeneration = pipeline.configGeneration,
                            retiredPipelineId = System.identityHashCode(pipeline),
                            updatesBlocked = true,
                        ),
                    )
                }
            },
            hotUpdatePreparer = { candidateFormat, snapshot ->
                val current = activeDspConfig
                if (hotBoundPipeline === dspPipeline && candidateFormat == format &&
                    dspPipeline?.format == candidateFormat &&
                    dspConfigProvider.studioHotUpdatesEnabled() && current.canApplyStudioHotUpdateTo(snapshot.config) &&
                    DspUpdatePlanner.classify(
                        current = current,
                        target = snapshot.config,
                        hasActivePipeline = true,
                        trackGenerationCommitted = trackGenerationContentCommitted,
                        currentLatencyFrames = liveUpdateLatencyFrames.get(),
                        targetLatencyFrames = liveUpdateLatencyFrames.get(),
                        requiresStateContinuity = false,
                        mediaRole = classification.dspRole == DspStreamRole.MEDIA,
                    ) == DspUpdatePlan.HOT
                ) {
                    snapshot.config.prepareHotUpdate(
                        candidateFormat,
                        snapshot.generation,
                        editorOwnerEpoch = controllerEpoch,
                        rendererOwnerToken = rendererOwnerToken,
                        pcmFormatEpoch = pcmFormatEpoch,
                        hotContextToken = hotBoundContextToken,
                        mediaRole = classification.dspRole == DspStreamRole.MEDIA,
                    )
                } else {
                    null
                }
            },
        )
        val installed = synchronized(dspUpdatePublicationLock) {
            if (!rendererReleased && liveUpdateControllerEpoch.get() == controllerEpoch &&
                liveUpdateFormat == format
            ) {
                liveUpdateController = newController
                true
            } else {
                false
            }
        }
        if (!installed) newController.close()
    }

    private fun isCurrentLiveUpdateController(epoch: Long, format: DspAudioFormat): Boolean =
        synchronized(dspUpdatePublicationLock) {
            !rendererReleased && liveUpdateControllerEpoch.get() == epoch && liveUpdateFormat == format
        }

    private fun bindHotPipeline(pipeline: DspPcmPipeline?, epoch: Long, format: DspAudioFormat): Boolean {
        if (pipeline == null || pipeline.format != format || classification.dspRole != DspStreamRole.MEDIA) {
            hotBoundPipeline = null
            hotBoundContextToken = 0
            discardStalePendingHotApply()
            return false
        }
        val bound = pipeline.bindHotUpdateContext(
            editorOwnerEpoch = epoch,
            rendererOwnerToken = rendererOwnerToken,
            pcmFormatEpoch = pcmFormatEpoch,
            hotContextToken = nextHotContextToken.updateAndGet { previous ->
                if (previous == Int.MAX_VALUE) 1 else previous + 1
            },
            mediaRole = true,
        )
        hotBoundPipeline = pipeline.takeIf { bound }
        hotBoundContextToken = pipeline.hotUpdateContextToken().takeIf { bound } ?: 0
        discardStalePendingHotApply()
        return bound
    }

    private fun writePcmWithLiveUpdates(
        source: ByteArray,
        offset: Int,
        length: Int,
        dspEncoding: DspPcmEncoding,
        sourceSample: Long?,
    ): Boolean {
        adoptPreparedDspUpdate()
        val currentFallback = latencyPreservingBypass
        val currentFallbackFrames = currentFallback?.process(
            source,
            offset,
            length,
            dspEncoding,
            encodeOutput = !liveFadeInProgress && dspPipeline == null,
        ) ?: -1
        if (!liveFadeInProgress) {
            val pipeline = dspPipeline
            if (pipeline == null) {
                return currentFallbackFrames > 0 && currentFallback?.let { writeLatencyFallback(it, sourceSample) } == true
            }
            val handled = writeDspIfAvailable(pipeline, source, offset, length, dspEncoding, sourceSample)
            if (!handled && currentFallbackFrames >= 0 && currentFallback != null) {
                return writeLatencyFallback(currentFallback, sourceSample)
            }
            return handled
        }
        val targetLatencyFallback = liveFadeLatencyPreservingBypass
        val targetFallbackFrames = targetLatencyFallback?.process(
            source,
            offset,
            length,
            dspEncoding,
            encodeOutput = false,
        ) ?: -1
        return writeLiveCrossfade(
            source,
            offset,
            length,
            dspEncoding,
            sourceSample,
            currentFallback?.takeIf { currentFallbackFrames >= 0 },
            targetLatencyFallback?.takeIf { targetFallbackFrames >= 0 },
        )
    }

    private fun writeLatencyFallback(
        bypass: DspLatencyPreservingBypass,
        sourceSample: Long?,
    ): Boolean {
        val outputLength = bypass.outputLength.takeIf { it > 0 } ?: bypass.renderPcm16()
        if (outputLength <= 0) return false
        writePcm(bypass.output, 0, outputLength, sourceSample)
        return true
    }

    private fun writeDecodedPcm(
        source: ByteArray,
        offset: Int,
        length: Int,
        decoderEncoding: Int,
        sourceSample: Long,
    ) {
        val dspEncoding = dspPcmEncoding(decoderEncoding)
        if (dspEncoding != null) {
            val channels = pendingDecoderPcmFormat?.channels ?: activeDecoderPcmFormat?.channels
                ?: format.channels.coerceIn(1, 2)
            val sourceFrameBytes = dspEncoding.bytesPerSample * channels
            val frameCount = length / sourceFrameBytes
            if (frameCount > DspBufferSizing.MAX_DECODER_PCM_FRAMES) {
                val trailingBytes = length % sourceFrameBytes
                var framesConsumed = 0
                while (framesConsumed < frameCount) {
                    val chunkFrames = minOf(DspBufferSizing.MAX_DECODER_PCM_FRAMES, frameCount - framesConsumed)
                    val finalChunk = framesConsumed + chunkFrames == frameCount
                    val chunkLength = chunkFrames * sourceFrameBytes + if (finalChunk) trailingBytes else 0
                    val chunkOffset = offset + framesConsumed * sourceFrameBytes
                    val chunkSourceSample = sourceSample.takeIf { framesConsumed == 0 }
                    if (!writePcmWithLiveUpdates(
                            source = source,
                            offset = chunkOffset,
                            length = chunkLength,
                            dspEncoding = dspEncoding,
                            sourceSample = chunkSourceSample,
                        )
                    ) {
                        if (latencyPreservingBypass != null) {
                            reportLatencyFallbackFailure()
                            return
                        }
                        val normalized = normalizePcm16(source, chunkOffset, chunkLength, decoderEncoding) ?: return
                        writePcm(normalized.bytes, normalized.offset, normalized.length, chunkSourceSample)
                    }
                    framesConsumed += chunkFrames
                }
                return
            }
            if (writePcmWithLiveUpdates(
                    source = source,
                    offset = offset,
                    length = length,
                    dspEncoding = dspEncoding,
                    sourceSample = sourceSample,
            )
            ) {
                return
            }
        }
        if (latencyPreservingBypass != null) {
            reportLatencyFallbackFailure()
            return
        }
        val normalized = normalizePcm16(source, offset, length, decoderEncoding) ?: return
        writePcm(normalized.bytes, normalized.offset, normalized.length, sourceSample)
    }

    private fun reportLatencyFallbackFailure() {
        if (latencyFallbackFailureLogged) return
        latencyFallbackFailureLogged = true
        reportDsp("DSP unavailable: reason=LATENCY_PRESERVING_FALLBACK_FAILED output=DROP_BLOCK")
    }

    private fun adoptPreparedDspUpdate() {
        discardStalePendingHotApply()
        if (liveFadeInProgress) return
        val prepared = pendingDspUpdate.get() ?: return
        val update = prepared.update
        val controller = liveUpdateController ?: return
        if (!controller.canAdoptUpdate()) return
        val decoderFormat = pendingDecoderPcmFormat ?: activeDecoderPcmFormat
        val effectiveFormat = decoderFormat?.let {
            runCatching { DspAudioFormat(it.sampleRate, it.channels) }.getOrNull()
        } ?: liveUpdateFormat
        if (update.format != liveUpdateFormat || update.format != effectiveFormat ||
            update.pipeline?.format?.let { it != update.format } == true
        ) {
            if (pendingDspUpdate.compareAndSet(prepared, null)) controller.retire(update.pipeline)
            return
        }
        val hotUpdate = update.hotUpdate
        if (hotUpdate != null && (rendererReleased || hotUpdate.generation != update.generation ||
                hotUpdate.editorOwnerEpoch != liveUpdateControllerEpoch.get() ||
                hotUpdate.rendererOwnerToken != rendererOwnerToken ||
                hotUpdate.pcmFormatEpoch != pcmFormatEpoch || hotUpdate.hotContextToken != hotBoundContextToken ||
                !hotUpdate.mediaRole ||
                classification.dspRole != DspStreamRole.MEDIA)
        ) {
            if (pendingDspUpdate.compareAndSet(prepared, null)) controller.retire(update.pipeline)
            return
        }
        val latestGeneration = runCatching { dspConfigProvider.versionedSnapshot().generation }
            .getOrDefault(update.generation)
        if (update.generation < latestGeneration) {
            if (pendingDspUpdate.compareAndSet(prepared, null)) controller.retire(update.pipeline)
            return
        }
        if (trackGenerationContentCommitted && deferredDspGeneration == update.generation &&
            update.hotUpdate == null
        ) return
        if (hotUpdate != null) {
            val pipeline = dspPipeline
            if (rendererReleased || hotBoundPipeline !== pipeline || pipeline == null || pipeline.format != update.format ||
                hotUpdate.generation != update.generation ||
                hotUpdate.editorOwnerEpoch != liveUpdateControllerEpoch.get() ||
                hotUpdate.rendererOwnerToken != rendererOwnerToken ||
                hotUpdate.pcmFormatEpoch != pcmFormatEpoch || hotUpdate.hotContextToken != hotBoundContextToken ||
                !hotUpdate.mediaRole ||
                pendingHotApply.get() != null
            ) return
            if (!pendingDspUpdate.compareAndSet(prepared, null)) return
            if (!dspConfigProvider.studioHotUpdatesEnabled()) {
                deferredDspGeneration = update.generation
                reportDspApplyStatus(
                    prepared.deferredStatus.copy(reason = "Native HOT updates are disabled"),
                )
                return
            }
            if (!pipeline.enqueueHotUpdate(hotUpdate)) {
                pendingDspUpdate.compareAndSet(null, prepared)
                deferredDspGeneration = update.generation
                reportDspApplyStatus(
                    prepared.deferredStatus.copy(
                        reason = "native DSP processor rejected the hot update; retrying on the next PCM block while keeping the active pipeline",
                    ),
                )
                return
            }
            pendingHotApply.set(PendingRendererHotApply(prepared, pipeline, hotUpdate.hotContextToken))
            discardStalePendingHotApply()
            return
        }
        val candidateLatencyFrames = when {
            !trackGenerationContentCommitted -> update.pipeline?.algorithmicLatencyFrames ?: 0
            update.pipeline == null -> trackGenerationLatencyFrames
            else -> update.pipeline.algorithmicLatencyFrames
        }
        val graphConfig = prepared.graphConfig
        val updateDecision = DspUpdatePlanner.decide(
            current = activeDspGraphConfig,
            target = graphConfig,
            hasActivePipeline = dspPipeline != null,
            trackGenerationCommitted = trackGenerationContentCommitted,
            currentLatencyFrames = trackGenerationLatencyFrames,
            targetLatencyFrames = candidateLatencyFrames,
            requiresStateContinuity = graphConfig.requiresStateContinuity(),
            mediaRole = classification.dspRole == DspStreamRole.MEDIA,
            studioHotUpdatesEnabled = dspConfigProvider.studioHotUpdatesEnabled(),
        )
        val unsupportedLocalWarm = updateDecision.plan == DspUpdatePlan.LOCAL_WARM &&
            trackGenerationContentCommitted
        if (updateDecision.plan == DspUpdatePlan.DEFERRED || unsupportedLocalWarm) {
            if (deferredDspGeneration != update.generation) {
                deferredDspGeneration = update.generation
                val reason = if (unsupportedLocalWarm) {
                    "stage-local warm-up is unavailable; keeping the active pipeline until the next AudioTrack generation"
                } else {
                    updateDecision.reason ?: "update is not safe for the committed track"
                }
                reportDsp("DSP update deferred until next AudioTrack generation: $reason")
                reportDspApplyStatus(prepared.deferredStatus.copy(reason = reason))
            }
            return
        }
        if (!trackGenerationContentCommitted) {
            if (!pendingDspUpdate.compareAndSet(prepared, null)) return
            val oldPipeline = dspPipeline
            dspPipeline = update.pipeline
            bindHotPipeline(dspPipeline, liveUpdateControllerEpoch.get(), update.format)
            activeDspConfig = update.config
            activeDspGraphConfig = graphConfig
            activeDspGeneration = update.generation
            beginTrackGeneration(
                trackFormat = update.format,
                preparedFallback = prepared.latencyPreservingBypass,
                fallbackWasPrepared = true,
            )
            controller.retire(oldPipeline?.takeIf { it !== dspPipeline })
            reportDspApplyStatus(prepared.activeStatus)
            return
        }
        if (!pendingDspUpdate.compareAndSet(prepared, null)) return
        if (update.pipeline === dspPipeline || (update.pipeline == null && dspPipeline == null)) {
            activeDspConfig = update.config
            activeDspGraphConfig = graphConfig
            activeDspGeneration = update.generation
            liveUpdateLatencyFrames.set(trackGenerationLatencyFrames)
            latencyPreservingBypass = prepared.latencyPreservingBypass
            reportDspApplyStatus(prepared.activeStatus)
            return
        }
        liveFadeFrom = dspPipeline
        liveFadeTo = update.pipeline
        liveFadeGeneration = update.generation
        liveFadeConfig = update.config
        liveFadeLatencyPreservingBypass = prepared.latencyPreservingBypass
        liveFadeApplyStatus = prepared.activeStatus
        liveFadeFailureStatus = prepared.failedStatus
        liveCrossfade.begin()
        liveFadeInProgress = true
    }

    private fun writeLiveCrossfade(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        sourceSample: Long?,
        latencyFallback: DspLatencyPreservingBypass?,
        targetLatencyFallback: DspLatencyPreservingBypass?,
    ): Boolean {
        val fromPipeline = liveFadeFrom
        val toPipeline = liveFadeTo
        val crossfadeFormat = liveUpdateFormat ?: return false
        if (fromPipeline?.format?.let { it != crossfadeFormat } == true ||
            toPipeline?.format?.let { it != crossfadeFormat } == true
        ) {
            abandonLiveFade()
            return false
        }
        val frameBytes = encoding.bytesPerSample * crossfadeFormat.channels
        if (length % frameBytes != 0) return false
        val frameCount = length / frameBytes
        if (frameCount <= 0 || frameCount > DspBufferSizing.MAX_DECODER_PCM_FRAMES) return false
        val fallbackFloats = latencyFallback?.takeIf { it.outputFrameCount == frameCount }?.outputFloats
        val targetFallbackFloats = targetLatencyFallback?.takeIf { it.outputFrameCount == frameCount }?.outputFloats
        val fromFloats = fromPipeline?.let { pipeline ->
            val outputLength = runCatching {
                pipeline.process(source, offset, length, encoding, encodeOutput = false)
            }.getOrDefault(-1)
            if (outputLength < 0) {
                if (dspPipeline === pipeline) {
                    latencyFallback?.activateSeatControlsForCurrentOutput()
                    dspPipeline = null
                    activeDspGraphConfig = DspRuntimeConfig.disabled()
                }
                abandonLiveFade()
                reportDsp("DSP unavailable: reason=PROCESS_FAILED fallback=LEGACY_PCM")
                if (latencyFallback != null && writeLatencyFallback(latencyFallback, sourceSample)) return true
                return false
            }
            if (outputLength == 0 || pipeline.outputFrameCount != frameCount) return false
            pipeline.outputFloats
        } ?: fallbackFloats ?: liveCrossfadeSourceFloats.also { destination ->
            if (DspPcmConverter.pcmToFloatArray(source, offset, length, encoding, crossfadeFormat, destination) != frameCount) {
                abandonLiveFade()
                return false
            }
        }
        val toFloats = toPipeline?.let { pipeline ->
            val outputLength = runCatching {
                pipeline.process(source, offset, length, encoding, encodeOutput = false)
            }.getOrDefault(-1)
            if (outputLength < 0) {
                liveUpdateController?.retire(pipeline)
                liveFadeFrom = null
                liveFadeTo = null
                liveFadeLatencyPreservingBypass = null
                liveFadeApplyStatus = null
                liveFadeInProgress = false
                liveCrossfade.cancel()
                val failedStatus = liveFadeFailureStatus
                if (failedStatus != null) reportDspApplyStatus(failedStatus)
                liveFadeFailureStatus = null
                if (fromPipeline != null) {
                    dspPipeline = fromPipeline
                    val fromLength = DspBufferSizing.pcm16ByteCount(fromPipeline.outputFrameCount, crossfadeFormat.channels)
                    if (!fromPipeline.encodeFloatPcm16(
                            fromPipeline.outputFloats,
                            fromPipeline.outputFrameCount,
                            normalizedPcm,
                            0,
                        )
                    ) {
                        return false
                    }
                    writePcm(normalizedPcm, 0, fromLength, sourceSample)
                    return true
                }
                if (latencyFallback != null && writeLatencyFallback(latencyFallback, sourceSample)) return true
                return false
            }
            if (outputLength == 0 || pipeline.outputFrameCount != frameCount) return false
            pipeline.outputFloats
        } ?: targetFallbackFloats ?: (if (liveFadeConfig.enabled) fallbackFloats else null)
            ?: liveCrossfadeSourceFloats.also { destination ->
            if (DspPcmConverter.pcmToFloatArray(source, offset, length, encoding, crossfadeFormat, destination) != frameCount) {
                abandonLiveFade()
                return false
            }
        }
        val fadeCompleted = liveCrossfade.blend(fromFloats, toFloats, liveCrossfadeFloats, frameCount)
        if (!fadeCompleted && !liveCrossfade.isActive) {
            abandonLiveFade()
            return false
        }
        val outputLength = DspBufferSizing.pcm16ByteCount(frameCount, crossfadeFormat.channels)
        val encoder = toPipeline ?: fromPipeline
        val encoded = if (encoder != null) {
            encoder.encodeFloatPcm16(liveCrossfadeFloats, frameCount, normalizedPcm, 0)
        } else {
            DspPcmConverter.floatArrayToPcm16(
                liveCrossfadeFloats,
                frameCount,
                crossfadeFormat,
                normalizedPcm,
                0,
                liveCrossfadeDither,
            )
        }
        if (!encoded) {
            abandonLiveFade()
            return false
        }
        writePcm(normalizedPcm, 0, outputLength, sourceSample)
        if (fadeCompleted) finishLiveCrossfade()
        return true
    }

    private fun finishLiveCrossfade() {
        val oldPipeline = liveFadeFrom
        dspPipeline = liveFadeTo
        dspPipeline?.let { bindHotPipeline(it, liveUpdateControllerEpoch.get(), it.format) }
        liveFadeFrom = null
        liveFadeTo = null
        liveFadeInProgress = false
        activeDspConfig = liveFadeConfig
        activeDspGeneration = liveFadeGeneration
        activeDspGraphConfig = effectiveDspGraphConfig(
            liveUpdateFormat ?: liveFadeTo?.format ?: liveFadeFrom?.format ?: DspAudioFormat(
                format.sampleRate,
                format.channels.coerceIn(1, 2),
            ),
            activeDspConfig,
            dspPipeline,
        )
        latencyPreservingBypass = liveFadeLatencyPreservingBypass
        liveFadeLatencyPreservingBypass = null
        liveUpdateLatencyFrames.set(trackGenerationLatencyFrames)
        liveUpdateController?.retire(oldPipeline?.takeIf { it !== dspPipeline })
        val activeStatus = liveFadeApplyStatus
        if (activeStatus != null) reportDspApplyStatus(activeStatus)
        liveFadeApplyStatus = null
        liveFadeFailureStatus = null
    }

    private fun completeLiveCrossfadeForTrackGeneration(trackFormat: DspAudioFormat? = null) {
        if (!liveFadeInProgress) return
        if (trackFormat != null && (liveUpdateFormat != trackFormat ||
                liveFadeFrom?.let { it.format != trackFormat } == true ||
                liveFadeTo?.let { it.format != trackFormat } == true)
        ) {
            abandonLiveFade()
            return
        }
        val oldPipeline = liveFadeFrom
        dspPipeline = liveFadeTo
        dspPipeline?.let { bindHotPipeline(it, liveUpdateControllerEpoch.get(), it.format) }
        liveFadeFrom = null
        liveFadeTo = null
        liveFadeLatencyPreservingBypass = null
        val activeStatus = liveFadeApplyStatus
        if (activeStatus != null) reportDspApplyStatus(activeStatus)
        liveFadeApplyStatus = null
        liveFadeFailureStatus = null
        liveFadeInProgress = false
        activeDspConfig = liveFadeConfig
        activeDspGeneration = liveFadeGeneration
        liveCrossfade.cancel()
        activeDspGraphConfig = effectiveDspGraphConfig(
            liveUpdateFormat ?: trackFormat ?: DspAudioFormat(format.sampleRate, format.channels.coerceIn(1, 2)),
            activeDspConfig,
            dspPipeline,
        )
        liveUpdateController?.retire(oldPipeline?.takeIf { it !== dspPipeline })
    }

    private fun beginTrackGeneration(
        trackFormat: DspAudioFormat? = null,
        preparedFallback: DspLatencyPreservingBypass? = null,
        fallbackWasPrepared: Boolean = false,
    ) {
        val effectiveFormat = trackFormat ?: DspAudioFormat(
            activeDecoderPcmFormat?.sampleRate ?: format.sampleRate,
            activeDecoderPcmFormat?.channels ?: format.channels.coerceIn(1, 2),
        )
        val mismatchedPipeline = dspPipeline?.takeIf { it.format != effectiveFormat }
        if (mismatchedPipeline != null) {
            dspPipeline = null
            activeDspGraphConfig = DspRuntimeConfig.disabled()
            liveUpdateController?.retire(mismatchedPipeline) ?: runCatching { mismatchedPipeline.close() }
        }
        trackGenerationContentCommitted = false
        deferredDspGeneration = null
        trackGenerationLatencyFrames = dspPipeline?.algorithmicLatencyFrames ?: 0
        liveUpdateLatencyFrames.set(trackGenerationLatencyFrames)
        runCatching { dspPipeline?.reset() }
        val seatSource = if (dspPipeline != null) activeDspGraphConfig else activeDspConfig
        val seatAlignment = seatSource.seatAlignment.takeIf { seatSource.enabled } ?: DspSeatAlignmentConfig()
        val interchannelRelativeDelay = if (effectiveFormat.channels == 2) {
            seatAlignment.relativeDelayFrames(effectiveFormat.sampleRate)
        } else {
            0.0
        }
        val hasSeatControls = seatAlignment.enabled && (
            seatAlignment.leftTrimDb != 0.0 || seatAlignment.rightTrimDb != 0.0 ||
                seatAlignment.invertLeft || seatAlignment.invertRight
            )
        latencyPreservingBypass = if (fallbackWasPrepared) {
            preparedFallback
        } else if (trackGenerationLatencyFrames > 0 || interchannelRelativeDelay > 0.0 || hasSeatControls) {
            DspLatencyPreservingBypass(
                effectiveFormat,
                trackGenerationLatencyFrames,
                seatAlignment,
                failurePreampDb = failureFallbackPreampDb(effectiveFormat, seatSource),
            )
        } else {
            null
        }
        if (dspPipeline == null && activeDspConfig.enabled) {
            latencyPreservingBypass?.activateSeatControlsForCurrentOutput()
        }
    }

    private fun abandonLiveFade() {
        liveUpdateController?.retire(liveFadeFrom?.takeIf { it !== dspPipeline })
        liveUpdateController?.retire(liveFadeTo?.takeIf { it !== dspPipeline })
        liveFadeFrom = null
        liveFadeTo = null
        liveFadeLatencyPreservingBypass = null
        val failedStatus = liveFadeFailureStatus
        if (failedStatus != null) reportDspApplyStatus(failedStatus)
        liveFadeApplyStatus = null
        liveFadeFailureStatus = null
        liveFadeInProgress = false
        liveCrossfade.cancel()
    }

    internal fun writeDspIfAvailable(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        sourceSample: Long,
    ): Boolean {
        val pipeline = dspPipeline ?: return false
        return writeDspIfAvailable(pipeline, source, offset, length, encoding, sourceSample)
    }

    private fun writeDspIfAvailable(
        pipeline: DspPcmPipeline,
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        sourceSample: Long?,
    ): Boolean {
        val outputLength = try {
            pipeline.process(source, offset, length, encoding)
        } catch (_: Exception) {
            -1
        } catch (_: LinkageError) {
            -1
        }
        if (outputLength < 0) {
            val failedHotApply = takePendingHotApplyForPipeline(pipeline)
            if (dspPipeline === pipeline) {
                latencyPreservingBypass?.activateSeatControlsForCurrentOutput()
                dspPipeline = null
                activeDspGraphConfig = DspRuntimeConfig.disabled()
            }
            liveUpdateController?.retire(pipeline)
            reportDsp("DSP unavailable: reason=PROCESS_FAILED fallback=LEGACY_PCM")
            reportDspApplyStatus(failedHotApply?.failedStatus ?: DspRendererApplyStatus(
                activeDspGeneration,
                DspRendererApplyPhase.FAILED,
                activeDspGeneration,
            ))
            return false
        }
        acknowledgeHotApply(pipeline)
        if (outputLength > 0) writePcm(pipeline.output, 0, outputLength, sourceSample)
        return true
    }

    private fun acknowledgeHotApply(pipeline: DspPcmPipeline) {
        discardStalePendingHotApply()
        val pending = pendingHotApply.get() ?: return
        val prepared = pending.prepared
        val update = prepared.update
        val hotUpdate = update.hotUpdate ?: return
        if (pipeline !== pending.pipeline || pipeline !== dspPipeline || !pipeline.hasAcknowledged(hotUpdate)) return
        runCatching { afterHotApplyPendingReadForTest?.invoke() }
        if (!pendingHotApply.compareAndSet(pending, null)) return
        activeDspConfig = update.config
        activeDspGraphConfig = prepared.graphConfig
        activeDspGeneration = update.generation
        liveUpdateLatencyFrames.set(trackGenerationLatencyFrames)
        deferredDspGeneration = null
        reportDspApplyStatus(prepared.activeStatus)
    }

    private fun takePendingHotApplyForPipeline(pipeline: DspPcmPipeline): PreparedRendererDspUpdate? {
        val pending = pendingHotApply.get() ?: return null
        if (pending.pipeline !== pipeline || !pendingHotApply.compareAndSet(pending, null)) return null
        return pending.prepared
    }

    private fun discardStalePendingHotApply() {
        val pending = pendingHotApply.get() ?: return
        val hotUpdate = pending.prepared.update.hotUpdate
        val current = !rendererReleased && hotUpdate != null && pending.pipeline === dspPipeline &&
            pending.pipeline === hotBoundPipeline && pending.hotContextToken == hotBoundContextToken &&
            pending.hotContextToken == pending.pipeline.hotUpdateContextToken() &&
            hotUpdate.editorOwnerEpoch == liveUpdateControllerEpoch.get() &&
            hotUpdate.rendererOwnerToken == rendererOwnerToken && hotUpdate.pcmFormatEpoch == pcmFormatEpoch &&
            hotUpdate.hotContextToken == hotBoundContextToken
        if (current || !pendingHotApply.compareAndSet(pending, null)) return
        reportDspApplyStatus(pending.prepared.failedStatus)
    }

    private fun failPendingHotApply() {
        val pending = pendingHotApply.get() ?: return
        if (!pendingHotApply.compareAndSet(pending, null)) return
        reportDspApplyStatus(pending.prepared.failedStatus)
    }

    private fun dspPcmEncoding(encoding: Int): DspPcmEncoding? = when {
        encoding == AndroidAudioFormat.ENCODING_PCM_8BIT -> DspPcmEncoding.PCM8
        encoding == AndroidAudioFormat.ENCODING_PCM_16BIT -> DspPcmEncoding.PCM16
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> DspPcmEncoding.PCM24_PACKED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AndroidAudioFormat.ENCODING_PCM_32BIT -> DspPcmEncoding.PCM32
        encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT -> DspPcmEncoding.PCM_FLOAT
        else -> null
    }

    private fun reportDsp(message: String) {
        runCatching { report(message) }
    }

    internal data class PcmChunk(
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        val outputFloats: FloatArray? = null,
        val outputFrameCount: Int = 0,
    )

    internal fun normalizePcm16(source: ByteArray, offset: Int, length: Int, encoding: Int): PcmChunk? {
        val sourceBytesPerSample = when {
            encoding == AndroidAudioFormat.ENCODING_PCM_16BIT -> 2
            encoding == AndroidAudioFormat.ENCODING_PCM_8BIT -> 1
            encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encoding == AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encoding == AndroidAudioFormat.ENCODING_PCM_32BIT -> 4
            else -> return null
        }
        val channels = pendingDecoderPcmFormat?.channels
            ?: activeDecoderPcmFormat?.channels
            ?: format.channels.coerceIn(1, 2)
        val sourceFrameBytes = sourceBytesPerSample * channels
        val completeLength = length - length % sourceFrameBytes
        if (completeLength != length && !unalignedPcmLogged) {
            unalignedPcmLogged = true
            report("Audio: decoder PCM contained incomplete frame bytes=${length - completeLength}")
        }
        if (completeLength == 0) return null
        if (encoding == AndroidAudioFormat.ENCODING_PCM_16BIT) {
            return PcmChunk(source, offset, completeLength)
        }

        val samples = completeLength / sourceBytesPerSample
        val destinationBytes = samples * 2
        if (normalizedPcm.size < destinationBytes) {
            normalizedPcm = ByteArray(maxOf(destinationBytes, normalizedPcm.size * 2))
        }
        var sourceCursor = offset
        var destinationCursor = 0
        repeat(samples) {
            val sample = when {
                encoding == AndroidAudioFormat.ENCODING_PCM_8BIT ->
                    ((source[sourceCursor].toInt() and 0xff) - 128) shl 8
                encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT -> {
                    val bits = (source[sourceCursor].toInt() and 0xff) or
                        ((source[sourceCursor + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceCursor + 2].toInt() and 0xff) shl 16) or
                        ((source[sourceCursor + 3].toInt() and 0xff) shl 24)
                    val value = Float.fromBits(bits).takeIf { it.isFinite() } ?: 0f
                    (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    encoding == AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val value = (source[sourceCursor].toInt() and 0xff) or
                        ((source[sourceCursor + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceCursor + 2].toInt() and 0xff) shl 16)
                    (if (value and 0x800000 != 0) value or -0x1000000 else value) shr 8
                }
                else -> {
                    val value = (source[sourceCursor].toInt() and 0xff) or
                        ((source[sourceCursor + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceCursor + 2].toInt() and 0xff) shl 16) or
                        ((source[sourceCursor + 3].toInt() and 0xff) shl 24)
                    value shr 16
                }
            }
            normalizedPcm[destinationCursor] = sample.toByte()
            normalizedPcm[destinationCursor + 1] = (sample shr 8).toByte()
            sourceCursor += sourceBytesPerSample
            destinationCursor += 2
        }
        return PcmChunk(normalizedPcm, 0, destinationBytes)
    }

    private fun isSupportedDecoderPcmEncoding(encoding: Int): Boolean =
        encoding == AndroidAudioFormat.ENCODING_PCM_16BIT ||
            encoding == AndroidAudioFormat.ENCODING_PCM_8BIT ||
            encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (encoding == AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED ||
                    encoding == AndroidAudioFormat.ENCODING_PCM_32BIT)

    private fun encodingName(encoding: Int): String = when {
        encoding == AndroidAudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        encoding == AndroidAudioFormat.ENCODING_PCM_8BIT -> "PCM8"
        encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24_PACKED"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AndroidAudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        else -> "UNKNOWN($encoding)"
    }

    private fun writePcm(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        sourceSample: Long? = null,
    ) {
        val track = track
        val writeAdapter = audioTrackWriteAdapterForTest
        if (track == null && writeAdapter == null) return
        val completeLength = length - length % frameBytes
        if (completeLength != length && !unalignedPcmLogged) {
            unalignedPcmLogged = true
            report("Audio: dropping incomplete PCM frame bytes=${length - completeLength} frameBytes=$frameBytes")
        }
        if (completeLength <= 0) return
        if (!firstPcmLogged) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$completeLength",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, completeLength)
        }
        var written = 0
        while (written < completeLength && (lifecycle.isRunning || writeAdapter != null)) {
            val writeLength = if (playbackStarted) {
                completeLength - written
            } else {
                minOf(completeLength - written, prebufferWriteChunkBytes)
            }
            val writeStarted = System.nanoTime()
            val count = try {
                writeAdapter?.write(data, offset + written, writeLength, AudioTrack.WRITE_NON_BLOCKING)
                    ?: track!!.write(data, offset + written, writeLength, AudioTrack.WRITE_NON_BLOCKING)
            } catch (error: Exception) {
                writeStallPolicy.reset()
                if (track != null) recoverTrackAfterOperationFailure(track, "write", error)
                break
            }
            val writeFinishedNs = System.nanoTime()
            maxWriteMs = maxOf(maxWriteMs, (writeFinishedNs - writeStarted) / 1_000_000L)
            val writeStalled = writeStallPolicy.onWriteResult(count, writeFinishedNs)
            if (count == 0) {
                if (writeStalled) {
                    if (track != null) {
                        recoverTrackAfterOperationFailure(
                            track,
                            "write",
                            AudioTrackWriteStallException(TRACK_WRITE_STALL_TIMEOUT_NS),
                        )
                    }
                    break
                }
                Thread.sleep(AUDIO_WRITE_RETRY_MILLIS)
                continue
            }
            if (count < 0) {
                if (track != null) recoverTrackAfterOperationFailure(track, "write", AudioTrackWriteException(count))
                break
            }
            if (!fadeApplied) fadeApplied = true
            trackGenerationContentCommitted = true
            if (sourceSample != null) playbackClockMapper.onPcmWritten(sourceSample)
            written += count
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    if (track != null) {
                        if (!startPlayback(track)) break
                    } else {
                        playbackStarted = true
                    }
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack): Boolean {
        if (this.track !== track) return false
        underrunsAtPlaybackStart = runCatching { track.underrunCount }.getOrDefault(0)
        try {
            track.play()
        } catch (error: Exception) {
            recoverTrackAfterOperationFailure(track, "play", error)
            return false
        }
        playbackStarted = true
        trackOperationRecovery.onPlaybackStarted(System.nanoTime())
        reportPlaybackRoute(track)
        Log.i(TAG, "audio playback started type=${format.payloadType}")
        return true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        val shouldRebuffer = try {
            bufferProgress.shouldRebuffer(
                format.audioType,
                playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart,
                queue.isEmpty(),
                track.playbackHeadPosition,
            )
        } catch (error: Exception) {
            recoverTrackAfterOperationFailure(track, "state", error)
            return
        }
        if (shouldRebuffer) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            try {
                track.pause()
            } catch (error: Exception) {
                recoverTrackAfterOperationFailure(track, "pause", error)
                return
            }
            trackOperationRecovery.onPlaybackPaused()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    private fun recoverTrackAfterOperationFailure(track: AudioTrack, operation: String, error: Exception) {
        if (this.track !== track) return
        val failedFormat = activeDecoderPcmFormat ?: pendingDecoderPcmFormat
        val nowNs = System.nanoTime()
        val retryInMs = trackOperationRecovery.recordFailure(nowNs)
        val failureDetail = when (error) {
            is AudioTrackWriteException ->
                "result=${error.result} code=${audioTrackWriteErrorName(error.result)}"
            is AudioTrackWriteStallException -> "writeStalledMs=${error.stalledForNs / 1_000_000L}"
            else -> "error=${error.javaClass.simpleName}"
        }
        val logKey = "$operation:${error.javaClass.simpleName}:$failureDetail"
        if (logKey != lastTrackOperationFailureLogKey ||
            nowNs - lastTrackOperationFailureLogNs >= TRACK_RETRY_LOG_INTERVAL_NS
        ) {
            lastTrackOperationFailureLogKey = logKey
            lastTrackOperationFailureLogNs = nowNs
            val message = "Audio: AudioTrack $operation failed; recreating output track " +
                "$failureDetail retryInMs=$retryInMs"
            Log.w(TAG, "$message error=${error.javaClass.simpleName}")
            report(message)
        }
        releaseTrack()
        if (failedFormat != null) {
            pendingDecoderPcmFormat = failedFormat
        }
    }

    private fun audioTrackWriteErrorName(result: Int): String = when (result) {
        AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioTrack.ERROR -> "ERROR"
        else -> "UNKNOWN"
    }

    private fun reportPlaybackRoute(track: AudioTrack) {
        if (playbackRouteLogged || this.track !== track) return
        playbackRouteLogged = true
        val routedDevice = runCatching {
            track.routedDevice?.let { "${it.productName}/type=${it.type}" } ?: "null"
        }.getOrDefault("unavailable")
        val output = activeDecoderPcmFormat
        val attributes = activeTrackAttributes
        val message = "Audio: playback route transport=$transport type=${format.payloadType} " +
            "audioType=${format.audioType} routedDevice=$routedDevice " +
            "sampleRate=${output?.sampleRate ?: format.sampleRate} " +
            "channels=${output?.channels ?: format.channels.coerceIn(1, 2)} " +
            "usage=${attributes?.let { usageName(it.usage) } ?: "unavailable"} " +
            "contentType=${attributes?.let { contentTypeName(it.contentType) } ?: "unavailable"}"
        Log.i(TAG, message)
        report(message)
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.let { current ->
            runCatching { current.underrunCount }.getOrDefault(statsLastUnderruns)
        } ?: statsLastUnderruns
        val underrunDelta = (underruns - statsLastUnderruns).coerceAtLeast(0)
        val lastRx = lastArrivalNs.get()
        val timestampAnchorAgeMs = timestampAnchor?.let { ((now - it.nanoTime).coerceAtLeast(0L)) / 1_000_000L } ?: -1L
        val dspTiming = dspPipeline?.diagnostics()?.let { diagnostics ->
            val native = diagnostics.nativeProcessUs
            val pipeline = diagnostics.pipelineProcessUs
            " dspNativeUs=avg:${native.averageUs.toLong()},p50:${native.p50Us},p95:${native.p95Us}," +
                "p99:${native.p99Us},max:${native.maxUs} " +
                "dspPipelineUs=avg:${pipeline.averageUs.toLong()},p50:${pipeline.p50Us}," +
                "p95:${pipeline.p95Us},p99:${pipeline.p99Us},max:${pipeline.maxUs}"
        }.orEmpty()
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+$underrunDelta queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "timestampPollingMs=${timestampPollPolicy.pollingIntervalMs} timestampAnchorAgeMs=$timestampAnchorAgeMs " +
            "feedbackClock=${playbackClockSnapshot?.source ?: "fallbackElapsed"}$dspTiming ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val frames = length / frameBytes
        val sampleRate = activeDecoderPcmFormat?.sampleRate ?: format.sampleRate
        val fadeFrames = minOf(frames, maxOf(1, sampleRate / 100))
        val channels = frameBytes / 2
        for (frame in 0 until fadeFrames) {
            val gainNumerator = frame + 1
            for (channel in 0 until channels) {
                val position = offset + frame * frameBytes + channel * 2
                val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
                val scaled = (sample.toLong() * gainNumerator / fadeFrames).toInt()
                data[position] = scaled.toByte()
                data[position + 1] = (scaled shr 8).toByte()
            }
        }
    }

    private fun updatePlaybackClock() {
        val currentTrack = track
        if (currentTrack == null) {
            playbackClockSnapshot = null
            return
        }
        val nowNs = System.nanoTime()
        val rawHead = runCatching { currentTrack.playbackHeadPosition.toLong() }.getOrNull()
        if (rawHead == null) {
            playbackClockSnapshot = null
            return
        }
        val headFrame = playbackHeadTracker.update(rawHead)
        if (!playbackStarted) {
            timestampAnchor = null
            timestampPollPolicy.reset(nowNs)
        } else {
            val lastFrame = lastObservedPlaybackFrame
            if (lastFrame != null && headFrame > lastFrame) {
                trackOperationRecovery.onPlaybackProgress(nowNs)
            }
        }
        lastObservedPlaybackFrame = headFrame
        if (timestampPollPolicy.shouldQuery(nowNs)) {
            val timestampAvailable = try {
                currentTrack.getTimestamp(audioTimestamp)
            } catch (error: Exception) {
                if (!timestampQueryFailureLogged) {
                    timestampQueryFailureLogged = true
                    report("AudioTimestamp query failed; using playbackHead error=${error.javaClass.simpleName}")
                }
                false
            }
            val timestampFrame = if (timestampAvailable) {
                timestampFrameTracker.update(audioTimestamp.framePosition)
            } else {
                null
            }
            val update = timestampPollPolicy.recordQuery(nowNs, timestampAvailable, timestampFrame)
            if (timestampAvailable) {
                timestampQueryFailureLogged = false
            }
            if (update.enteredProbe) {
                timestampAnchor = null
                if (!timestampUnavailableLogged) {
                    timestampUnavailableLogged = true
                    val message = "AudioTimestamp unavailable; using playbackHead with sparse probes " +
                        "intervalMs=${timestampPollPolicy.pollingIntervalMs}"
                    Log.i(TAG, message)
                    report(message)
                }
            }
            if (update.recoveredFromProbe) {
                timestampUnavailableLogged = false
                val message = "AudioTimestamp became available; returning to warmup polling " +
                    "intervalMs=${timestampPollPolicy.pollingIntervalMs}"
                Log.i(TAG, message)
                report(message)
            }
            if (update.acceptAnchor && timestampFrame != null) {
                timestampAnchor = TimestampAnchor(timestampFrame, audioTimestamp.nanoTime)
                if (!timestampAcquiredLogged) {
                    timestampAcquiredLogged = true
                    val message = "AudioTrack timestamp acquired pollingMs=${timestampPollPolicy.pollingIntervalMs}"
                    Log.i(TAG, message)
                    report(message)
                }
            }
            if (update.becameStable) {
                val message = "AudioTimestamp stable pollingMs=${timestampPollPolicy.pollingIntervalMs}"
                Log.i(TAG, message)
                report(message)
            }
        }
        val anchor = timestampAnchor
        val outputRate = activeDecoderPcmFormat?.sampleRate ?: format.sampleRate
        val timestampNs = anchor?.takeIf { !timestampPollPolicy.unavailable && headFrame >= it.framePosition }?.let {
            it.nanoTime + framesToNanos(headFrame - it.framePosition, outputRate)
        } ?: nowNs
        val clockSource = if (!timestampPollPolicy.unavailable && anchor != null && headFrame >= anchor.framePosition) {
            "audioTrackTimestampAnchored"
        } else {
            "playbackHead"
        }
        playbackClockSnapshot = playbackClockMapper.snapshot(
            framePosition = headFrame,
            timestampNs = timestampNs,
            source = clockSource,
            algorithmicLatencyFrames = trackGenerationLatencyFrames,
        )
    }

    private fun maybeSwitchAacToAdtsFallback() {
        if (format.codec == AudioCodecKind.AAC_LC && aacFallbackPolicy.shouldFallback(System.nanoTime())) {
            startAacAdtsFallback("raw decoder produced no output")
        }
    }

    private fun markRawAacDecoderOutput() {
        aacFallbackPolicy.onDecoderOutput()
        if (!aacStartupCacheEnabled) return
        val cachedCount = aacStartupCache.size
        val cachedBytes = aacStartupCache.byteCount
        aacStartupCache.clear()
        aacStartupCacheEnabled = false
        val message = "Audio: raw AAC produced decoder output; " +
            "startup cache discarded $cachedCount AUs $cachedBytes bytes"
        Log.i(TAG, message)
        report(message)
    }

    private fun startAacAdtsFallback(reason: String, currentAu: PendingAacAu? = null) {
        if (!aacFallbackPolicy.beginFallback()) return
        val replay = aacStartupCache.drainIncluding(currentAu)
        if (currentAu != null && replay.none { it === currentAu }) {
            report("Audio: dropping AAC access unit larger than startup replay limit bytes=${currentAu.bytes.size}")
        }
        aacStartupCacheEnabled = false
        val replayBytes = replay.sumOf { it.bytes.size.toLong() }
        val cacheMessage = "Audio: raw AAC startup cache ${replay.size} accessUnits $replayBytes bytes"
        Log.i(TAG, cacheMessage)
        report(cacheMessage)
        val message = "Audio: switching AAC decoder to ADTS fallback; replaying ${replay.size} cached AUs " +
            "reason=$reason"
        Log.w(TAG, message)
        report(message)
        reportAacInputMode("adts-fallback")
        configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC, useAdts = true)
        if (codec == null) {
            aacStartupCache.clear()
            return
        }
        for (accessUnit in replay) {
            if (!lifecycle.isRunning) break
            feedCodec(
                MediaCodecSupport.adtsFrame(accessUnit.bytes, format.sampleRate, format.channels),
                accessUnit.presentationTimeUs,
            )
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    private fun release() {
        failPendingHotApply()
        val (controller, pendingUpdate) = synchronized(dspUpdatePublicationLock) {
            rendererReleased = true
            liveUpdateControllerEpoch.incrementAndGet()
            val previousController = liveUpdateController
            liveUpdateController = null
            val pending = pendingDspUpdate.getAndSet(null)
            previousController to pending
        }
        playbackClockSnapshot = null
        queue.clear()
        pendingDecoderPcmFormat = null
        aacStartupCache.clear()
        aacStartupCacheEnabled = false
        controller?.close()
        val pipelines = listOf(
            dspPipeline,
            liveFadeFrom,
            liveFadeTo,
            pendingUpdate?.update?.pipeline,
        ).filterNotNull().distinct()
        pipelines.forEach { runCatching { it.close() } }
        dspPipeline = null
        liveFadeFrom = null
        liveFadeTo = null
        liveFadeInProgress = false
        releaseCodec()
        releaseTrack()
        sampleTimestampMapper.reset()
    }

    private fun releaseCodec() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private fun releaseTrack() {
        val track = track
        this.track = null
        DspStreamPolicy.reportPlaybackSampleRate(
            classification.dspRole,
            this,
            null,
            dspConfigProvider::reportPlaybackSampleRate,
        )
        activeDecoderPcmFormat = null
        activeTrackAttributes = null
        playbackRouteLogged = false
        playbackClockSnapshot = null
        playbackStarted = false
        trackOperationRecovery.onPlaybackPaused()
        writeStallPolicy.reset()
        prebufferBytes = 0
        startThresholdBytes = 0
        fadeApplied = false
        statsLastUnderruns = 0
        trackCreationRetry.reset()
        lastTrackFailureLogNs = 0L
        lastTrackFailureLogFormat = null
        lastTrackRetryLogNs = null
        if (track != null) {
            val listener = trackRoutingListener
            trackRoutingListener = null
            playbackRouteListenerGate.release(track) {
                onPlaybackRouteChanged?.invoke(playbackRouteOwnerToken, null, null, false)
            }
            listener?.let { routeListener ->
                runCatching { track.removeOnRoutingChangedListener(routeListener) }
            }
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        bufferProgress = AudioBufferProgress(frameBytes)
        playbackClockMapper.reset(format.sampleRate, format.sampleRate)
        playbackHeadTracker.reset()
        timestampFrameTracker.reset()
        lastObservedPlaybackFrame = null
        timestampAnchor = null
        timestampPollPolicy.reset(System.nanoTime())
        timestampAcquiredLogged = false
        timestampQueryFailureLogged = false
        timestampUnavailableLogged = false
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val LIVE_CROSSFADE_DIVISOR = 50
        const val AUDIO_WRITE_RETRY_MILLIS = 2L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Decoder/render queue for bursts; RTP ordering and its short jitter hold happen upstream.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val RTP_HEADER_BYTES = 12
        const val RTP_SAMPLE_MASK = 0xffff_ffffL
        const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024
        const val MIN_OUTPUT_SAMPLE_RATE = 8_000
        const val MAX_OUTPUT_SAMPLE_RATE = 192_000
    }
}
