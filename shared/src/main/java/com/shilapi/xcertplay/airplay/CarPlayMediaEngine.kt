package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun claimAudioOwner(token: AudioOwnerToken) {}
    fun releaseAudioOwner(token: AudioOwnerToken) {}
    fun onAudioStarted(token: AudioOwnerToken, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(token: AudioOwnerToken, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun audioPlaybackClock(token: AudioOwnerToken): AudioPlaybackClock? = null
    fun onAudioStopped(token: AudioOwnerToken) {}
    fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean = false
    fun isMicrophoneActive(token: AudioOwnerToken): Boolean = true
    fun onMicrophoneStopped(token: AudioOwnerToken) {}
    fun onIapMessage(bytes: ByteArray) {}
}

internal fun currentScreenListener(
    type: Int,
    sink: MediaSink,
    callbackLock: Any = Any(),
    isCurrent: () -> Boolean,
    onClosed: (Throwable?) -> Unit,
): ScreenStream.Listener = object : ScreenStream.Listener {
    override fun onCodec(codec: VideoCodec) {
        synchronized(callbackLock) {
            if (isCurrent()) sink.onVideoCodec(type, codec)
        }
    }

    override fun onConfig(codecData: ByteArray) {
        synchronized(callbackLock) {
            if (isCurrent()) sink.onVideoConfig(type, codecData)
        }
    }

    override fun onFrame(naluBytes: ByteArray) {
        synchronized(callbackLock) {
            if (isCurrent()) sink.onVideoFrame(type, naluBytes)
        }
    }

    override fun onClosed(cause: Throwable?) {
        val current = synchronized(callbackLock) { isCurrent() }
        if (current) onClosed(cause)
    }
}

data class AudioOwnerToken(val type: Int, val generation: Long)

internal class AudioOwnerSlot<S>(private val type: Int) {
    val lock = Any()
    @Volatile var owner: S? = null
    @Volatile var active: S? = null
    private var generation = 0L

    fun nextToken(): AudioOwnerToken {
        check(Thread.holdsLock(lock))
        check(generation < Long.MAX_VALUE) { "audio owner generation exhausted for type=$type" }
        generation += 1
        return AudioOwnerToken(type, generation)
    }

    fun clearIfCurrent(expected: S, onCleared: (S) -> Unit): Boolean = synchronized(lock) {
        if (owner !== expected) {
            false
        } else {
            owner = null
            onCleared(expected)
            true
        }
    }
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
    )

    private class AudioState(
        val key: StreamKey,
        val token: AudioOwnerToken,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        val stream: AudioStream,
        val capture: AudioPacketCapture?,
        val microphoneConfig: MicrophoneConfig?,
    ) {
        @Volatile var replacedOwner: AudioState? = null
        @Volatile var firstSample: Int? = null
        @Volatile var originNs: Long? = null
        @Volatile var sinkStarted = false
        @Volatile var microphoneStarted = false
        @Volatile var microphoneStarting = false
        @Volatile var nextMicrophoneAttemptNs = 0L
        @Volatile var lastFeedbackClockLogNs = 0L
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val screenLifecycleLocks = ConcurrentHashMap<Int, Any>()
    private val screenStreamOwners = ConcurrentHashMap<Int, StreamKey>()
    private val audioStates = ConcurrentHashMap<StreamKey, AudioState>()
    private val audioSlots = ConcurrentHashMap<Int, AudioOwnerSlot<AudioState>>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null
    internal var beforeAudioOwnerCommitForTest: ((AirPlaySession, Int) -> Unit)? = null
    internal var beforeAudioReceiverStartForTest: ((AudioOwnerToken) -> Unit)? = null
    internal var audioStreamTestHooksForTest: AudioStreamTestHooks? = null
    internal var monotonicTimeForTest: (() -> Long)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        if (session.isClosed) return null
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        val callbackLock = screenLifecycleLock(type)
        return synchronized(callbackLock) {
            if (session.isClosed) return@synchronized null
            Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
            val screen = ScreenStream(key, session::logDebug)
            val port = screen.listen(
                currentScreenListener(
                    type = type,
                    sink = sink,
                    callbackLock = callbackLock,
                    isCurrent = { screenStreamOwners[type] == streamKey && streams[streamKey] === screen },
                ) { cause ->
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    val shouldCloseSession = synchronized(callbackLock) {
                        if (screenStreamOwners[type] == streamKey && streams.remove(streamKey, screen)) {
                            screenStreamOwners.remove(type, streamKey)
                            sink.onScreenStreamActive(type, false)
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldCloseSession) session.close()
                },
            )
            val previousOwner = screenStreamOwners.put(type, streamKey)
            val previousForKey = streams.put(streamKey, screen)
            val previousOwnerStream = previousOwner
                ?.takeIf { it != streamKey }
                ?.let { streams.remove(it) }
            if (previousOwner != null || previousForKey != null) {
                previousForKey?.close()
                if (previousOwnerStream !== previousForKey) previousOwnerStream?.close()
                sink.onScreenStreamActive(type, false)
            }
            sink.onScreenStreamActive(type, true)
            sink.setVideoDiagnosticHandler(type) {
                if (screenStreamOwners[type] == streamKey && streams[streamKey] === screen) {
                    if (it == "first frame submitted to surface") session.videoFrameSubmittedToSurface(type)
                    session.logDebug("Video: $it")
                }
            }
            sink.setVideoRecoveryHandler(type) {
                if (screenStreamOwners[type] == streamKey && streams[streamKey] === screen) {
                    val sent = session.sendCommand(mapOf("type" to "forceKeyFrame"))
                    session.logDebug("Video recovery: requested keyframe sent=$sent")
                }
            }
            port
        }
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        if (session.isClosed) return null
        val streamKey = StreamKey(session, type)
        val audioType = normalizeAudioType(stream["audioType"]?.toString() ?: "default")
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: run {
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=missing audioFormat")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits(formatBits, type, audioType) ?: run {
            session.logDebug(
                "AirPlay audio SETUP rejected type=$type audioType=$audioType " +
                    "formatBits=0x${formatBits.toString(16)} reason=unsupported format",
            )
            return null
        }
        val advertisedMask = session.advertisedAudioOutputMask(type, audioType) ?: run {
            session.logDebug(
                "AirPlay audio SETUP rejected type=$type audioType=$audioType " +
                    "formatBits=0x${formatBits.toString(16)} reason=no advertised capability",
            )
            return null
        }
        if (advertisedMask and formatBits != formatBits) {
            session.logDebug(
                "AirPlay audio SETUP rejected type=$type audioType=$audioType " +
                    "formatBits=0x${formatBits.toString(16)} advertised=0x${advertisedMask.toString(16)} " +
                    "reason=format not advertised",
            )
            return null
        }
        val key = try {
            outputKey(session, stream)
        } catch (error: Exception) {
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=output key derivation failed")
            null
        } ?: return null

        Log.i(
            TAG,
            "AirPlay audio negotiated transport=${if (session.wirelessAudio) "wireless" else "wired"} " +
                "type=$type audioType=$audioType " +
                "formatBits=0x${formatBits.toString(16)} codec=${format.codec} " +
                "sampleRate=${format.sampleRate} channels=${format.channels} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val microphone = try {
            microphoneConfig(session, type, stream, format)
        } catch (error: Exception) {
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=microphone key derivation failed")
            return null
        }
        val capture = try {
            audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        } catch (error: Exception) {
            session.logDebug("AirPlay audio capture unavailable type=$type error=${error.javaClass.simpleName}")
            null
        }
        val audio = newAudioStream(session, type, audioType, key)
        val ports = try {
            audio.prepare()
        } catch (error: Exception) {
            audio.close()
            capture?.close()
            session.logDebug(
                "AirPlay audio SETUP rejected type=$type reason=socket preparation failed " +
                    "error=${error.javaClass.simpleName}",
            )
            return null
        }

        try {
            beforeAudioOwnerCommitForTest?.invoke(session, type)
        } catch (error: Throwable) {
            audio.close()
            capture?.close()
            throw error
        }

        val slot = audioSlots.computeIfAbsent(type) { AudioOwnerSlot(type) }
        lateinit var state: AudioState
        var oldState: AudioState? = null
        var claimFailure: Throwable? = null
        var closedBeforeCommit = false
        synchronized(slot.lock) {
            if (session.isClosed) {
                closedBeforeCommit = true
            } else {
                val token = slot.nextToken()
                oldState = slot.owner
                state = AudioState(streamKey, token, format, connectionId, latencyMs, audio, capture, microphone)
                    .apply { replacedOwner = oldState }
                slot.owner = state
                oldState?.let { audioStates.remove(it.key, it) }
                audioStates[streamKey] = state
                try {
                    sink.claimAudioOwner(token)
                } catch (error: Throwable) {
                    claimFailure = error
                    slot.owner = oldState
                    audioStates.remove(streamKey, state)
                    oldState?.let { audioStates[it.key] = it }
                    runCatching { sink.releaseAudioOwner(token) }
                    oldState?.let { previous -> runCatching { sink.claimAudioOwner(previous.token) } }
                }
            }
        }
        if (closedBeforeCommit) {
            audio.close()
            capture?.close()
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=session closed before owner commit")
            return null
        }
        if (claimFailure != null) {
            audio.close()
            capture?.close()
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=owner claim failed")
            return null
        }

        var startFailure: Throwable? = null
        try {
            beforeAudioReceiverStartForTest?.invoke(state.token)
        } catch (error: Throwable) {
            startFailure = error
        }
        var rollbackCleanup = emptyList<AudioState>()
        var retireAfterStart = emptyList<AudioState>()
        val started = if (startFailure != null) {
            rollbackCleanup = rollbackAudioState(state, slot)
            false
        } else synchronized(slot.lock) {
            if (slot.owner !== state) {
                false
            } else {
                try {
                    audio.start(audioListener(state, slot))
                    val previousActive = slot.active
                    val previousOwner = state.replacedOwner
                    slot.active = state
                    state.replacedOwner = null
                    retireAfterStart = listOfNotNull(previousActive, previousOwner)
                        .filter { it !== state }
                        .distinct()
                    true
                } catch (error: Throwable) {
                    startFailure = error
                    rollbackCleanup = rollbackAudioStateLocked(state, slot)
                    false
                }
            }
        }
        if (!started) {
            (rollbackCleanup + state).distinct().forEach(::retireAudioState)
            val reason = if (startFailure == null) "superseded before receiver start" else
                "receiver start failed ${startFailure.javaClass.simpleName}"
            session.logDebug("AirPlay audio SETUP rolled back type=$type generation=${state.token.generation} reason=$reason")
            return null
        }
        retireAfterStart.forEach(::retireAudioState)
        return linkedMapOf(
            "type" to type,
            "dataPort" to ports.dataPort,
            "controlPort" to ports.controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    private fun newAudioStream(
        session: AirPlaySession,
        type: Int,
        audioType: String,
        key: ByteArray,
    ): AudioStream {
        val hooks = audioStreamTestHooksForTest
        return if (hooks == null) {
            AudioStream(
                key = key,
                streamType = type,
                onDiagnostic = session::logDebug,
                audioType = audioType,
                wirelessAudio = session.wirelessAudio,
            )
        } else {
            AudioStream(
                key = key,
                streamType = type,
                onDiagnostic = session::logDebug,
                audioType = audioType,
                wirelessAudio = session.wirelessAudio,
                testHooks = hooks,
            )
        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed error=${error.javaClass.simpleName}")
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioSlots.values.mapNotNull { slot ->
            synchronized(slot.lock) {
                slot.owner?.takeIf { it.key.session === session }?.let { slot to it }
            }
        }
        if (active.isEmpty()) return null
        val streams = arrayListOf<Map<String, Any?>>()
        active.forEach { (slot, state) ->
            val entry = linkedMapOf<String, Any?>(
                "type" to state.token.type,
                "sampleRate" to state.format.sampleRate,
            )
            val nowNs = System.nanoTime()
            val playbackClock = sink.audioPlaybackClock(state.token)
            if (playbackClock != null) {
                entry["streamConnectionID"] = unsignedPlistInteger(state.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = playbackClock.monotonicTimestampNs
                entry["sampleTime"] = playbackClock.samplePosition
                entry["sampleRate"] = playbackClock.sourceSampleRate
                if (nowNs - state.lastFeedbackClockLogNs >= FEEDBACK_CLOCK_LOG_INTERVAL_NS) {
                    state.lastFeedbackClockLogNs = nowNs
                    session.logDebug(
                        "Audio feedback clock source=${playbackClock.source} type=${state.token.type} " +
                            "baseSample=${playbackClock.baseRtpSample} playedFrames=${playbackClock.playedFrames} " +
                            "contentFrames=${playbackClock.contentFrames} latencyFrames=${playbackClock.algorithmicLatencyFrames} " +
                            "sampleTime=${playbackClock.samplePosition} sourceRate=${playbackClock.sourceSampleRate} " +
                            "outputRate=${playbackClock.outputSampleRate}",
                    )
                }
            } else {
                val firstSample = state.firstSample
                val originNs = state.originNs
                if (firstSample != null && originNs != null) {
                    val elapsedSec = Math.max(
                        0.0,
                        (nowNs - originNs) / 1e9 - state.playoutLatencyMs / 1000.0,
                    )
                    val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                    val sampleTime = (firstUnsigned + Math.round(elapsedSec * state.format.sampleRate)) and
                        0xffff_ffffL
                    entry["streamConnectionID"] = unsignedPlistInteger(state.connectionId ?: 0L)
                    entry["timestamp"] = session.syncedNtp()
                    entry["timestampRawNs"] = nowNs
                    entry["sampleTime"] = sampleTime
                    if (nowNs - state.lastFeedbackClockLogNs >= FEEDBACK_CLOCK_LOG_INTERVAL_NS) {
                        state.lastFeedbackClockLogNs = nowNs
                        session.logDebug(
                            "Audio feedback clock source=fallbackElapsed type=${state.token.type} " +
                                "latencyMs=${state.playoutLatencyMs}",
                        )
                    }
                }
            }
            if (synchronized(slot.lock) { slot.owner === state }) streams.add(entry)
        }
        if (streams.isEmpty()) return null
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        val streamKey = StreamKey(session, type)
        if (isScreenStreamType(type)) {
            val callbackLock = screenLifecycleLock(type)
            synchronized(callbackLock) {
                val stream = streams.remove(streamKey)
                val wasOwner = screenStreamOwners.remove(type, streamKey)
                stream?.close()
                if (wasOwner || screenStreamOwners[type] == null) {
                    sink.onScreenStreamActive(type, false)
                }
            }
        } else {
            streams.remove(streamKey)?.close()
        }
        detachAudioStates(session, type).forEach(::retireAudioState)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingIapTunnel(session)
        listOf(STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN).forEach { type ->
            val key = StreamKey(session, type)
            val callbackLock = screenLifecycleLock(type)
            synchronized(callbackLock) {
                val screen = streams.remove(key)
                val wasOwner = screenStreamOwners.remove(type, key)
                if (wasOwner || (screenStreamOwners[type] == null && screen != null)) {
                    sink.onScreenStreamActive(key.type, false)
                }
                screen?.close()
            }
        }
        val sessionStreams = streams.entries.filter { it.key.session === session }.toList()
        sessionStreams.forEach { (key, stream) ->
            if (streams.remove(key, stream)) stream.close()
        }
        audioSlots.keys.toList().forEach { type ->
            detachAudioStates(session, type).forEach(::retireAudioState)
        }
    }

    private fun audioListener(state: AudioState, slot: AudioOwnerSlot<AudioState>): AudioStream.Listener =
        object : AudioStream.Listener {
            override fun onStarted(firstSample: Int) {
                state.firstSample = firstSample
                state.originNs = System.nanoTime()
                val microphone = synchronized(slot.lock) {
                    if (slot.owner === state) startAudioSinkLocked(state, slot) else null
                }
                microphone?.let { startMicrophone(state, slot, it) }
            }

            override fun onRtp(rtp: ByteArray, sample: Int) {
                if (slot.owner !== state) return
                val microphone = synchronized(slot.lock) {
                    if (slot.owner !== state) {
                        null
                    } else if (!state.sinkStarted) {
                        if (state.firstSample == null) {
                            state.firstSample = sample
                            state.originNs = System.nanoTime()
                        }
                        startAudioSinkLocked(state, slot)
                    } else {
                        startMicrophoneIfDueLocked(state, slot)
                    }
                }
                microphone?.let { startMicrophone(state, slot, it) }
                if (slot.owner === state && state.sinkStarted) {
                    sink.onAudioRtp(state.token, state.format, rtp, sample)
                }
            }

            override fun onPacket(
                wire: ByteArray,
                rtp: ByteArray?,
                sample: Int?,
                error: Throwable?,
            ) {
                if (slot.owner === state) state.capture?.record(wire, rtp, sample, error)
            }
        }

    private fun startAudioSinkLocked(
        state: AudioState,
        slot: AudioOwnerSlot<AudioState>,
    ): MicrophoneConfig? {
        check(Thread.holdsLock(slot.lock))
        if (slot.owner !== state || state.sinkStarted) return null
        val firstSample = state.firstSample ?: return null
        sink.onAudioStarted(state.token, state.format, firstSample)
        state.sinkStarted = true
        return startMicrophoneIfDueLocked(state, slot)
    }

    private fun startMicrophoneIfDueLocked(
        state: AudioState,
        slot: AudioOwnerSlot<AudioState>,
    ): MicrophoneConfig? {
        check(Thread.holdsLock(slot.lock))
        if (slot.owner !== state || !state.sinkStarted || state.microphoneStarting) return null
        if (state.microphoneStarted) {
            if (sink.isMicrophoneActive(state.token)) return null
            state.microphoneStarted = false
        }
        val microphone = state.microphoneConfig ?: return null
        if (monotonicTimeNs() < state.nextMicrophoneAttemptNs) return null
        state.microphoneStarting = true
        return microphone
    }

    private fun startMicrophone(
        state: AudioState,
        slot: AudioOwnerSlot<AudioState>,
        microphone: MicrophoneConfig,
    ) {
        var started = false
        try {
            started = sink.onMicrophoneStarted(state.token, microphone)
        } catch (error: Exception) {
            Log.w(TAG, "Microphone start failed; retrying error=${error.javaClass.simpleName}")
        } finally {
            synchronized(slot.lock) {
                state.microphoneStarted = started
                if (slot.owner === state) {
                    state.nextMicrophoneAttemptNs = monotonicTimeNs() + MICROPHONE_RETRY_BACKOFF_NS
                }
                state.microphoneStarting = false
            }
        }
    }

    private fun monotonicTimeNs(): Long = monotonicTimeForTest?.invoke() ?: System.nanoTime()

    private fun rollbackAudioState(state: AudioState, slot: AudioOwnerSlot<AudioState>): List<AudioState> =
        synchronized(slot.lock) { rollbackAudioStateLocked(state, slot) }

    private fun rollbackAudioStateLocked(
        state: AudioState,
        slot: AudioOwnerSlot<AudioState>,
    ): List<AudioState> {
        check(Thread.holdsLock(slot.lock))
        if (slot.owner !== state) return emptyList()

        val previousActive = slot.active
        val fallback = previousActive?.takeIf { !it.key.session.isClosed }
        val cleanup = mutableListOf<AudioState>()
        slot.owner = fallback
        audioStates.remove(state.key, state)
        if (fallback != null) {
            audioStates[fallback.key] = fallback
            runCatching { sink.claimAudioOwner(fallback.token) }
            runCatching { sink.releaseAudioOwner(state.token) }
        } else {
            runCatching { sink.releaseAudioOwner(state.token) }
            if (previousActive != null) {
                slot.active = null
                cleanup.add(previousActive)
            }
        }
        state.replacedOwner?.takeIf { it !== fallback && it !== previousActive }?.let(cleanup::add)
        state.replacedOwner = null
        return cleanup.distinct()
    }

    private fun detachAudioStates(session: AirPlaySession, type: Int): List<AudioState> {
        val slot = audioSlots[type] ?: return emptyList()
        return synchronized(slot.lock) {
            val cleanup = mutableListOf<AudioState>()
            val current = slot.owner
            val active = slot.active
            pruneClosedAudioPredecessors(slot.owner, session, cleanup)
            if (current?.key?.session === session) {
                audioStates.remove(current.key, current)
                val fallback = active?.takeIf {
                    it !== current && it.key.session !== session && !it.key.session.isClosed
                }
                slot.owner = fallback
                if (fallback != null) {
                    audioStates[fallback.key] = fallback
                    runCatching { sink.claimAudioOwner(fallback.token) }
                    runCatching { sink.releaseAudioOwner(current.token) }
                } else {
                    runCatching { sink.releaseAudioOwner(current.token) }
                    if (active != null) {
                        slot.active = null
                        cleanup.add(active)
                    }
                }
                current.replacedOwner?.takeIf { it !== fallback && it !== active }?.let(cleanup::add)
                current.replacedOwner = null
                cleanup.add(current)
            }
            if (active?.key?.session === session && active !== current) {
                if (slot.active === active) slot.active = null
                if (slot.owner === active) {
                    slot.owner = null
                    audioStates.remove(active.key, active)
                }
                runCatching { sink.releaseAudioOwner(active.token) }
                slot.owner?.takeIf { it.replacedOwner === active }?.replacedOwner = null
                cleanup.add(active)
            }
            cleanup.distinct()
        }
    }

    private fun pruneClosedAudioPredecessors(
        state: AudioState?,
        session: AirPlaySession,
        cleanup: MutableList<AudioState>,
    ) {
        var parent = state
        var predecessor = parent?.replacedOwner
        while (parent != null && predecessor != null) {
            val next = predecessor.replacedOwner
            if (predecessor.key.session === session) {
                parent.replacedOwner = next
                cleanup.add(predecessor)
            } else {
                parent = predecessor
            }
            predecessor = next
        }
    }

    private fun retireAudioState(state: AudioState) {
        runCatching { state.stream.close() }
        runCatching { sink.onAudioStopped(state.token) }
        if (state.microphoneStarted || state.microphoneStarting) {
            runCatching { sink.onMicrophoneStopped(state.token) }
        }
        runCatching { state.capture?.close() }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun screenLifecycleLock(type: Int): Any =
        screenLifecycleLocks.computeIfAbsent(type) { Any() }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || !MicrophoneRequestPolicy.wantsMicrophone(type, stream)) return null
        if (format.channels != 1) return null
        val port = MicrophoneRequestPolicy.destinationPort(stream) ?: return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
        const val FEEDBACK_CLOCK_LOG_INTERVAL_NS = 5_000_000_000L
        const val MICROPHONE_RETRY_BACKOFF_NS = 750_000_000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
