package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal enum class DspPcmEncoding(val bytesPerSample: Int) {
    PCM8(1),
    PCM16(2),
    PCM24_PACKED(3),
    PCM32(4),
    PCM_FLOAT(4),
}

internal object DspPcmConverter {
    fun pcmToFloat(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        format: DspAudioFormat,
        destination: ByteBuffer,
        diagnostics: DspDiagnostics? = null,
    ): Int {
        if (offset < 0 || length < 0 || offset > source.size - length) return -1
        val frameBytes = encoding.bytesPerSample * format.channels
        val completeLength = length - length % frameBytes
        val frames = completeLength / frameBytes
        val floatBytes = frames.toLong() * format.channels * Float.SIZE_BYTES
        if (floatBytes > Int.MAX_VALUE || destination.remaining().toLong() < floatBytes) return -1

        var sourceIndex = offset
        repeat(frames * format.channels) {
            val value = when (encoding) {
                DspPcmEncoding.PCM8 ->
                    ((source[sourceIndex].toInt() and 0xff) - 128) / 128f
                DspPcmEncoding.PCM16 -> {
                    val sample = (source[sourceIndex].toInt() and 0xff) or
                        ((source[sourceIndex + 1].toInt()) shl 8)
                    sample.toShort() / 32768f
                }
                DspPcmEncoding.PCM24_PACKED -> {
                    val packed = (source[sourceIndex].toInt() and 0xff) or
                        ((source[sourceIndex + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceIndex + 2].toInt() and 0xff) shl 16)
                    val signed = if (packed and 0x800000 != 0) packed or -0x1000000 else packed
                    signed / 8_388_608f
                }
                DspPcmEncoding.PCM32 -> {
                    val sample = (source[sourceIndex].toInt() and 0xff) or
                        ((source[sourceIndex + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceIndex + 2].toInt() and 0xff) shl 16) or
                        (source[sourceIndex + 3].toInt() shl 24)
                    (sample.toDouble() / 2_147_483_648.0).toFloat()
                }
                DspPcmEncoding.PCM_FLOAT -> {
                    val bits = (source[sourceIndex].toInt() and 0xff) or
                        ((source[sourceIndex + 1].toInt() and 0xff) shl 8) or
                        ((source[sourceIndex + 2].toInt() and 0xff) shl 16) or
                        (source[sourceIndex + 3].toInt() shl 24)
                    Float.fromBits(bits).let { sample ->
                        if (sample.isFinite()) sample else {
                            diagnostics?.let { it.nonFiniteInputSamples++ }
                            0f
                        }
                    }
                }
            }
            destination.putFloat(value)
            sourceIndex += encoding.bytesPerSample
        }
        return frames
    }

    fun floatToPcm16(
        source: ByteBuffer,
        frames: Int,
        format: DspAudioFormat,
        destination: ByteArray,
        offset: Int,
        dither: DspTpdfDither?,
        diagnostics: DspDiagnostics? = null,
        initialSilenceFrames: Int = 0,
    ): Boolean {
        if (frames <= 0 || offset < 0) return false
        if (initialSilenceFrames !in 0..frames) return false
        val sampleCount = frames.toLong() * format.channels
        val sourceBytes = sampleCount * Float.SIZE_BYTES
        val destinationBytes = sampleCount * Short.SIZE_BYTES
        if (sourceBytes > Int.MAX_VALUE || destinationBytes > Int.MAX_VALUE) return false
        if (source.remaining().toLong() != sourceBytes ||
            offset.toLong() > destination.size.toLong() - destinationBytes
        ) {
            return false
        }

        var sourceIndex = source.position()
        var destinationIndex = offset
        repeat(frames) { frame ->
            repeat(format.channels) { channel ->
                val raw = source.getFloat(sourceIndex)
                val finite = if (raw.isFinite()) raw else {
                    diagnostics?.let { it.nonFiniteOutputSamples++ }
                    0f
                }
                val clipped = if (frame < initialSilenceFrames) 0f else finite.coerceIn(-1f, MAX_PCM16_FLOAT)
                val scaled = clipped * 32768f
                val noise = if (frame < initialSilenceFrames) 0.0 else dither?.nextTpdf(channel) ?: 0.0
                val quantized = (scaled.toDouble() + noise).roundToInt().coerceIn(-32768, 32767)
                destination[destinationIndex] = quantized.toByte()
                destination[destinationIndex + 1] = (quantized shr 8).toByte()
                sourceIndex += Float.SIZE_BYTES
                destinationIndex += Short.SIZE_BYTES
            }
        }
        return true
    }

    private const val MAX_PCM16_FLOAT = 32767f / 32768f
}

internal class DspTpdfDither(private val seed: Long) {
    private val states = LongArray(2)
    private val increments = LongArray(2)

    init {
        reset()
    }

    fun reset() {
        states[0] = seed xor LEFT_SEED_SALT
        states[1] = seed xor RIGHT_SEED_SALT
        increments[0] = LEFT_SEQUENCE
        increments[1] = RIGHT_SEQUENCE
    }

    fun nextTpdf(channel: Int): Double = nextUniform(channel) - nextUniform(channel)

    private fun nextUniform(channel: Int): Double {
        val oldState = states[channel]
        states[channel] = oldState * PCG_MULTIPLIER + increments[channel]
        val shifted = (((oldState ushr 18) xor oldState) ushr 27).toInt()
        val rotation = (oldState ushr 59).toInt()
        val unsigned = Integer.rotateRight(shifted, rotation).toLong() and UINT32_MASK
        return unsigned.toDouble() / UINT32_RANGE
    }

    private companion object {
        const val PCG_MULTIPLIER = 6_364_136_223_846_793_005L
        const val LEFT_SEED_SALT = 0x4d595df4d0f33173L
        const val RIGHT_SEED_SALT = 0x14057b7ef767814fL
        const val LEFT_SEQUENCE = 0x5851f42d4c957f2dL
        const val RIGHT_SEQUENCE = 0x14057b7ef767814fL
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_RANGE = 4_294_967_296.0
    }
}
