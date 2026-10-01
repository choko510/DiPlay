package com.shilapi.xcertplay.media.dsp

data class DspRuntimeConfig(
    val enabled: Boolean,
    val gainDb: Double = 0.0,
) {
    init {
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
    }

    companion object {
        fun disabled(): DspRuntimeConfig = DspRuntimeConfig(enabled = false)

        private const val MIN_GAIN_DB = -120.0
        private const val MAX_GAIN_DB = 24.0
    }
}
