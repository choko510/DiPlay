package com.shilapi.xcertplay.media.dsp

import kotlin.math.log10

data class DspMacroToneConfig(
    val enabled: Boolean = false,
    val bassGainDb: Double = 0.0,
    val vocalGainDb: Double = 0.0,
    val airGainDb: Double = 0.0,
    val relativeWidth: Double = 1.0,
) {
    init {
        require(bassGainDb.isFinite() && bassGainDb in -2.0..4.0)
        require(vocalGainDb.isFinite() && vocalGainDb in -2.0..2.5)
        require(airGainDb.isFinite() && airGainDb in -2.0..2.0)
        require(relativeWidth.isFinite() && relativeWidth in 0.75..1.25)
    }
}

internal fun DspMacroToneConfig.conservativeBoostDb(): Double {
    if (!enabled) return 0.0
    fun gain(db: Double) = StrictMath.pow(10.0, db / 20.0)
    val amplitudeBound = 1.0 + kotlin.math.abs(gain(bassGainDb) - 1.0) +
        2.0 * kotlin.math.abs(gain(vocalGainDb) - 1.0) +
        2.0 * kotlin.math.abs(gain(airGainDb) - 1.0)
    return 20.0 * log10(amplitudeBound)
}

data class DspSmartLoudnessRuntimeConfig(
    val enabled: Boolean = false,
    val lowGainDb: Double = 0.0,
    val highGainDb: Double = 0.0,
) {
    init {
        require(lowGainDb.isFinite() && lowGainDb in 0.0..3.0)
        require(highGainDb.isFinite() && highGainDb in 0.0..1.5)
    }
}

internal fun DspSmartLoudnessRuntimeConfig.conservativeBoostDb(): Double {
    if (!enabled) return 0.0
    val lowGain = StrictMath.pow(10.0, lowGainDb / 20.0)
    val highGain = StrictMath.pow(10.0, highGainDb / 20.0)
    // The first-order residual `sample - lowpass(sample)` has an L1 impulse gain no greater than 2.
    val amplitudeBound = lowGain + 2.0 * (highGain - 1.0)
    return 20.0 * log10(amplitudeBound)
}

data class DspDynamicBassRuntimeConfig(
    val enabled: Boolean = false,
    val strength: Double = 0.5,
    val maxBoostDb: Double = 3.0,
    val detectorCutoffHz: Double = 120.0,
    val silenceGateDbfs: Double = -55.0,
    val lowThresholdDbfs: Double = -35.0,
    val highThresholdDbfs: Double = -10.0,
    val attackMs: Double = 40.0,
    val releaseMs: Double = 250.0,
) {
    init {
        require(strength.isFinite() && strength in 0.0..1.0)
        require(maxBoostDb.isFinite() && maxBoostDb in 0.0..3.0)
        require(detectorCutoffHz.isFinite() && detectorCutoffHz in 80.0..160.0)
        require(silenceGateDbfs.isFinite() && silenceGateDbfs in -90.0..-30.0)
        require(lowThresholdDbfs.isFinite() && lowThresholdDbfs in -60.0..0.0)
        require(highThresholdDbfs.isFinite() && highThresholdDbfs in -60.0..0.0)
        require(lowThresholdDbfs < highThresholdDbfs)
        require(attackMs.isFinite() && attackMs in 10.0..200.0)
        require(releaseMs.isFinite() && releaseMs in 50.0..1_000.0)
    }
}
