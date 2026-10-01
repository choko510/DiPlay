package com.shilapi.xcertplay.media.dsp

enum class DspEqType {
    PEAK,
    LOW_SHELF,
    HIGH_SHELF,
    HIGH_PASS,
    LOW_PASS,
    NOTCH,
    ALL_PASS,
}

data class DspEqBand(
    val type: DspEqType,
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 0.7071067811865476,
    val enabled: Boolean = true,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz >= MIN_FREQUENCY_HZ)
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(q.isFinite() && q in MIN_Q..MAX_Q)
    }

    companion object {
        private const val MIN_FREQUENCY_HZ = 20.0
        private const val MIN_GAIN_DB = -18.0
        private const val MAX_GAIN_DB = 18.0
        private const val MIN_Q = 0.1
        private const val MAX_Q = 20.0
    }
}
