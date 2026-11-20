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
    private val maximumChannelSpectralGain: Double
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
        maximumChannelSpectralGain = computeMaxChannelSpectralGain(samples, channels, frames)
        sampleData = samples.copyOf()
    }

    internal fun copySamples(): FloatArray = sampleData.copyOf()

    /** Strict L1 peak upper bound guaranteeing arbitrary PCM inputs stay within full scale. */
    internal fun conservativePeakGainDb(wet: Double): Double {
        require(wet.isFinite() && wet in 0.0..1.0)
        val peakGain = (1.0 - wet) + wet * maximumChannelAbsoluteSum
        return if (peakGain > 1.0) 20.0 * log10(peakGain) else 0.0
    }

    /** Steady-state sine frequency-response peak estimate (diagnostic/reference only). */
    internal fun spectralPeakGainDb(wet: Double): Double {
        require(wet.isFinite() && wet in 0.0..1.0)
        val peakGain = (1.0 - wet) + wet * maximumChannelSpectralGain
        return if (peakGain > 1.0) 20.0 * log10(peakGain) else 0.0
    }

    companion object {
        const val MAX_FRAMES = 65_536
        val SUPPORTED_SAMPLE_RATES = setOf(44_100, 48_000, 96_000)
        private const val MAX_ABSOLUTE_SAMPLE = 32.0f
        private const val MAX_ABSOLUTE_SUM = 1_000_000.0
        private val IR_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        private fun computeMaxChannelSpectralGain(samples: FloatArray, channels: Int, frames: Int): Double {
            if (frames <= 1) {
                var peak = 0.0
                for (sample in samples) peak = maxOf(peak, abs(sample).toDouble())
                return peak
            }
            // Zero-pad to at least 2x frame count (capped at 131072) to resolve inter-bin magnitude peaks.
            var fftSize = 64
            val targetSize = minOf(MAX_FRAMES * 2, frames * 2)
            while (fftSize < targetSize) fftSize = fftSize shl 1
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            var maxPeak = 0.0
            for (ch in 0 until channels) {
                real.fill(0.0)
                imag.fill(0.0)
                for (frame in 0 until frames) {
                    real[frame] = samples[frame * channels + ch].toDouble()
                }
                fftInPlace(real, imag, fftSize)
                for (bin in 0..fftSize / 2) {
                    val mag = kotlin.math.hypot(real[bin], imag[bin])
                    if (mag > maxPeak) maxPeak = mag
                }
            }
            return maxPeak
        }

        private fun fftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]; real[i] = real[j]; real[j] = tr
                    val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2.0 * Math.PI / len
                val wLenRe = kotlin.math.cos(angle)
                val wLenIm = kotlin.math.sin(angle)
                var i = 0
                while (i < n) {
                    var wRe = 1.0
                    var wIm = 0.0
                    val half = len shr 1
                    for (k in 0 until half) {
                        val uRe = real[i + k]
                        val uIm = imag[i + k]
                        val vIdx = i + k + half
                        val vRe = real[vIdx] * wRe - imag[vIdx] * wIm
                        val vIm = real[vIdx] * wIm + imag[vIdx] * wRe
                        real[i + k] = uRe + vRe
                        imag[i + k] = uIm + vIm
                        real[vIdx] = uRe - vRe
                        imag[vIdx] = uIm - vIm
                        val nextWRe = wRe * wLenRe - wIm * wLenIm
                        wIm = wRe * wLenIm + wIm * wLenRe
                        wRe = nextWRe
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
