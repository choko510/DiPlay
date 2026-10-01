package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioPlaybackClock
import com.shilapi.xcertplay.airplay.AudioPlaybackClockMapper
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.RtpSampleTimestampMapper
import com.shilapi.xcertplay.airplay.Unsigned32FrameTracker
import com.shilapi.xcertplay.airplay.framesToNanos
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
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
    onVideoFrameRendered: ((Int) -> Unit)? = null,
) : MediaSink {
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    @Volatile private var videoFrameRendered = onVideoFrameRendered
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    fun setVideoFrameRenderedListener(listener: ((Int) -> Unit)?) {
        videoFrameRendered = listener
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        audioRenderer(type, format).start()
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        audioRenderers.remove(type)?.close()
    }

    override fun audioPlaybackClock(type: Int): AudioPlaybackClock? =
        audioRenderers[type]?.playbackClock()

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(type) { MicrophoneUplink(config) }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { requestVideoRecovery(type) },
                report = { videoDiagnosticHandlers[type]?.invoke(it) },
                onFirstFrameRendered = { videoFrameRendered?.invoke(type) },
            )
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format = format,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            navigationAudioRoute = navigationAudioRoute,
            transport = transport,
            audioManager = audioManager,
            mediaBufferMillis = mediaBufferMillis,
            report = onAudioDiagnostic,
        ).also { audioRenderers[type] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val onFirstFrameRendered: () -> Unit,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private val stats = VideoStats()
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

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
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
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
        releaseDecoder()
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
        val next = firstSuccessfulDecoderAttempt(attempts) { attempt ->
            tryConfigure(mime, csd, surface, attempt, attemptedFormats)
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
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
                runCatching { codec.release() }
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
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
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
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
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
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
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
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        onFirstFrameRendered()
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
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
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
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

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
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
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val navigationAudioRoute: NavigationAudioRoute,
    private val transport: String,
    private val audioManager: AudioManager?,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)
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
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
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
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun playbackClock(): AudioPlaybackClock? = playbackClockSnapshot

    fun start() {
        if (started) return
        started = true
        report(
            "Audio: stream start transport=$transport type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} sampleRate=${format.sampleRate} channels=${format.channels} " +
                "routingPolicy=${if (advancedAudioChannelMapping) "AUTOMOTIVE_BUS" else navigationAudioRoute}",
        )
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> createTrack(
                    DecoderPcmFormat(
                        format.sampleRate,
                        format.channels.coerceIn(1, 2),
                        AndroidAudioFormat.ENCODING_PCM_16BIT,
                    ),
                )
            }
            while (running) {
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
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
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
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
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
        activeTrackAttributes = attributes
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
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
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
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationRoute = navigationAudioRoute,
        )
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

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
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
                    writePcm(pcmBytes, sourceSample = packet.sample.toLong() and 0xffff_ffffL)
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
                                "accessUnits=${parsed.accessUnits.size} bytes=${firstUnit.size} " +
                                "head=${firstUnit.copyOf(minOf(firstUnit.size, 16)).toHexString()}",
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
                                "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                    "head=${accessUnit.toHexString()}",
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
                Log.w(TAG, "audio packet dropped codec=${format.codec}", error)
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
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
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
        while (running) {
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
                                    val normalized = normalizePcm16(pcm, 0, size, decoderEncoding)
                                    if (normalized != null) {
                                        writePcm(
                                            normalized.bytes,
                                            normalized.offset,
                                            normalized.length,
                                            sampleTimestampMapper.sampleAtPresentationTimeUs(info.presentationTimeUs),
                                        )
                                    }
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
                if (running && !decoderOutputFailureLogged) {
                    decoderOutputFailureLogged = true
                    Log.w(TAG, "audio decoder output failed codec=${format.codec}", error)
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
        trackOperationRecovery.reset()
        pendingDecoderPcmFormat = next
        rejectedDecoderOutputFormat = null
        val message = "Audio: decoder output format sampleRate=$sampleRate channels=$channels " +
            "encoding=${encodingName(encoding)} fallback=${next.fallbackReason}"
        Log.i(TAG, message)
        report(message)
        createTrack(next)
    }

    private fun DecoderPcmFormat.hasSameTrackFormat(other: DecoderPcmFormat): Boolean =
        sampleRate == other.sampleRate && channels == other.channels && encoding == other.encoding

    private data class PcmChunk(val bytes: ByteArray, val offset: Int, val length: Int)

    private fun normalizePcm16(source: ByteArray, offset: Int, length: Int, encoding: Int): PcmChunk? {
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
        val track = track ?: return
        val completeLength = length - length % frameBytes
        if (completeLength != length && !unalignedPcmLogged) {
            unalignedPcmLogged = true
            report("Audio: dropping incomplete PCM frame bytes=${length - completeLength} frameBytes=$frameBytes")
        }
        if (completeLength <= 0) return
        if (!firstPcmLogged) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(completeLength, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$completeLength " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, completeLength)
        }
        var written = 0
        while (written < completeLength && running) {
            val writeLength = if (playbackStarted) {
                completeLength - written
            } else {
                minOf(completeLength - written, prebufferWriteChunkBytes)
            }
            val writeStarted = System.nanoTime()
            val count = try {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_NON_BLOCKING)
            } catch (error: Exception) {
                writeStallPolicy.reset()
                recoverTrackAfterOperationFailure(track, "write", error)
                break
            }
            val writeFinishedNs = System.nanoTime()
            maxWriteMs = maxOf(maxWriteMs, (writeFinishedNs - writeStarted) / 1_000_000L)
            val writeStalled = writeStallPolicy.onWriteResult(count, writeFinishedNs)
            if (count == 0) {
                if (writeStalled) {
                    recoverTrackAfterOperationFailure(
                        track,
                        "write",
                        AudioTrackWriteStallException(TRACK_WRITE_STALL_TIMEOUT_NS),
                    )
                    break
                }
                Thread.sleep(AUDIO_WRITE_RETRY_MILLIS)
                continue
            }
            if (count < 0) {
                recoverTrackAfterOperationFailure(track, "write", AudioTrackWriteException(count))
                break
            }
            if (!fadeApplied) fadeApplied = true
            if (sourceSample != null) playbackClockMapper.onPcmWritten(sourceSample)
            written += count
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    if (!startPlayback(track)) break
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
            Log.w(TAG, message, error)
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
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+$underrunDelta queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "timestampPollingMs=${timestampPollPolicy.pollingIntervalMs} timestampAnchorAgeMs=$timestampAnchorAgeMs " +
            "feedbackClock=${playbackClockSnapshot?.source ?: "fallbackElapsed"} ended=$force"
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
        playbackClockSnapshot = playbackClockMapper.snapshot(headFrame, timestampNs, clockSource)
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
            if (!running) break
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
        playbackClockSnapshot = null
        queue.clear()
        pendingDecoderPcmFormat = null
        aacStartupCache.clear()
        aacStartupCacheEnabled = false
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
