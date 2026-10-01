package com.shilapi.xcertplay.media.dsp

import kotlin.math.roundToInt

internal class DspPcmCrossfade(
    private val channels: Int,
    private val durationFrames: Int,
) {
    private val frameBytes = channels * Short.SIZE_BYTES
    private var totalFrames = 0
    private var remainingFrames = 0

    init {
        require(channels in 1..2)
        require(durationFrames > 0)
    }

    val isActive: Boolean
        get() = remainingFrames > 0

    fun begin() {
        totalFrames = durationFrames
        remainingFrames = durationFrames
    }

    fun cancel() {
        totalFrames = 0
        remainingFrames = 0
    }

    fun blend(
        from: ByteArray,
        fromOffset: Int,
        to: ByteArray,
        toOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Boolean {
        if (!isActive || byteCount < 0 || byteCount % frameBytes != 0 ||
            !contains(from, fromOffset, byteCount) || !contains(to, toOffset, byteCount) ||
            !contains(destination, destinationOffset, byteCount)
        ) {
            return false
        }
        val frameCount = byteCount / frameBytes
        val crossfadeFrames = minOf(frameCount, remainingFrames)
        for (frame in 0 until frameCount) {
            val mix = if (frame < crossfadeFrames) {
                val elapsedFrame = totalFrames - remainingFrames + frame
                if (totalFrames == 1) 1.0 else elapsedFrame.toDouble() / (totalFrames - 1)
            } else {
                1.0
            }
            val frameOffset = frame * frameBytes
            for (channel in 0 until channels) {
                val sampleOffset = frameOffset + channel * Short.SIZE_BYTES
                val fromSample = readSample(from, fromOffset + sampleOffset)
                val toSample = readSample(to, toOffset + sampleOffset)
                val mixed = (fromSample + (toSample - fromSample) * mix)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                writeSample(destination, destinationOffset + sampleOffset, mixed)
            }
        }
        remainingFrames -= crossfadeFrames
        return remainingFrames == 0
    }

    private fun contains(bytes: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= bytes.size - length

    private fun readSample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()

    private fun writeSample(bytes: ByteArray, offset: Int, sample: Int) {
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }
}
