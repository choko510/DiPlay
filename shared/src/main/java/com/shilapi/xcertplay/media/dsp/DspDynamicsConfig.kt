package com.shilapi.xcertplay.media.dsp

data class DspCompressorConfig(
    val enabled: Boolean = false,
    val thresholdDb: Double = -20.0,
    val ratio: Double = 4.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 100.0,
    val kneeDb: Double = 0.0,
    val makeupDb: Double = 0.0,
) {
    init {
        require(thresholdDb.isFinite() && thresholdDb in MIN_THRESHOLD_DB..0.0)
        require(ratio.isFinite() && ratio in MIN_RATIO..MAX_RATIO)
        require(attackMs.isFinite() && attackMs in MIN_TIME_MS..MAX_TIME_MS)
        require(releaseMs.isFinite() && releaseMs in MIN_TIME_MS..MAX_TIME_MS)
        require(kneeDb.isFinite() && kneeDb in 0.0..MAX_KNEE_DB)
        require(makeupDb.isFinite() && makeupDb in MIN_MAKEUP_DB..MAX_MAKEUP_DB)
    }

    companion object {
        private const val MIN_THRESHOLD_DB = -60.0
        private const val MIN_RATIO = 1.0
        private const val MAX_RATIO = 20.0
        private const val MIN_TIME_MS = 0.1
        private const val MAX_TIME_MS = 2_000.0
        private const val MAX_KNEE_DB = 24.0
        private const val MIN_MAKEUP_DB = -24.0
        private const val MAX_MAKEUP_DB = 24.0
    }
}

data class DspSafetyLimiterConfig(
    val enabled: Boolean = true,
    val thresholdDb: Double = -1.0,
    val releaseMs: Double = 60.0,
) {
    init {
        require(thresholdDb.isFinite() && thresholdDb in MIN_THRESHOLD_DB..0.0)
        require(releaseMs.isFinite() && releaseMs in MIN_RELEASE_MS..MAX_RELEASE_MS)
    }

    companion object {
        private const val MIN_THRESHOLD_DB = -12.0
        private const val MIN_RELEASE_MS = 1.0
        private const val MAX_RELEASE_MS = 2_000.0
    }
}
