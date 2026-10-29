package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

internal interface MicrophoneUplinkController : Closeable {
    val isActive: Boolean

    fun start(): Boolean

    fun activate()

    fun awaitStopped(timeoutMillis: Long): Boolean = true
}

internal fun microphoneZeroReadBackoffMs(consecutiveEmptyReads: Int): Long =
    consecutiveEmptyReads.coerceIn(1, 10) * 5L

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val isCurrentOwner: () -> Boolean = { true },
) : MicrophoneUplinkController {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    private val activation = CountDownLatch(1)
    @Volatile private var activated = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private var thread: Thread? = null

    override val isActive: Boolean
        get() = running.get() && activated && !closed.get()

    @Synchronized
    override fun start(): Boolean {
        if (closed.get()) return false
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(
                OpusEncoderConfiguration(
                    sampleRate = config.sampleRate,
                    channels = config.channels,
                    bitrate = config.bitrate ?: 48_000,
                    frameMillis = config.frameMillis,
                ),
            ).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val nextRecorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed error=${error.javaClass.simpleName}")
            nextEncoder?.close()
            running.set(false)
            return false
        }
        if (nextRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize")
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed error=${error.javaClass.simpleName}")
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            nextRecorder.startRecording()
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels} " +
                    "frameMs=${config.frameMillis} port=${config.port}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed error=${error.javaClass.simpleName}")
            release()
            false
        }
    }

    override fun activate() {
        activated = true
        activation.countDown()
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        var consecutiveEmptyReads = 0
        try {
            activation.await()
            while (running.get() && isCurrentOwner()) {
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    consecutiveEmptyReads = (consecutiveEmptyReads + 1).coerceAtMost(10)
                    Thread.sleep(microphoneZeroReadBackoffMs(consecutiveEmptyReads))
                    continue
                }
                consecutiveEmptyReads = 0
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        if (!isCurrentOwner()) return
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed error=${error.javaClass.simpleName}")
            }
        } finally {
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        if (!isCurrentOwner()) return
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
        }
    }

    override fun close() {
        val worker = synchronized(this) {
            activation.countDown()
            closed.set(true)
            running.set(false)
            val currentWorker = thread
            if (currentWorker == null) {
                release()
            } else {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                    // Best effort; the capture worker releases its resources when it exits.
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
            currentWorker
        }
        if (worker !== null && worker !== Thread.currentThread()) {
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
            if (!worker.isAlive) release()
        }
    }

    override fun awaitStopped(timeoutMillis: Long): Boolean {
        val worker = thread ?: return true
        if (worker === Thread.currentThread()) return false
        try {
            worker.join(timeoutMillis.coerceAtLeast(0L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !worker.isAlive
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
