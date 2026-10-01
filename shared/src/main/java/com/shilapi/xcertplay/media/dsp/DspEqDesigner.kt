package com.shilapi.xcertplay.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DspBiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    fun writeTo(destination: DoubleArray, offset: Int) {
        destination[offset] = b0
        destination[offset + 1] = b1
        destination[offset + 2] = b2
        destination[offset + 3] = a1
        destination[offset + 4] = a2
    }

    fun magnitudeAt(sampleRate: Int, frequencyHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val cosine2 = cos(2.0 * omega)
        val sine2 = sin(2.0 * omega)
        val numerator = hypot(
            b0 + b1 * cosine + b2 * cosine2,
            -(b1 * sine + b2 * sine2),
        )
        val denominator = hypot(
            1.0 + a1 * cosine + a2 * cosine2,
            -(a1 * sine + a2 * sine2),
        )
        return numerator / denominator
    }
}

internal object DspEqDesigner {
    const val MAX_BANDS = 15
    const val COEFFICIENTS_PER_BAND = 5
    private const val MIN_FREQUENCY_HZ = 20.0
    private const val MAX_FREQUENCY_HZ = 20_000.0
    private const val MIN_Q = 0.1
    private const val MAX_Q = 20.0
    private const val MIN_GAIN_DB = -18.0
    private const val MAX_GAIN_DB = 18.0

