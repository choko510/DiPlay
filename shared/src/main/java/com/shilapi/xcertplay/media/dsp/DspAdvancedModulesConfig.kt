package com.shilapi.xcertplay.media.dsp

enum class DspSeatPreset {
    CUSTOM,
    DRIVER,
    PASSENGER,
    CENTER,
}

enum class DspAdvancedMsMonitorMode {
    NORMAL,
    MID_ONLY,
    SIDE_ONLY,
    MONO_FOLD_DOWN,
}

data class DspSeatDistanceDelays(
    val leftDelayMs: Double,
    val rightDelayMs: Double,
) {
    init {
        require(leftDelayMs.isFinite() && leftDelayMs >= 0.0)
        require(rightDelayMs.isFinite() && rightDelayMs >= 0.0)
    }

    val withinSupportedRange: Boolean
        get() = leftDelayMs <= DspSeatFocusConfig.MAX_DELAY_MS &&
            rightDelayMs <= DspSeatFocusConfig.MAX_DELAY_MS
}

data class DspSeatFocusConfig(
    val enabled: Boolean = false,
    val bypassed: Boolean = false,
    val preset: DspSeatPreset = DspSeatPreset.CUSTOM,
    val leftDelayMs: Double = 0.0,
    val rightDelayMs: Double = 0.0,
    val leftTrimDb: Double = 0.0,
    val rightTrimDb: Double = 0.0,
    val invertLeft: Boolean = false,
    val invertRight: Boolean = false,
    val distanceMode: Boolean = false,
    val leftDistanceCm: Double? = null,
    val rightDistanceCm: Double? = null,
) {
    init {
        require(leftDelayMs.isFinite() && leftDelayMs in 0.0..MAX_DELAY_MS)
        require(rightDelayMs.isFinite() && rightDelayMs in 0.0..MAX_DELAY_MS)
        require(leftTrimDb.isFinite() && leftTrimDb in MIN_TRIM_DB..MAX_TRIM_DB)
        require(rightTrimDb.isFinite() && rightTrimDb in MIN_TRIM_DB..MAX_TRIM_DB)
        require(leftDistanceCm == null || leftDistanceCm.isFinite() && leftDistanceCm in MIN_DISTANCE_CM..MAX_DISTANCE_CM)
        require(rightDistanceCm == null || rightDistanceCm.isFinite() && rightDistanceCm in MIN_DISTANCE_CM..MAX_DISTANCE_CM)
        if (distanceMode) {
            val left = requireNotNull(leftDistanceCm)
            val right = requireNotNull(rightDistanceCm)
            require(calculateDistanceDelays(left, right).withinSupportedRange)
        }
    }

    fun effectiveDelays(): DspSeatDistanceDelays = if (distanceMode) {
        calculateDistanceDelays(requireNotNull(leftDistanceCm), requireNotNull(rightDistanceCm))
    } else {
        DspSeatDistanceDelays(leftDelayMs = leftDelayMs, rightDelayMs = rightDelayMs)
    }

    internal fun toNativeValues(channels: Int): DoubleArray {
        val delays = effectiveDelays()
        val active = enabled && channels == 2
        return doubleArrayOf(
            if (active) 1.0 else 0.0,
            if (active && bypassed) 1.0 else 0.0,
            delays.leftDelayMs,
            delays.rightDelayMs,
            leftTrimDb,
            rightTrimDb,
            if (invertLeft) 1.0 else 0.0,
            if (invertRight) 1.0 else 0.0,
        )
    }

    companion object {
        const val MAX_DELAY_MS = 5.0
        const val MIN_DISTANCE_CM = 20.0
        const val MAX_DISTANCE_CM = 300.0
        private const val MIN_TRIM_DB = -6.0
        private const val MAX_TRIM_DB = 6.0
        private const val SPEED_OF_SOUND_METERS_PER_SECOND = 343.0

        fun calculateDistanceDelays(leftDistanceCm: Double, rightDistanceCm: Double): DspSeatDistanceDelays {
            require(leftDistanceCm.isFinite() && leftDistanceCm in MIN_DISTANCE_CM..MAX_DISTANCE_CM)
            require(rightDistanceCm.isFinite() && rightDistanceCm in MIN_DISTANCE_CM..MAX_DISTANCE_CM)
            val farthestDistanceCm = maxOf(leftDistanceCm, rightDistanceCm)
            return DspSeatDistanceDelays(
                leftDelayMs = (farthestDistanceCm - leftDistanceCm) * 10.0 / SPEED_OF_SOUND_METERS_PER_SECOND,
                rightDelayMs = (farthestDistanceCm - rightDistanceCm) * 10.0 / SPEED_OF_SOUND_METERS_PER_SECOND,
            )
        }
    }
}

