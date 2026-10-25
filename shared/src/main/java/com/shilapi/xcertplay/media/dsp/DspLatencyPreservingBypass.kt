package com.shilapi.xcertplay.media.dsp

import kotlin.math.abs

internal class DspLatencyPreservingBypass(
    private val format: DspAudioFormat,
    private val commonLatencyFrames: Int,
    private val seatAlignment: DspSeatAlignmentConfig = DspSeatAlignmentConfig(),
    failurePreampDb: Double = 0.0,
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
    private val failurePreampGain = Math.pow(10.0, failurePreampDb / 20.0).toFloat()
    private val seatRing = FloatArray(maxOf(1, seatIntegerDelayFrames + 1))
    private val leftTrimGain = Math.pow(
        10.0,
        (if (seatAlignment.enabled) seatAlignment.leftTrimDb else 0.0) / 20.0,
    ).toFloat()
    private val rightTrimGain = Math.pow(
        10.0,
        (if (seatAlignment.enabled) seatAlignment.rightTrimDb else 0.0) / 20.0,
    ).toFloat()
    private val dither = DspTpdfDither(seed)
    private val pcm16 = ByteArray(
        DspBufferSizing.pcm16ByteCount(DspBufferSizing.MAX_DECODER_PCM_FRAMES, format.channels),
    )
    private var ringPosition = 0
    private var seatRingPosition = 0
    private var seatFractionalXPrevious = 0.0f
    private var seatFractionalYPrevious = 0.0f
    private var seatControlsEnabled = false
    private var processedLength = 0
    private var processedFrames = 0
    internal val hasSeatControls: Boolean
        get() = seatAlignment.enabled && (
            seatAlignment.leftTrimDb != 0.0 || seatAlignment.rightTrimDb != 0.0 ||
                seatAlignment.invertLeft || seatAlignment.invertRight
            )
    internal val seatControlsActiveForTest: Boolean
        get() = seatControlsEnabled

    init {
        require(commonLatencyFrames >= 0)
        require(commonLatencyFrames > 0 || seatDelayFrames > 0.0 || hasSeatControls)
    }

    val output: ByteArray
        get() = pcm16

    val outputLength: Int
        get() = processedLength

    val outputFloats: FloatArray
        get() = delayedFloats

    val outputFrameCount: Int
        get() = processedFrames

    fun activateSeatControlsForCurrentOutput() {
        if (seatControlsEnabled) return
        seatControlsEnabled = true
        for (frame in 0 until processedFrames) {
            for (channel in 0 until format.channels) {
                val index = frame * format.channels + channel
                delayedFloats[index] = applySeatControl(delayedFloats[index], channel)
            }
        }
    }

    fun process(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        encodeOutput: Boolean = true,
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
                    var outputSample = ring[ringIndex]
                    ring[ringIndex] = sample
                    if (seatControlsEnabled) outputSample = applySeatControl(outputSample, channel)
                    delayedFloats[sampleIndex] = outputSample
                } else {
                    delayedFloats[sampleIndex] = if (seatControlsEnabled) {
                        applySeatControl(sample, channel)
                    } else {
                        sample
                    }
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
        processedFrames = frames
        if (!encodeOutput) return 0
        return renderPcm16()
    }

    fun renderPcm16(): Int {
        if (processedFrames <= 0) return -1
        if (!DspPcmConverter.floatArrayToPcm16(
                source = delayedFloats,
                frames = processedFrames,
                format = format,
                destination = pcm16,
                offset = 0,
                dither = dither,
            )
        ) {
            return -1
        }
        processedLength = DspBufferSizing.pcm16ByteCount(processedFrames, format.channels)
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
        seatControlsEnabled = false
        processedLength = 0
        processedFrames = 0
    }

    private fun applySeatControl(sample: Float, channel: Int): Float {
        val trimGain = if (!hasSeatControls) 1.0f else if (channel == 0) leftTrimGain else rightTrimGain
        val gain = failurePreampGain * trimGain
        val invert = hasSeatControls && if (channel == 0) seatAlignment.invertLeft else seatAlignment.invertRight
        val adjusted = if (invert) -sample * gain else sample * gain
        if (!adjusted.isFinite()) return 0.0f
        val magnitude = abs(adjusted)
        if (magnitude <= FAILURE_LIMITER_KNEE) return adjusted
        val amount = ((magnitude - FAILURE_LIMITER_KNEE) / FAILURE_LIMITER_RANGE).coerceIn(0.0f, 1.0f)
        val limited = FAILURE_LIMITER_KNEE + 0.3f * amount - 0.36f * amount * amount +
            0.14f * amount * amount * amount
        return if (adjusted < 0.0f) -limited else limited
    }

    private companion object {
        const val FAILURE_LIMITER_KNEE = 0.9f
        const val FAILURE_LIMITER_RANGE = 0.3f
    }
}