    fun design(sampleRate: Int, band: DspEqBand): DspBiquadCoefficients {
        require(sampleRate in 8_000..192_000)
        val maxFrequency = minOf(MAX_FREQUENCY_HZ, sampleRate * 0.45)
        require(band.frequencyHz in MIN_FREQUENCY_HZ..maxFrequency)
        require(band.gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(band.q in MIN_Q..MAX_Q)

        val omega = 2.0 * PI * band.frequencyHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * band.q)
        val a = 10.0.pow(band.gainDb / 40.0)
        val raw = when (band.type) {
            DspEqType.PEAK -> doubleArrayOf(
                1.0 + alpha * a,
                -2.0 * cosine,
                1.0 - alpha * a,
                1.0 + alpha / a,
                -2.0 * cosine,
                1.0 - alpha / a,
            )
            DspEqType.LOW_SHELF -> shelf(cosine, sine, a, low = true)
            DspEqType.HIGH_SHELF -> shelf(cosine, sine, a, low = false)
            DspEqType.HIGH_PASS -> doubleArrayOf(
                (1.0 + cosine) / 2.0,
                -(1.0 + cosine),
                (1.0 + cosine) / 2.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.LOW_PASS -> doubleArrayOf(
                (1.0 - cosine) / 2.0,
                1.0 - cosine,
                (1.0 - cosine) / 2.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.NOTCH -> doubleArrayOf(
                1.0,
                -2.0 * cosine,
                1.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.ALL_PASS -> doubleArrayOf(
                1.0 - alpha,
                -2.0 * cosine,
                1.0 + alpha,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
        }
        val a0 = raw[3]
        require(a0.isFinite() && a0 != 0.0)
        val normalized = DspBiquadCoefficients(
            b0 = raw[0] / a0,
            b1 = raw[1] / a0,
            b2 = raw[2] / a0,
            a1 = raw[4] / a0,
            a2 = raw[5] / a0,
        )
        require(
            normalized.b0.isFinite() && normalized.b1.isFinite() && normalized.b2.isFinite() &&
                normalized.a1.isFinite() && normalized.a2.isFinite()
        )
        return normalized
    }

    private fun shelf(cosine: Double, sine: Double, a: Double, low: Boolean): DoubleArray {
        val alpha = sine / 2.0 * sqrt(2.0)
        val beta = 2.0 * sqrt(a) * alpha
        return if (low) {
            doubleArrayOf(
                a * ((a + 1.0) - (a - 1.0) * cosine + beta),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cosine),
                a * ((a + 1.0) - (a - 1.0) * cosine - beta),
                (a + 1.0) + (a - 1.0) * cosine + beta,
                -2.0 * ((a - 1.0) + (a + 1.0) * cosine),
                (a + 1.0) + (a - 1.0) * cosine - beta,
            )
        } else {
            doubleArrayOf(
                a * ((a + 1.0) + (a - 1.0) * cosine + beta),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine),
                a * ((a + 1.0) + (a - 1.0) * cosine - beta),
                (a + 1.0) - (a - 1.0) * cosine + beta,
                2.0 * ((a - 1.0) - (a + 1.0) * cosine),
                (a + 1.0) - (a - 1.0) * cosine - beta,
            )
        }
    }
}

internal data class DspHeadroomResult(
    val filterPeakDb: Double,
    val reductionDb: Double,
)

internal object DspAutoHeadroom {
    private const val SCAN_POINTS = 1024

    fun calculate(
        sampleRate: Int,
        preampDb: Double,
        peqBands: List<DspEqBand>,
        compressorMakeupDb: Double = 0.0,
        stereoWidth: Double = 1.0,
        marginDb: Double = 1.0,
        staticBassBands: List<DspEqBand> = emptyList(),
    ): DspHeadroomResult {
        require(sampleRate in 8_000..192_000)
        require(preampDb.isFinite())
        require(compressorMakeupDb.isFinite())
        require(stereoWidth.isFinite() && stereoWidth in 0.0..2.0)
        require(marginDb.isFinite() && marginDb >= 0.0)
        require(peqBands.size <= DspEqDesigner.MAX_BANDS)
        require(staticBassBands.size <= DspEqDesigner.MAX_BANDS)

        val bands = (peqBands + staticBassBands).filter(DspEqBand::enabled)
        val filters = bands.map { DspEqDesigner.design(sampleRate, it) }
        val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
        var peakDb = Double.NEGATIVE_INFINITY
        val logMin = kotlin.math.ln(20.0)
        val logMax = kotlin.math.ln(maxFrequency)
        for (point in 0 until SCAN_POINTS) {
            val fraction = point.toDouble() / (SCAN_POINTS - 1)
            val frequency = kotlin.math.exp(logMin + (logMax - logMin) * fraction)
            var magnitude = 1.0
            for (filter in filters) magnitude *= filter.magnitudeAt(sampleRate, frequency)
            require(magnitude.isFinite() && magnitude >= 0.0)
            if (magnitude > 0.0) peakDb = max(peakDb, 20.0 * log10(magnitude))
        }
        if (filters.isEmpty()) peakDb = 0.0
        val widthWorstCaseDb = 20.0 * log10(max(1.0, stereoWidth))
        val staticWorstCaseDb = preampDb + peakDb + max(0.0, compressorMakeupDb) + widthWorstCaseDb
        return DspHeadroomResult(
            filterPeakDb = peakDb,
            reductionDb = max(0.0, staticWorstCaseDb + marginDb),
        )
    }
}

internal data class DspPreparedConfig(
    val appliedPreampDb: Double,
    val eqCoefficients: DoubleArray,
    val dynamics: DoubleArray,
    val headroom: DspHeadroomResult,
)

internal fun DspRuntimeConfig.prepare(format: DspAudioFormat): DspPreparedConfig {
    val enabledBands = peqBands.filter(DspEqBand::enabled)
    val coefficients = DoubleArray(enabledBands.size * DspEqDesigner.COEFFICIENTS_PER_BAND)
    enabledBands.forEachIndexed { index, band ->
        DspEqDesigner.design(format.sampleRate, band)
            .writeTo(coefficients, index * DspEqDesigner.COEFFICIENTS_PER_BAND)
    }
    val headroom = if (autoHeadroomEnabled) {
        DspAutoHeadroom.calculate(
            sampleRate = format.sampleRate,
            preampDb = gainDb,
            peqBands = peqBands,
            compressorMakeupDb = if (compressor.enabled) compressor.makeupDb else 0.0,
            stereoWidth = stereoWidth,
            marginDb = autoHeadroomMarginDb,
        )
    } else {
        DspHeadroomResult(filterPeakDb = 0.0, reductionDb = 0.0)
    }
    return DspPreparedConfig(
        appliedPreampDb = (gainDb - headroom.reductionDb).coerceAtLeast(MIN_APPLIED_PREAMP_DB),
        eqCoefficients = coefficients,
        dynamics = doubleArrayOf(
            if (compressor.enabled) 1.0 else 0.0,
            compressor.thresholdDb,
            compressor.ratio,
            compressor.attackMs,
            compressor.releaseMs,
            compressor.kneeDb,
            compressor.makeupDb,
            if (safetyLimiter.enabled) 1.0 else 0.0,
            safetyLimiter.thresholdDb,
            safetyLimiter.releaseMs,
        ),
        headroom = headroom,
    )
}

private const val MIN_APPLIED_PREAMP_DB = -700.0
