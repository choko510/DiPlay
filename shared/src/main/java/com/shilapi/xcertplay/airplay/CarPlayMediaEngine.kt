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
    fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(token: AudioOwnerToken) {}
    fun onIapMessage(bytes: ByteArray) {}
}

data class AudioOwnerToken(val type: Int, val generation: Long)

internal class AudioOwnerSlot<S>(private val type: Int) {
    val lock = Any()
    @Volatile var owner: S? = null
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
        @Volatile var firstSample: Int? = null
        @Volatile var originNs: Long? = null
        @Volatile var microphoneStarted = false
        @Volatile var lastFeedbackClockLogNs = 0L
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioStates = ConcurrentHashMap<StreamKey, AudioState>()
    private val audioSlots = ConcurrentHashMap<Int, AudioOwnerSlot<AudioState>>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null
    internal var beforeAudioReceiverStartForTest: ((AudioOwnerToken) -> Unit)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, session::logDebug)
        sink.setVideoDiagnosticHandler(type) {
            if (it == "first frame rendered") session.videoFrameRendered()
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (streams[streamKey] === screen) {
                val sent = session.sendCommand(mapOf("type" to "forceKeyFrame"))
                session.logDebug("Video recovery: requested keyframe sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = sink.onVideoCodec(type, codec)
                override fun onConfig(codecData: ByteArray) = sink.onVideoConfig(type, codecData)
                override fun onFrame(naluBytes: ByteArray) = sink.onVideoFrame(type, naluBytes)
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onScreenStreamActive(type, false)
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
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
        val audio = AudioStream(
            key = key,
            streamType = type,
            onDiagnostic = session::logDebug,
            audioType = audioType,
            wirelessAudio = session.wirelessAudio,
        )
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

        val slot = audioSlots.computeIfAbsent(type) { AudioOwnerSlot(type) }
        lateinit var state: AudioState
        var oldState: AudioState? = null
        var claimFailure: Throwable? = null
        synchronized(slot.lock) {
            val token = slot.nextToken()
            state = AudioState(streamKey, token, format, connectionId, latencyMs, audio, capture, microphone)
            oldState = slot.owner
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
        if (claimFailure != null) {
            audio.close()
            capture?.close()
            session.logDebug("AirPlay audio SETUP rejected type=$type reason=owner claim failed")
            return null
        }

        oldState?.let(::retireAudioState)
        var startFailure: Throwable? = null
        try {
            beforeAudioReceiverStartForTest?.invoke(state.token)
        } catch (error: Throwable) {
            startFailure = error
        }
        val started = if (startFailure != null) {
            slot.clearIfCurrent(state) { current ->
                audioStates.remove(streamKey, current)
                runCatching { sink.releaseAudioOwner(current.token) }
            }
            false
        } else synchronized(slot.lock) {
            if (slot.owner !== state) {
                false
            } else {
                try {
                    audio.start(audioListener(state, slot))
                    true
                } catch (error: Throwable) {
                    startFailure = error
                    slot.owner = null
                    audioStates.remove(streamKey, state)
                    runCatching { sink.releaseAudioOwner(state.token) }
                    false
                }
            }
        }
        if (!started) {
            retireAudioState(state)
            val reason = if (startFailure == null) "superseded before receiver start" else
                "receiver start failed ${startFailure.javaClass.simpleName}"
            session.logDebug("AirPlay audio SETUP rolled back type=$type generation=${state.token.generation} reason=$reason")
            return null
        }
        return linkedMapOf(
            "type" to type,
            "dataPort" to ports.dataPort,
            "controlPort" to ports.controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
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
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
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
        streams.remove(StreamKey(session, type))?.close()
        detachAudioState(session, type)?.let(::retireAudioState)
        if (isScreenStreamType(type)) sink.onScreenStreamActive(type, false)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingIapTunnel(session)
        val sessionStreams = streams.entries.filter { it.key.session === session }.toList()
        sessionStreams
            .filter { isScreenStreamType(it.key.type) }
            .forEach { sink.onScreenStreamActive(it.key.type, false) }
        sessionStreams.forEach { (key, stream) ->
            if (streams.remove(key, stream)) stream.close()
        }
        audioSlots.values.toList().forEach { slot ->
            val current = slot.owner?.takeIf { it.key.session === session } ?: return@forEach
            var detached: AudioState? = null
            slot.clearIfCurrent(current) { state ->
                audioStates.remove(state.key, state)
                runCatching { sink.releaseAudioOwner(state.token) }
                detached = state
            }
            detached?.let(::retireAudioState)
        }
    }

    private fun audioListener(state: AudioState, slot: AudioOwnerSlot<AudioState>): AudioStream.Listener =
        object : AudioStream.Listener {
            override fun onStarted(firstSample: Int) {
                if (slot.owner !== state) return
                state.firstSample = firstSample
                state.originNs = System.nanoTime()
                sink.onAudioStarted(state.token, state.format, firstSample)
                state.microphoneConfig?.let { microphone ->
                    if (slot.owner === state) {
                        state.microphoneStarted = true
                        sink.onMicrophoneStarted(state.token, microphone)
                    }
                }
            }

            override fun onRtp(rtp: ByteArray, sample: Int) {
                if (slot.owner === state) sink.onAudioRtp(state.token, state.format, rtp, sample)
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

    private fun detachAudioState(session: AirPlaySession, type: Int): AudioState? {
        val slot = audioSlots[type] ?: return null
        val current = slot.owner?.takeIf { it.key.session === session } ?: return null
        var detached: AudioState? = null
        slot.clearIfCurrent(current) { state ->
            audioStates.remove(state.key, state)
            runCatching { sink.releaseAudioOwner(state.token) }
            detached = state
        }
        return detached
    }

    private fun retireAudioState(state: AudioState) {
        runCatching { state.stream.close() }
        runCatching { sink.onAudioStopped(state.token) }
        if (state.microphoneStarted) runCatching { sink.onMicrophoneStopped(state.token) }
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

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (format.audioType != "telephony" && format.audioType != "speechrecognition") return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
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
