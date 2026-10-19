package com.shilapi.xcertplay.media.dsp

import kotlin.math.log10
import kotlin.math.sqrt

enum class DspHarmonicMode {
    EVEN,
    ODD,
    MIXED,
}

enum class DspHarmonicQuality(val firTaps: Int, val latencyFrames: Int) {
    LOW(firTaps = 9, latencyFrames = 4),
    BALANCED(firTaps = 17, latencyFrames = 8),
    HIGH(firTaps = 33, latencyFrames = 16),
}

data class DspHighExciterConfig(
    val enabled: Boolean = false,
    val amount: Double = 0.0,
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
) {
    init {
        require(amount.isFinite() && amount in 0.0..MAX_AMOUNT)
        require(crossoverHz.isFinite() && crossoverHz in MIN_CROSSOVER_HZ..MAX_CROSSOVER_HZ)
    }

    companion object {
        const val DEFAULT_CROSSOVER_HZ = 8_000.0
        const val MIN_CROSSOVER_HZ = 3_000.0
        const val MAX_CROSSOVER_HZ = 16_000.0
        const val MAX_AMOUNT = 0.3
    }
}

data class DspVirtualBassConfig(
    val enabled: Boolean = false,
    val amount: Double = 0.0,
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
) {
    init {
        require(amount.isFinite() && amount in 0.0..DspHighExciterConfig.MAX_AMOUNT)
        require(crossoverHz.isFinite() && crossoverHz in MIN_CROSSOVER_HZ..MAX_CROSSOVER_HZ)
    }

    companion object {
        const val DEFAULT_CROSSOVER_HZ = 80.0
        const val MIN_CROSSOVER_HZ = 30.0
        const val MAX_CROSSOVER_HZ = 160.0
    }
}

data class DspHarmonicConfig(
    val highExciter: DspHighExciterConfig = DspHighExciterConfig(),
    val virtualBass: DspVirtualBassConfig = DspVirtualBassConfig(),
    val mode: DspHarmonicMode = DspHarmonicMode.MIXED,
    val quality: DspHarmonicQuality = DspHarmonicQuality.BALANCED,
    val levelMatchEnabled: Boolean = true,
) {
    internal fun effectiveFor(format: DspAudioFormat): DspHarmonicConfig = copy(
        highExciter = highExciter.copy(
            enabled = highExciter.enabled && highExciter.crossoverHz < format.sampleRate * 0.45,
        ),
    )

    internal val isActive: Boolean
        get() = (highExciter.enabled && highExciter.amount > 0.0) ||
            (virtualBass.enabled && virtualBass.amount > 0.0)

    internal val algorithmicLatencyFrames: Int
        get() = if (isActive) quality.latencyFrames else 0

    internal val maximumBoostDb: Double
        get() {
            if (!isActive) return 0.0
            val totalAmount =
                (if (highExciter.enabled) highExciter.amount else 0.0) +
                    (if (virtualBass.enabled) virtualBass.amount else 0.0)
            val levelMatchGain = if (levelMatchEnabled) {
                1.0 / sqrt(1.0 + 0.5 * totalAmount * totalAmount)
            } else {
                1.0
            }
            return maxOf(0.0, 20.0 * log10((1.0 + totalAmount) * levelMatchGain))
        }

    internal fun toNativeValues(): DoubleArray = doubleArrayOf(
        if (highExciter.enabled) 1.0 else 0.0,
        highExciter.amount,
        highExciter.crossoverHz,
        if (virtualBass.enabled) 1.0 else 0.0,
        virtualBass.amount,
        virtualBass.crossoverHz,
        mode.ordinal.toDouble(),
        quality.ordinal.toDouble(),
        if (levelMatchEnabled) 1.0 else 0.0,
    )

    companion object {
        const val NATIVE_VALUE_COUNT = 9
    }
}
