package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer

internal interface OpusCodec : Closeable {
    fun configure(format: MediaFormat)
    fun start()
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun stop()
}

internal fun interface OpusCodecFactory {
    fun create(): OpusCodec
}

private class AndroidOpusCodec(private val delegate: MediaCodec) : OpusCodec {
    override fun configure(format: MediaFormat) =
        delegate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

    override fun start() = delegate.start()
    override fun dequeueInputBuffer(timeoutUs: Long): Int = delegate.dequeueInputBuffer(timeoutUs)
    override fun getInputBuffer(index: Int): ByteBuffer? = delegate.getInputBuffer(index)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int =
        delegate.dequeueOutputBuffer(info, timeoutUs)
    override fun getOutputBuffer(index: Int): ByteBuffer? = delegate.getOutputBuffer(index)
    override fun releaseOutputBuffer(index: Int, render: Boolean) = delegate.releaseOutputBuffer(index, render)
    override fun stop() = delegate.stop()
    override fun close() = delegate.release()
}

internal data class OpusEncoderConfiguration(
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val frameMillis: Int,
) {
    val samplesPerFrame: Int = maxOf(1, sampleRate * frameMillis / 1000)
    val frameBytes: Int = samplesPerFrame * channels * 2
    val frameDurationUs: Long = frameMillis * 1_000L
}

/** Encodes fixed-duration PCM frames at the negotiated rate into raw Opus access units. */
internal class OpusEncoder(
    private val configuration: OpusEncoderConfiguration,
    private val codecFactory: OpusCodecFactory = OpusCodecFactory {
        AndroidOpusCodec(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS))
    },
) : Closeable {
    private val codec: OpusCodec? = createCodec()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var failureLogged = false
    private var outputPackets = 0

    private fun createCodec(): OpusCodec? {
        var created: OpusCodec? = null
        var started = false
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                configuration.sampleRate,
                configuration.channels,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, configuration.bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(configuration.frameBytes, MAX_INPUT_BYTES))
            }
            codecFactory.create().also { codec ->
                created = codec
                codec.configure(format)
                codec.start()
                started = true
                Log.i(
                    TAG,
                    "Opus microphone encoder started rate=${configuration.sampleRate} " +
                        "channels=${configuration.channels} bitrate=${configuration.bitrate}",
                )
            }
        } catch (error: Exception) {
            created?.let { codec ->
                if (started) runCatching { codec.stop() }
                runCatching { codec.close() }
            }
            Log.w(TAG, "Opus microphone encoder unavailable error=${error.javaClass.simpleName}")
            null
        }
    }

    val available: Boolean get() = codec != null && !closed

    val hasFailed: Boolean get() = failed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    fun encode(pcm: ByteArray): List<ByteArray> {
        val codec = codec ?: return emptyList()
        if (closed || failed) return emptyList()
        if (pcm.size != configuration.frameBytes) {
            Log.w(TAG, "Opus microphone frame size mismatch expected=${configuration.frameBytes} actual=${pcm.size}")
            return emptyList()
        }
        return try {
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex)
                if (input == null || pcm.size > input.remaining()) {
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                } else {
                    input.clear()
                    input.put(pcm)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        pcm.size,
                        presentationTimeUs,
                        0,
                    )
                    presentationTimeUs += configuration.frameDurationUs
                }
            }
            drain()
        } catch (error: Exception) {
            fail(error)
            return emptyList()
        }
    }

    private fun fail(error: Exception) {
        failed = true
        if (!failureLogged) {
            failureLogged = true
            Log.w(TAG, "Opus microphone encoder failed error=${error.javaClass.simpleName}")
        }
        close()
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                fail(error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            codec.close()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
    }
}
