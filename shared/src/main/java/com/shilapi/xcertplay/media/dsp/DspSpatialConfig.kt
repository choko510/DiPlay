package com.shilapi.xcertplay.media.dsp

enum class DspSeatPreset {
    DRIVER,
    PASSENGER,
    CENTER,
    CUSTOM,
}

enum class DspSeatNearSpeaker(val nativeValue: Int) {
    CENTER(0),
    LEFT(1),
    RIGHT(2),
}

internal const val DSP_SPATIAL_NATIVE_VALUE_COUNT = 10

/** Stereo seat alignment with an explicitly selected nearer channel; steering side is never inferred. */
data class DspSeatAlignmentConfig(
    val enabled: Boolean = false,
    val preset: DspSeatPreset = DspSeatPreset.CENTER,
    val nearSpeaker: DspSeatNearSpeaker = DspSeatNearSpeaker.CENTER,
    val distanceDifferenceCm: Double = 0.0,
    val leftTrimDb: Double = 0.0,
    val rightTrimDb: Double = 0.0,
    val invertLeft: Boolean = false,
    val invertRight: Boolean = false,
) {
    init {
        require(distanceDifferenceCm.isFinite() && distanceDifferenceCm in 0.0..MAX_DISTANCE_DIFFERENCE_CM)
        require(nearSpeaker != DspSeatNearSpeaker.CENTER || distanceDifferenceCm == 0.0)
        require(leftTrimDb.isFinite() && leftTrimDb in MIN_TRIM_DB..MAX_TRIM_DB)
        require(rightTrimDb.isFinite() && rightTrimDb in MIN_TRIM_DB..MAX_TRIM_DB)
    }

    fun relativeDelayFrames(sampleRate: Int): Double {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        if (!enabled || nearSpeaker == DspSeatNearSpeaker.CENTER) return 0.0
        return distanceDifferenceCm * sampleRate / SPEED_OF_SOUND_CM_PER_SECOND
    }

    fun withPreset(value: DspSeatPreset): DspSeatAlignmentConfig = if (value == DspSeatPreset.CENTER) {
        copy(preset = value, nearSpeaker = DspSeatNearSpeaker.CENTER, distanceDifferenceCm = 0.0)
    } else {
        copy(preset = value)
    }

    fun withNearSpeaker(value: DspSeatNearSpeaker): DspSeatAlignmentConfig = if (value == DspSeatNearSpeaker.CENTER) {
        copy(nearSpeaker = value, distanceDifferenceCm = 0.0)
    } else {
        copy(nearSpeaker = value)
    }

    val requiresStateContinuity: Boolean
        get() = enabled && nearSpeaker != DspSeatNearSpeaker.CENTER && distanceDifferenceCm > 0.0

    val positiveTrimForHeadroomDb: Double
        get() = if (enabled) maxOf(0.0, leftTrimDb, rightTrimDb) else 0.0

    companion object {
        private const val SPEED_OF_SOUND_CM_PER_SECOND = 34_300.0
        const val MAX_RELATIVE_DELAY_MILLIS = 5.0
        const val MAX_DISTANCE_DIFFERENCE_CM = SPEED_OF_SOUND_CM_PER_SECOND * MAX_RELATIVE_DELAY_MILLIS / 1000.0
        const val MIN_TRIM_DB = -6.0
        const val MAX_TRIM_DB = 6.0
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000
    }
}

data class DspBassConfig(
    val enabled: Boolean = false,
    val gainDb: Double = 0.0,
    val frequencyHz: Double = 80.0,
) {
    init {
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(frequencyHz.isFinite() && frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
    }

    companion object {
        private const val MIN_GAIN_DB = -18.0
        private const val MAX_GAIN_DB = 18.0
        private const val MIN_FREQUENCY_HZ = 20.0
        private const val MAX_FREQUENCY_HZ = 300.0
    }
}

data class DspMonoBassConfig(
    val enabled: Boolean = false,
    val cutoffHz: Int = 120,
) {
    init {
        require(cutoffHz in CUTOFFS_HZ)
    }

    companion object {
        val CUTOFFS_HZ = setOf(60, 80, 100, 120, 150, 200)
    }
}

data class DspConvolverConfig(
    val enabled: Boolean = false,
    val impulseResponseId: String? = null,
    val wet: Double = 1.0,
    val impulseResponse: DspImpulseResponse? = null,
) {
    init {
        require(impulseResponseId == null || PROFILE_ID.matches(impulseResponseId))
        require(impulseResponse == null || impulseResponse.id == impulseResponseId)
        require(wet.isFinite() && wet in 0.0..1.0)
    }

    companion object {
        private val PROFILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
