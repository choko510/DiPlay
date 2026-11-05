package com.shilapi.xcertplay.media.dsp

data class DspAdvancedMsConfig(
    val enabled: Boolean = false,
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
    val centerFocusDb: Double = 0.0,
    val midLowGainDb: Double = 0.0,
    val midHighGainDb: Double = 0.0,
    val sideLowGainDb: Double = 0.0,
    val sideHighGainDb: Double = 0.0,
    val lowBandWidth: Double = 1.0,
    val highBandWidth: Double = 1.0,
    val sideCompressor: DspMsSideCompressorConfig = DspMsSideCompressorConfig(),
) {
    init {
        require(crossoverHz.isFinite() && crossoverHz in MIN_CROSSOVER_HZ..MAX_CROSSOVER_HZ)
        require(centerFocusDb.isFinite() && centerFocusDb in MIN_CENTER_FOCUS_DB..MAX_CENTER_FOCUS_DB)
        require(midLowGainDb.isFinite() && midLowGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(midHighGainDb.isFinite() && midHighGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(sideLowGainDb.isFinite() && sideLowGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(sideHighGainDb.isFinite() && sideHighGainDb in MIN_TONE_DB..MAX_TONE_DB)
        require(lowBandWidth.isFinite() && lowBandWidth in MIN_WIDTH..MAX_WIDTH)
        require(highBandWidth.isFinite() && highBandWidth in MIN_WIDTH..MAX_WIDTH)
    }

    fun validateForSampleRate(sampleRate: Int) {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        require(crossoverHz < sampleRate * MAX_NORMALIZED_FREQUENCY)
    }

    val worstCaseBoostDb: Double
        get() {
            if (!enabled) return 0.0
            val midBoost = maxOf(centerFocusDb + midLowGainDb, centerFocusDb + midHighGainDb)
            val sideLowBoost = sideLowGainDb + widthGainDb(lowBandWidth)
            val sideHighBoost = sideHighGainDb + widthGainDb(highBandWidth)
            return maxOf(0.0, midBoost, sideLowBoost, sideHighBoost)
        }

    val requiresStateContinuity: Boolean
        get() = enabled

    companion object {
        const val DEFAULT_CROSSOVER_HZ = 250.0
        const val MIN_CROSSOVER_HZ = 60.0
        const val MAX_CROSSOVER_HZ = 1_000.0
        const val MIN_WIDTH = 0.0
        const val MAX_WIDTH = 2.0
        const val MIN_TONE_DB = -6.0
        const val MAX_TONE_DB = 6.0
        const val MIN_CENTER_FOCUS_DB = -6.0
        const val MAX_CENTER_FOCUS_DB = 6.0
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000
        private const val MAX_NORMALIZED_FREQUENCY = 0.45

        private fun widthGainDb(width: Double): Double = if (width <= 0.0) {
            Double.NEGATIVE_INFINITY
        } else {
            20.0 * kotlin.math.log10(width)
        }

        fun fromLegacy(width: Double, monoBass: DspMonoBassConfig): DspAdvancedMsConfig = DspAdvancedMsConfig(
            enabled = true,
            crossoverHz = monoBass.cutoffHz.toDouble(),
            lowBandWidth = if (monoBass.enabled) 0.0 else width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            highBandWidth = width.coerceIn(MIN_WIDTH, MAX_WIDTH),
        )
    }
}

data class DspMsSideCompressorConfig(
    val enabled: Boolean = false,
    val thresholdDb: Double = -24.0,
    val ratio: Double = 2.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 100.0,
    val kneeDb: Double = 6.0,
) {
    init {
        require(thresholdDb.isFinite() && thresholdDb in MIN_THRESHOLD_DB..MAX_THRESHOLD_DB)
        require(ratio.isFinite() && ratio in MIN_RATIO..MAX_RATIO)
        require(attackMs.isFinite() && attackMs in MIN_TIME_MS..MAX_TIME_MS)
        require(releaseMs.isFinite() && releaseMs in MIN_TIME_MS..MAX_TIME_MS)
        require(kneeDb.isFinite() && kneeDb in MIN_KNEE_DB..MAX_KNEE_DB)
    }

    companion object {
        const val MIN_THRESHOLD_DB = -60.0
        const val MAX_THRESHOLD_DB = 0.0
        const val MIN_RATIO = 1.0
        const val MAX_RATIO = 8.0
        const val MIN_TIME_MS = 0.1
        const val MAX_TIME_MS = 2_000.0
        const val MIN_KNEE_DB = 0.0
        const val MAX_KNEE_DB = 24.0
    }
}
