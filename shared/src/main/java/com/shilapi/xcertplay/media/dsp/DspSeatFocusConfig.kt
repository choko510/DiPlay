package com.shilapi.xcertplay.media.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

enum class DspSeatFocusPreset {
    DRIVER,
    PASSENGER,
    CENTER,
    CUSTOM,
}

object DspSeatFocusLimits {
    const val MAX_DELAY_MILLIS = 5.0
    const val MAX_DISTANCE_CM = 500.0
    const val MIN_TRIM_DB = -6.0
    const val MAX_TRIM_DB = 6.0
    const val SOUND_SPEED_CM_PER_SECOND = 34_300.0
}

/** The preset names the target seat; timing uses explicit physical left/right measurements only. */
data class DspSeatFocusConfig(
    val enabled: Boolean = false,
    val preset: DspSeatFocusPreset = DspSeatFocusPreset.CENTER,
    val leftDistanceCm: Double = 0.0,
    val rightDistanceCm: Double = 0.0,
    val leftTrimDb: Double = 0.0,
    val rightTrimDb: Double = 0.0,
    val leftPolarityInverted: Boolean = false,
    val rightPolarityInverted: Boolean = false,
) {
    init {
        require(leftDistanceCm.isFinite() && leftDistanceCm in 0.0..DspSeatFocusLimits.MAX_DISTANCE_CM)
        require(rightDistanceCm.isFinite() && rightDistanceCm in 0.0..DspSeatFocusLimits.MAX_DISTANCE_CM)
        require(leftTrimDb.isFinite() && leftTrimDb in DspSeatFocusLimits.MIN_TRIM_DB..DspSeatFocusLimits.MAX_TRIM_DB)
        require(rightTrimDb.isFinite() && rightTrimDb in DspSeatFocusLimits.MIN_TRIM_DB..DspSeatFocusLimits.MAX_TRIM_DB)
    }

    internal fun prepare(format: DspAudioFormat): DspPreparedSeatFocus {
        if (!enabled || format.channels != 2 || leftDistanceCm <= 0.0 || rightDistanceCm <= 0.0) {
            return DspPreparedSeatFocus.DISABLED
        }
        val maximumLatencyFrames = ceil(format.sampleRate * DspSeatFocusLimits.MAX_DELAY_MILLIS / 1000.0).toInt()
        val relativeDelayFrames = (
            abs(leftDistanceCm - rightDistanceCm) * format.sampleRate /
                DspSeatFocusLimits.SOUND_SPEED_CM_PER_SECOND
            ).coerceAtMost(maximumLatencyFrames.toDouble())
        val leftDelayFrames: Double
        val rightDelayFrames: Double
        when {
            leftDistanceCm > rightDistanceCm -> {
                leftDelayFrames = maximumLatencyFrames - relativeDelayFrames
                rightDelayFrames = maximumLatencyFrames.toDouble()
            }
            rightDistanceCm > leftDistanceCm -> {
                leftDelayFrames = maximumLatencyFrames.toDouble()
                rightDelayFrames = maximumLatencyFrames - relativeDelayFrames
            }
            else -> {
                leftDelayFrames = maximumLatencyFrames.toDouble()
                rightDelayFrames = maximumLatencyFrames.toDouble()
            }
        }
        return DspPreparedSeatFocus(
            enabled = true,
            maximumLatencyFrames = maximumLatencyFrames,
            leftDelayFrames = leftDelayFrames,
            rightDelayFrames = rightDelayFrames,
            leftTrimLinear = 10.0.pow(leftTrimDb / 20.0),
            rightTrimLinear = 10.0.pow(rightTrimDb / 20.0),
            leftPolarityInverted = leftPolarityInverted,
            rightPolarityInverted = rightPolarityInverted,
        )
    }
}

internal data class DspPreparedSeatFocus(
    val enabled: Boolean,
    val maximumLatencyFrames: Int,
    val leftDelayFrames: Double,
    val rightDelayFrames: Double,
    val leftTrimLinear: Double,
    val rightTrimLinear: Double,
    val leftPolarityInverted: Boolean,
    val rightPolarityInverted: Boolean,
) {
    companion object {
        val DISABLED = DspPreparedSeatFocus(
            enabled = false,
            maximumLatencyFrames = 0,
            leftDelayFrames = 0.0,
            rightDelayFrames = 0.0,
            leftTrimLinear = 1.0,
            rightTrimLinear = 1.0,
            leftPolarityInverted = false,
            rightPolarityInverted = false,
        )
    }
}