data class DspAdvancedMsConfig(
    val enabled: Boolean = false,
    val monitorMode: DspAdvancedMsMonitorMode = DspAdvancedMsMonitorMode.NORMAL,
    val midGainDb: Double = 0.0,
    val sideGainDb: Double = 0.0,
    val midEqBands: List<DspEqBand> = emptyList(),
    val sideEqBands: List<DspEqBand> = emptyList(),
    val lowWidth: Double = 1.0,
    val midWidth: Double = 1.0,
    val highWidth: Double = 1.0,
    val lowMidCrossoverHz: Double = DEFAULT_LOW_MID_CROSSOVER_HZ,
    val midHighCrossoverHz: Double = DEFAULT_MID_HIGH_CROSSOVER_HZ,
    val centerFocusDb: Double = 0.0,
) {
    init {
        require(midGainDb.isFinite() && midGainDb in MIN_CHANNEL_GAIN_DB..MAX_CHANNEL_GAIN_DB)
        require(sideGainDb.isFinite() && sideGainDb in MIN_CHANNEL_GAIN_DB..MAX_CHANNEL_GAIN_DB)
        require(midEqBands.size <= MAX_EQ_BANDS && sideEqBands.size <= MAX_EQ_BANDS)
        require(lowWidth.isFinite() && lowWidth in 0.0..MAX_BAND_WIDTH)
        require(midWidth.isFinite() && midWidth in 0.0..MAX_BAND_WIDTH)
        require(highWidth.isFinite() && highWidth in 0.0..MAX_BAND_WIDTH)
        require(lowMidCrossoverHz.isFinite() && lowMidCrossoverHz in MIN_LOW_MID_CROSSOVER_HZ..MAX_LOW_MID_CROSSOVER_HZ)
        require(midHighCrossoverHz.isFinite() && midHighCrossoverHz in MIN_MID_HIGH_CROSSOVER_HZ..MAX_MID_HIGH_CROSSOVER_HZ)
        require(lowMidCrossoverHz < midHighCrossoverHz)
        require(centerFocusDb.isFinite() && centerFocusDb in MIN_CENTER_FOCUS_DB..MAX_CENTER_FOCUS_DB)
    }

    companion object {
        const val MAX_EQ_BANDS = 5
        const val NATIVE_VALUE_COUNT = 12
        const val NATIVE_EQ_COEFFICIENT_COUNT = 55
        const val DEFAULT_LOW_MID_CROSSOVER_HZ = 150.0
        const val DEFAULT_MID_HIGH_CROSSOVER_HZ = 4_000.0
        const val MIN_LOW_MID_CROSSOVER_HZ = 80.0
        const val MAX_LOW_MID_CROSSOVER_HZ = 500.0
        const val MIN_MID_HIGH_CROSSOVER_HZ = 1_000.0
        const val MAX_MID_HIGH_CROSSOVER_HZ = 8_000.0
        const val MAX_BAND_WIDTH = 1.5
        private const val MIN_CHANNEL_GAIN_DB = -6.0
        private const val MAX_CHANNEL_GAIN_DB = 6.0
        private const val MIN_CENTER_FOCUS_DB = -3.0
        private const val MAX_CENTER_FOCUS_DB = 3.0
    }
}

enum class DspCabinCutSource {
    MANUAL,
    MEASUREMENT,
}

data class DspCabinCut(
    val frequencyHz: Double,
    val q: Double,
    val reductionDb: Double,
    val enabled: Boolean = false,
    val source: DspCabinCutSource = DspCabinCutSource.MANUAL,
    val prominenceDb: Double = 0.0,
    val confidence: Double = 0.0,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(q.isFinite() && q in MIN_Q..MAX_Q)
        require(reductionDb.isFinite() && reductionDb in MIN_REDUCTION_DB..0.0)
        require(prominenceDb.isFinite() && prominenceDb in 0.0..MAX_PROMINENCE_DB)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    companion object {
        private const val MIN_FREQUENCY_HZ = 40.0
        private const val MAX_FREQUENCY_HZ = 250.0
        private const val MIN_Q = 0.5
        private const val MAX_Q = 8.0
        private const val MIN_REDUCTION_DB = -6.0
        private const val MAX_PROMINENCE_DB = 24.0
    }
}

