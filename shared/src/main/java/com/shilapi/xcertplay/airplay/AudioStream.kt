package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
internal data class AudioStreamTestHooks(
    val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
    val threadFactory: (Runnable, String) -> Thread = { task, name -> Thread(task, name) },
)

class AudioStream private constructor(
    private val key: ByteArray,
    private val streamType: Int,
    private val onDiagnostic: (String) -> Unit,
    private val audioType: String,
    private val wirelessAudio: Boolean,
    private val socketFactory: () -> DatagramSocket,
    private val threadFactory: (Runnable, String) -> Thread,
) : Closeable {
    constructor(
        key: ByteArray,
        streamType: Int = -1,
        onDiagnostic: (String) -> Unit = {},
        audioType: String = "media",
        wirelessAudio: Boolean = false,
    ) : this(
        key,
        streamType,
        onDiagnostic,
        audioType,
        wirelessAudio,
        { DatagramSocket(null) },
        { task, name -> Thread(task, name) },
    )

    internal constructor(
        key: ByteArray,
        streamType: Int = -1,
        onDiagnostic: (String) -> Unit = {},
        audioType: String = "media",
        wirelessAudio: Boolean = false,
        testHooks: AudioStreamTestHooks,
    ) : this(
        key,
        streamType,
        onDiagnostic,
        audioType,
        wirelessAudio,
        testHooks.socketFactory,
        testHooks.threadFactory,
    )

    data class PreparedPorts(val dataPort: Int, val controlPort: Int)

    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private val lifecycleLock = Any()
    private var lifecycleState = LifecycleState.NEW
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    @Volatile private var started = false
    private var packetCallbackFailureLogged = false
    private var rtpCallbackFailureLogged = false
    private val reorderBuffer = RtpReorderBuffer<OrderedRtp>(
        maxPendingPackets = if (audioType == "media") MEDIA_REORDER_WINDOW else LOW_LATENCY_REORDER_WINDOW,
        holdNanos = (if (audioType == "media") MEDIA_REORDER_HOLD_MS else LOW_LATENCY_REORDER_HOLD_MS) * 1_000_000L,
    )
    private var lastReorderStatsNs = 0L

    private data class OrderedRtp(val bytes: ByteArray, val sample: Int)

    fun prepare(): PreparedPorts = synchronized(lifecycleLock) {
        when (lifecycleState) {
            LifecycleState.PREPARED -> return@synchronized PreparedPorts(
                dataSocket?.localPort ?: error("prepared data socket is missing"),
                controlSocket?.localPort ?: error("prepared control socket is missing"),
            )
            LifecycleState.NEW -> Unit
            LifecycleState.STARTED, LifecycleState.CLOSED ->
                throw IllegalStateException("AudioStream cannot be prepared from $lifecycleState")
        }

        var data: DatagramSocket? = null
        var control: DatagramSocket? = null
        try {
            data = bindAnyPort("data", DATA_RECEIVE_BUFFER_BYTES, REORDER_POLL_MS)
            control = bindAnyPort("control", CONTROL_RECEIVE_BUFFER_BYTES)
            dataSocket = data
            controlSocket = control
            lifecycleState = LifecycleState.PREPARED
            PreparedPorts(data.localPort, control.localPort)
        } catch (error: Throwable) {
            runCatching { control?.close() }
            runCatching { data?.close() }
            closed.set(true)
            lifecycleState = LifecycleState.CLOSED
            throw error
        }
    }

    fun start(listener: Listener) {
        var dataWorker: Thread? = null
        var controlWorker: Thread? = null
        var failure: Throwable? = null
        val activation = CountDownLatch(1)
        synchronized(lifecycleLock) {
            when (lifecycleState) {
                LifecycleState.STARTED, LifecycleState.CLOSED -> return
                LifecycleState.NEW -> throw IllegalStateException("AudioStream must be prepared before start")
                LifecycleState.PREPARED -> Unit
            }
            val data = dataSocket ?: throw IllegalStateException("prepared data socket is missing")
            val control = controlSocket ?: throw IllegalStateException("prepared control socket is missing")
            try {
                dataWorker = threadFactory({
                    if (awaitActivation(activation)) runData(data, listener)
                }, "airplay-audio-rx").apply {
                    isDaemon = true
                }
                controlWorker = threadFactory({
                    if (awaitActivation(activation)) runControl(control)
                }, "airplay-rtcp-rx").apply {
                    isDaemon = true
                }
                dataThread = dataWorker
                controlThread = controlWorker
                dataWorker.start()
                controlWorker.start()
                lifecycleState = LifecycleState.STARTED
                activation.countDown()
            } catch (error: Throwable) {
                failure = error
                lifecycleState = LifecycleState.CLOSED
                closed.set(true)
                activation.countDown()
                runCatching { data.close() }
                runCatching { control.close() }
                dataWorker?.interrupt()
                controlWorker?.interrupt()
            }
        }
        if (failure != null) {
            joinWorker(dataWorker)
            joinWorker(controlWorker)
            throw requireNotNull(failure)
        }
    }

    fun listen(listener: Listener): Pair<Int, Int> {
        val ports = prepare()
        start(listener)
        return ports.dataPort to ports.controlPort
    }

    override fun close() {
        val workers = synchronized(lifecycleLock) {
            if (lifecycleState != LifecycleState.CLOSED) {
                lifecycleState = LifecycleState.CLOSED
                closed.set(true)
                dataSocket?.close()
                controlSocket?.close()
                dataThread?.interrupt()
                controlThread?.interrupt()
            }
            dataThread to controlThread
        }
        joinWorker(workers.first)
        joinWorker(workers.second)
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (!closed.get()) deliver(reorderBuffer.poll(System.nanoTime()), listener)
                    logReorderStatsIfDue()
                    continue
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                stats.received(packet.length, if (packet.length >= 12)
                    ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff) else null)
                val wire = packet.data.copyOf(packet.length)
                val packetNumber = receivedPackets.incrementAndGet()
                val header = parseRtpHeader(wire)
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN || header == null) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=${wire.size}",
                        )
                    }
                    notifyPacket(
                        listener,
                        wire,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
                val sealedEnd = wire.size - NONCE_LEN
                val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
                val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
                val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
                val sample = header.timestamp.toInt()

                val payload = try {
                    AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    notifyPacket(listener, wire, null, sample, error)
                    stats.processed()
                    continue
                }
                val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=${payload.size} " +
                            "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                notifyPacket(listener, wire, rtp, sample, null)
                deliver(
                    reorderBuffer.offer(
                        header.sequenceNumber,
                        OrderedRtp(rtp, sample),
                        System.nanoTime(),
                    ),
                    listener,
                )
                logReorderStatsIfDue()
                stats.processed()
            }
        } finally {
            reorderBuffer.clear()
            logReorderStatsIfDue(force = true)
            stats.flush(ended = true)
        }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(role: String, requestedBufferBytes: Int, timeoutMs: Int = 0): DatagramSocket {
        val socket = socketFactory()
        try {
            socket.reuseAddress = true
            runCatching { socket.receiveBufferSize = requestedBufferBytes }
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            if (timeoutMs > 0) socket.soTimeout = timeoutMs
            val actualBufferBytes = runCatching { socket.receiveBufferSize }.getOrDefault(-1)
            val message = "audio UDP role=$role type=$streamType " +
                "requestedReceiveBufferBytes=$requestedBufferBytes actualReceiveBufferBytes=$actualBufferBytes " +
                "port=${socket.localPort}"
            android.util.Log.i(TAG, message)
            onDiagnostic(message)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun joinWorker(worker: Thread?) {
        if (worker == null || worker === Thread.currentThread()) return
        try {
            worker.join(CLOSE_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun deliver(packets: List<RtpDelivery<OrderedRtp>>, listener: Listener) {
        for (delivery in packets) {
            val packet = delivery.value
            try {
                if (!started) {
                    started = true
                    listener.onStarted(packet.sample)
                }
                listener.onRtp(packet.bytes, packet.sample)
            } catch (error: Exception) {
                if (!rtpCallbackFailureLogged) {
                    rtpCallbackFailureLogged = true
                    android.util.Log.w(TAG, "audio callback failed type=$streamType", error)
                    onDiagnostic("Audio RTP callback failed type=$streamType error=${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun notifyPacket(
        listener: Listener,
        wire: ByteArray,
        rtp: ByteArray?,
        sample: Int?,
        error: Throwable?,
    ) {
        try {
            listener.onPacket(wire, rtp, sample, error)
        } catch (failure: Exception) {
            if (!packetCallbackFailureLogged) {
                packetCallbackFailureLogged = true
                android.util.Log.w(TAG, "audio packet capture callback failed type=$streamType", failure)
                onDiagnostic("Audio packet capture callback failed type=$streamType error=${failure.javaClass.simpleName}")
            }
        }
    }

    private fun logReorderStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && lastReorderStatsNs != 0L && now - lastReorderStatsNs < REORDER_STATS_INTERVAL_NS) return
        lastReorderStatsNs = now
        val stats = reorderBuffer.stats()
        val holdMs = if (audioType == "media") MEDIA_REORDER_HOLD_MS else LOW_LATENCY_REORDER_HOLD_MS
        val message = "audio RTP stats type=$streamType audioType=$audioType transport=" +
            "${if (wirelessAudio) "wireless" else "wired"} rx=${stats.received} delivered=${stats.delivered} " +
            "lost=${stats.lost} reordered=${stats.reordered} duplicate=${stats.duplicates} late=${stats.late} " +
            "maxReorderDepth=${stats.maxReorderDepth} maxGapSeqDistance=${stats.maxGap} holdMs=$holdMs " +
            "pending=${reorderBuffer.pendingCount}"
        android.util.Log.i(TAG, message)
        onDiagnostic(message)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
        const val DATA_RECEIVE_BUFFER_BYTES = 1024 * 1024
        const val CONTROL_RECEIVE_BUFFER_BYTES = 128 * 1024
        const val REORDER_POLL_MS = 5
        const val MEDIA_REORDER_WINDOW = 64
        const val LOW_LATENCY_REORDER_WINDOW = 32
        const val MEDIA_REORDER_HOLD_MS = 30
        const val LOW_LATENCY_REORDER_HOLD_MS = 10
        const val REORDER_STATS_INTERVAL_NS = 5_000_000_000L
        const val CLOSE_JOIN_TIMEOUT_MS = 200L
    }

    private fun awaitActivation(activation: CountDownLatch): Boolean = try {
        activation.await()
        !closed.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private enum class LifecycleState { NEW, PREPARED, STARTED, CLOSED }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat? =
        FORMAT_BY_BITS[bits]?.let { (codec, sampleRate, channels) ->
            AudioFormat(codec, sampleRate, channels, payloadType, normalizeAudioType(audioType))
        }

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )

    private val FORMAT_BY_BITS = buildMap {
        PCM_FORMAT.forEach { (bits, format) -> put(bits, Triple(AudioCodecKind.LPCM, format.first, format.second)) }
        put(AirPlayAudioCapabilities.AAC_LC_44_1_KHZ_STEREO, Triple(AudioCodecKind.AAC_LC, 44_100, 2))
        put(AirPlayAudioCapabilities.AAC_LC_48_KHZ_STEREO, Triple(AudioCodecKind.AAC_LC, 48_000, 2))
        put(AirPlayAudioCapabilities.OPUS_16_KHZ_MONO, Triple(AudioCodecKind.OPUS, 16_000, 1))
        put(AirPlayAudioCapabilities.OPUS_24_KHZ_MONO, Triple(AudioCodecKind.OPUS, 24_000, 1))
        put(AirPlayAudioCapabilities.OPUS_48_KHZ_MONO, Triple(AudioCodecKind.OPUS, 48_000, 1))
    }
}

internal fun normalizeAudioType(audioType: String): String = audioType.lowercase(java.util.Locale.ROOT)
