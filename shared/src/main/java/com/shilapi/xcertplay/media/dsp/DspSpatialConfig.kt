package com.shilapi.xcertplay.media.dsp

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
) {
    init {
        require(impulseResponseId == null || PROFILE_ID.matches(impulseResponseId))
        require(wet.isFinite() && wet in 0.0..1.0)
    }

    companion object {
        private val PROFILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
