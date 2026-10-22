package com.shilapi.xcertplay.media.dsp

internal class DspLatencyPreservingBypass(
    private val format: DspAudioFormat,
    private val commonLatencyFrames: Int,
    seatAlignment: DspSeatAlignmentConfig = DspSeatAlignmentConfig(),
    seed: Long = System.nanoTime(),
) {
    private val ring = FloatArray(commonLatencyFrames * format.channels)
    private val inputFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
    private val delayedFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
    private val seatNearChannel = when {
        format.channels != 2 || !seatAlignment.enabled -> -1
        seatAlignment.nearSpeaker == DspSeatNearSpeaker.LEFT -> 0
        seatAlignment.nearSpeaker == DspSeatNearSpeaker.RIGHT -> 1
        else -> -1
    }
    private val seatDelayFrames = if (seatNearChannel >= 0) {
        seatAlignment.relativeDelayFrames(format.sampleRate)
    } else {
        0.0
    }
    private val seatIntegerDelayFrames = seatDelayFrames.toInt()
    private val seatFraction = (seatDelayFrames - seatIntegerDelayFrames).toFloat()
    private val seatAllpassCoefficient = (1.0f - seatFraction) / (1.0f + seatFraction)
    private val seatRing = FloatArray(maxOf(1, seatIntegerDelayFrames + 1))
    private val dither = DspTpdfDither(seed)
    private val pcm16 = ByteArray(
        DspBufferSizing.pcm16ByteCount(DspBufferSizing.MAX_DECODER_PCM_FRAMES, format.channels),
    )
    private var ringPosition = 0
    private var seatRingPosition = 0
    private var seatFractionalXPrevious = 0.0f
    private var seatFractionalYPrevious = 0.0f
    private var processedLength = 0
    private var processedFrames = 0

    init {
        require(commonLatencyFrames >= 0)
        require(commonLatencyFrames > 0 || seatDelayFrames > 0.0)
    }

    val output: ByteArray
        get() = pcm16

    val outputLength: Int
        get() = processedLength

    val outputFloats: FloatArray
        get() = delayedFloats

    val outputFrameCount: Int
        get() = processedFrames

    fun process(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
    ): Int {
        processedLength = 0
        processedFrames = 0
        val frames = DspPcmConverter.pcmToFloatArray(source, offset, length, encoding, format, inputFloats)
        if (frames <= 0 || frames > DspBufferSizing.MAX_DECODER_PCM_FRAMES) return -1
        for (frame in 0 until frames) {
            for (channel in 0 until format.channels) {
                val sampleIndex = frame * format.channels + channel
                var sample = inputFloats[sampleIndex]
                if (channel == seatNearChannel) {
                    seatRing[seatRingPosition] = sample
                    var readPosition = seatRingPosition - seatIntegerDelayFrames
                    if (readPosition < 0) readPosition += seatRing.size
                    val integerDelayed = seatRing[readPosition]
                    sample = if (seatFraction > 0.0f) {
                        val delayed = seatAllpassCoefficient * integerDelayed + seatFractionalXPrevious -
                            seatAllpassCoefficient * seatFractionalYPrevious
                        seatFractionalXPrevious = integerDelayed
                        seatFractionalYPrevious = delayed
                        delayed
                    } else {
                        integerDelayed
                    }
                }
                if (commonLatencyFrames > 0) {
                    val ringIndex = ringPosition * format.channels + channel
                    delayedFloats[sampleIndex] = ring[ringIndex]
                    ring[ringIndex] = sample
                } else {
                    delayedFloats[sampleIndex] = sample
                }
            }
            if (commonLatencyFrames > 0) {
                ringPosition++
                if (ringPosition == commonLatencyFrames) ringPosition = 0
            }
            if (seatDelayFrames > 0.0) {
                seatRingPosition++
                if (seatRingPosition == seatRing.size) seatRingPosition = 0
            }
        }
        if (!DspPcmConverter.floatArrayToPcm16(
                source = delayedFloats,
                frames = frames,
                format = format,
                destination = pcm16,
                offset = 0,
                dither = dither,
            )
        ) {
            return -1
        }
        processedFrames = frames
        processedLength = DspBufferSizing.pcm16ByteCount(frames, format.channels)
        return processedLength
    }

    fun reset() {
        ring.fill(0f)
        seatRing.fill(0f)
        inputFloats.fill(0f)
        delayedFloats.fill(0f)
        dither.reset()
        ringPosition = 0
        seatRingPosition = 0
        seatFractionalXPrevious = 0.0f
        seatFractionalYPrevious = 0.0f
        processedLength = 0
        processedFrames = 0
    }
}
