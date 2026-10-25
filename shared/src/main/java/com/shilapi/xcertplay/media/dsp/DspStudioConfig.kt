package com.shilapi.xcertplay.media.dsp

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
