package com.shilapi.xcertplay.media.dsp

import kotlin.math.abs
import kotlin.math.log10

class DspImpulseResponse(
    val id: String,
    val sampleRate: Int,
    val channels: Int,
    samples: FloatArray,
) {
    private val sampleData: FloatArray
    private val maximumChannelAbsoluteSum: Double
    val frameCount: Int

    fun sampleAt(frame: Int, channel: Int): Float {
        require(frame in 0 until frameCount && channel in 0 until channels)
        return sampleData[frame * channels + channel]
    }

    init {
        require(IR_ID.matches(id))
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        require(channels in 1..2)
        require(samples.isNotEmpty() && samples.size % channels == 0)
        val frames = samples.size / channels
        require(frames <= MAX_FRAMES)
        var absoluteSum = 0.0
        val channelAbsoluteSums = DoubleArray(channels)
        samples.forEachIndexed { index, sample ->
            require(sample.isFinite() && abs(sample) <= MAX_ABSOLUTE_SAMPLE)
            val magnitude = abs(sample).toDouble()
            absoluteSum += magnitude
            channelAbsoluteSums[index % channels] += magnitude
            require(absoluteSum <= MAX_ABSOLUTE_SUM)
        }
        frameCount = frames
        maximumChannelAbsoluteSum = channelAbsoluteSums.maxOrNull() ?: 0.0
        sampleData = samples.copyOf()
    }

    internal fun copySamples(): FloatArray = sampleData.copyOf()

    internal fun conservativePeakGainDb(wet: Double): Double {
        require(wet.isFinite() && wet in 0.0..1.0)
        val peakGain = (1.0 - wet) + wet * maximumChannelAbsoluteSum
        return if (peakGain > 1.0) 20.0 * log10(peakGain) else 0.0
    }

    companion object {
        const val MAX_FRAMES = 65_536
        val SUPPORTED_SAMPLE_RATES = setOf(44_100, 48_000, 96_000)
        private const val MAX_ABSOLUTE_SAMPLE = 32.0f
        private const val MAX_ABSOLUTE_SUM = 1_000_000.0
        private val IR_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
