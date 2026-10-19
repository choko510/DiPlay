package com.shilapi.xcertplay.media.dsp

import kotlin.math.log10
import kotlin.math.max

data class DspAdvancedMsBandConfig(
    val midGainDb: Double = 0.0,
    val sideGainDb: Double = 0.0,
    val width: Double = 1.0,
) {
    init {
        require(midGainDb.isFinite() && midGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(sideGainDb.isFinite() && sideGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(width.isFinite() && width in 0.0..MAX_WIDTH)
    }

    companion object {
        private const val MIN_TONE_DB = -6.0
        private const val MAX_TONE_DB = 6.0
        private const val MAX_WIDTH = 2.0
    }
}

data class DspAdvancedMsConfig(
    val enabled: Boolean = false,
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
    val low: DspAdvancedMsBandConfig = DspAdvancedMsBandConfig(),
    val high: DspAdvancedMsBandConfig = DspAdvancedMsBandConfig(),
    val centerFocusDb: Double = 0.0,
    val sideCompressor: DspCompressorConfig = DspCompressorConfig(),
) {
    init {
        require(crossoverHz.isFinite() && crossoverHz in MIN_CROSSOVER_HZ..MAX_CROSSOVER_HZ)
        require(centerFocusDb.isFinite() && centerFocusDb in MIN_CENTER_FOCUS_DB..MAX_CENTER_FOCUS_DB)
    }

    internal val maximumBoostDb: Double
        get() {
            if (!enabled) return 0.0
            val midBoostDb = max(0.0, centerFocusDb + max(low.midGainDb, high.midGainDb))
            val sideDynamicsMakeupDb = if (sideCompressor.enabled) max(0.0, sideCompressor.makeupDb) else 0.0
            val lowSideBoostDb = max(
                0.0,
                low.sideGainDb + widthGainDb(low.width) + sideDynamicsMakeupDb,
            )
            val highSideBoostDb = max(
                0.0,
                high.sideGainDb + widthGainDb(high.width) + sideDynamicsMakeupDb,
            )
            val lowMidDb = centerFocusDb + low.midGainDb
            val highMidDb = centerFocusDb + high.midGainDb
            val sideMakeupDb = if (sideCompressor.enabled) sideCompressor.makeupDb else 0.0
            val lowSideDb = low.sideGainDb + widthGainDb(low.width) + sideMakeupDb
            val highSideDb = high.sideGainDb + widthGainDb(high.width) + sideMakeupDb
            val interactionMarginDb = if (
                (sideCompressor.enabled && (low.width > 0.0 || high.width > 0.0)) ||
                (low.width > 0.0 && lowMidDb != lowSideDb) ||
                (high.width > 0.0 && highMidDb != highSideDb)
            ) {
                M_S_INTERACTION_MARGIN_DB
            } else {
                0.0
            }
            return max(midBoostDb, max(lowSideBoostDb, highSideBoostDb)) + interactionMarginDb
        }

    internal fun toNativeValues(): DoubleArray = doubleArrayOf(
        if (enabled) 1.0 else 0.0,
        crossoverHz,
        low.midGainDb,
        low.sideGainDb,
        low.width,
        high.midGainDb,
        high.sideGainDb,
        high.width,
        centerFocusDb,
        if (sideCompressor.enabled) 1.0 else 0.0,
        sideCompressor.thresholdDb,
        sideCompressor.ratio,
        sideCompressor.attackMs,
        sideCompressor.releaseMs,
        sideCompressor.kneeDb,
        sideCompressor.makeupDb,
    )

    private fun widthGainDb(width: Double): Double =
        if (width > 0.0) 20.0 * log10(width) else Double.NEGATIVE_INFINITY

    companion object {
        const val NATIVE_VALUE_COUNT = 16
        const val DEFAULT_CROSSOVER_HZ = 120.0
        const val MIN_CROSSOVER_HZ = 20.0
        const val MAX_CROSSOVER_HZ = 1_000.0
        const val MIN_CENTER_FOCUS_DB = -6.0
        const val MAX_CENTER_FOCUS_DB = 6.0
        private const val M_S_INTERACTION_MARGIN_DB = 3.0
    }
}
