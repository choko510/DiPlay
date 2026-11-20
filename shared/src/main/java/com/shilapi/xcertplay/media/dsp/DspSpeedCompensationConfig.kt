package com.shilapi.xcertplay.media.dsp

/**
 * Transient, GPS driven boost layered on top of the user's saved DSP profile.
 *
 * This is runtime state only: it is never written to a profile, preset, or workspace, so
 * changing speed cannot pollute the user's EQ edits, undo/redo history, or A/B compare.
 * [reservedBoostDb] is the worst-case boost of the whole configured curve and is what the
 * auto-headroom calculation reserves, which keeps the applied preamp independent of the
 * current speed.
 */
data class DspSpeedCompensationConfig(
    val enabled: Boolean = false,
    val volumeGainDb: Double = 0.0,
    val lowGainDb: Double = 0.0,
    val midGainDb: Double = 0.0,
    val highGainDb: Double = 0.0,
    val reservedBoostDb: Double = 0.0,
) {
    init {
        require(volumeGainDb.isFinite() && volumeGainDb in 0.0..MAX_VOLUME_GAIN_DB)
        require(lowGainDb.isFinite() && lowGainDb in 0.0..MAX_LOW_GAIN_DB)
        require(midGainDb.isFinite() && midGainDb in 0.0..MAX_MID_GAIN_DB)
        require(highGainDb.isFinite() && highGainDb in 0.0..MAX_HIGH_GAIN_DB)
        require(reservedBoostDb.isFinite() && reservedBoostDb in 0.0..MAX_RESERVED_BOOST_DB)
    }

    val isNeutral: Boolean
        get() = volumeGainDb == 0.0 && lowGainDb == 0.0 && midGainDb == 0.0 && highGainDb == 0.0

    val active: Boolean get() = enabled || !isNeutral

    /** Native payload: [enabled, volume, low, mid, high]. */
    fun nativeValues(): DoubleArray = doubleArrayOf(
        if (enabled) 1.0 else 0.0,
        volumeGainDb,
        lowGainDb,
        midGainDb,
        highGainDb,
    )

    companion object {
        const val MAX_VOLUME_GAIN_DB = 6.0
        const val MAX_LOW_GAIN_DB = 6.0
        const val MAX_MID_GAIN_DB = 3.0
        const val MAX_HIGH_GAIN_DB = 4.0
        const val MAX_RESERVED_BOOST_DB = 20.0
        const val NATIVE_VALUE_COUNT = 5

        val NEUTRAL = DspSpeedCompensationConfig()
    }
}