data class DspCabinCorrectionConfig(
    val enabled: Boolean = false,
    val bypassed: Boolean = false,
    val calibrationId: String? = null,
    val calibrationVersion: Int = CALIBRATION_FORMAT_VERSION,
    val cuts: List<DspCabinCut> = emptyList(),
    val dynamicControlEnabled: Boolean = false,
    val maxDynamicCutDb: Double = 3.0,
) {
    init {
        require(calibrationId == null || CALIBRATION_ID_PATTERN.matches(calibrationId))
        require(calibrationVersion in 1..CALIBRATION_FORMAT_VERSION)
        require(cuts.size <= MAX_CUTS)
        require(maxDynamicCutDb.isFinite() && maxDynamicCutDb in 0.0..MAX_DYNAMIC_CUT_DB)
        if (cuts.any { it.source == DspCabinCutSource.MEASUREMENT }) {
            require(calibrationId != null)
        }
        if (dynamicControlEnabled) {
            require(calibrationId != null)
            require(cuts.any { it.enabled && it.source == DspCabinCutSource.MEASUREMENT })
        }
    }

    companion object {
        const val MAX_CUTS = 5
        const val CALIBRATION_FORMAT_VERSION = 1
        private const val MAX_DYNAMIC_CUT_DB = 3.0
        private val CALIBRATION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

enum class DspHarmonicQuality {
    TWO_X,
    FOUR_X,
}

enum class DspHarmonicMode {
    EVEN,
    ODD,
    MIXED,
}

data class DspHighExciterConfig(
    val enabled: Boolean = false,
    val frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
    val drive: Double = 1.0,
    val amount: Double = 0.5,
    val mode: DspHarmonicMode = DspHarmonicMode.MIXED,
    val wet: Double = 0.0,
    val outputCompensationDb: Double = 0.0,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(drive.isFinite() && drive in 0.0..MAX_DRIVE)
        require(amount.isFinite() && amount in 0.0..1.0)
        require(wet.isFinite() && wet in 0.0..1.0)
        require(outputCompensationDb.isFinite() && outputCompensationDb in MIN_COMPENSATION_DB..MAX_COMPENSATION_DB)
    }

    companion object {
        const val DEFAULT_FREQUENCY_HZ = 5_000.0
        const val MIN_FREQUENCY_HZ = 1_000.0
        const val MAX_FREQUENCY_HZ = 16_000.0
        const val MAX_DRIVE = 8.0
        private const val MIN_COMPENSATION_DB = -12.0
        private const val MAX_COMPENSATION_DB = 6.0
    }
}

data class DspVirtualBassConfig(
    val enabled: Boolean = false,
    val fundamentalLowHz: Double = 35.0,
    val fundamentalHighHz: Double = 80.0,
    val drive: Double = 1.0,
    val amount: Double = 0.5,
    val secondHarmonicAmount: Double = 0.5,
    val thirdHarmonicAmount: Double = 0.25,
    val wet: Double = 0.0,
) {
    init {
        require(fundamentalLowHz.isFinite() && fundamentalLowHz in MIN_FUNDAMENTAL_HZ..MAX_FUNDAMENTAL_HZ)
        require(fundamentalHighHz.isFinite() && fundamentalHighHz in MIN_FUNDAMENTAL_HZ..MAX_FUNDAMENTAL_HZ)
        require(fundamentalLowHz < fundamentalHighHz)
        require(drive.isFinite() && drive in 0.0..MAX_DRIVE)
        require(amount.isFinite() && amount in 0.0..1.0)
        require(secondHarmonicAmount.isFinite() && secondHarmonicAmount in 0.0..1.0)
        require(thirdHarmonicAmount.isFinite() && thirdHarmonicAmount in 0.0..1.0)
        require(wet.isFinite() && wet in 0.0..1.0)
    }

    companion object {
        const val MIN_FUNDAMENTAL_HZ = 35.0
        const val MAX_FUNDAMENTAL_HZ = 80.0
        const val MAX_DRIVE = 8.0
    }
}

data class DspHarmonicEngineConfig(
    val enabled: Boolean = false,
    val bypassed: Boolean = false,
    val quality: DspHarmonicQuality = DspHarmonicQuality.TWO_X,
    val highExciter: DspHighExciterConfig = DspHighExciterConfig(),
    val virtualBass: DspVirtualBassConfig = DspVirtualBassConfig(),
) {
    init {
        require(quality == DspHarmonicQuality.TWO_X || quality == DspHarmonicQuality.FOUR_X)
    }

    companion object {
        const val NATIVE_VALUE_COUNT = 18
        const val NATIVE_FILTER_COEFFICIENT_COUNT = 20
        const val ALGORITHM_LATENCY_FRAMES = 64
    }
}
