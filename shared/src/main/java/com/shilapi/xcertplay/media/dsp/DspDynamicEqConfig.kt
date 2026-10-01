package com.shilapi.xcertplay.media.dsp

import java.util.Collections

enum class DspDynamicEqMode {
    CUT,
    BOOST,
}

data class DspDynamicEqBandConfig(
    val enabled: Boolean = false,
    val frequencyHz: Double = 1_000.0,
    val q: Double = 1.0,
    val thresholdDb: Double = -24.0,
    val ratio: Double = 2.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 100.0,
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 6.0,
    val mode: DspDynamicEqMode = DspDynamicEqMode.CUT,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(q.isFinite() && q in MIN_Q..MAX_Q)
        require(thresholdDb.isFinite() && thresholdDb in MIN_THRESHOLD_DB..0.0)
        require(ratio.isFinite() && ratio in MIN_RATIO..MAX_RATIO)
        require(attackMs.isFinite() && attackMs in MIN_TIME_MS..MAX_TIME_MS)
        require(releaseMs.isFinite() && releaseMs in MIN_TIME_MS..MAX_TIME_MS)
        require(maxBoostDb.isFinite() && maxBoostDb in 0.0..MAX_GAIN_DB)
        require(maxCutDb.isFinite() && maxCutDb in 0.0..MAX_GAIN_DB)
    }

    companion object {
        private const val MIN_FREQUENCY_HZ = 20.0
        private const val MAX_FREQUENCY_HZ = 20_000.0
        private const val MIN_Q = 0.1
        private const val MAX_Q = 20.0
        private const val MIN_THRESHOLD_DB = -60.0
        private const val MIN_RATIO = 1.0
        private const val MAX_RATIO = 20.0
        private const val MIN_TIME_MS = 0.1
        private const val MAX_TIME_MS = 2_000.0
        private const val MAX_GAIN_DB = 12.0
    }
}

class DspDynamicEqConfig(
    val enabled: Boolean = false,
    bands: List<DspDynamicEqBandConfig> = defaultBands(),
) {
    val bands: List<DspDynamicEqBandConfig> = Collections.unmodifiableList(ArrayList(bands))

    init {
        require(this.bands.size <= MAX_BANDS)
    }

    internal fun toNativeValues(): DoubleArray {
        val values = DoubleArray(NATIVE_VALUE_COUNT)
        values[0] = if (enabled) 1.0 else 0.0
        for (bandIndex in 0 until MAX_BANDS) {
            val band = bands.getOrNull(bandIndex) ?: DspDynamicEqBandConfig()
            val start = HEADER_VALUE_COUNT + bandIndex * VALUES_PER_BAND
            values[start] = if (band.enabled) 1.0 else 0.0
            values[start + 1] = when (band.mode) {
                DspDynamicEqMode.CUT -> 0.0
                DspDynamicEqMode.BOOST -> 1.0
            }
            values[start + 2] = band.frequencyHz
            values[start + 3] = band.q
            values[start + 4] = band.thresholdDb
            values[start + 5] = band.ratio
            values[start + 6] = band.attackMs
            values[start + 7] = band.releaseMs
            values[start + 8] = band.maxBoostDb
            values[start + 9] = band.maxCutDb
        }
        return values
    }

    override fun equals(other: Any?): Boolean = other is DspDynamicEqConfig &&
        enabled == other.enabled && bands == other.bands

    override fun hashCode(): Int = 31 * enabled.hashCode() + bands.hashCode()

    companion object {
        const val MAX_BANDS = 5
        const val NATIVE_VALUE_COUNT = 51
        private const val HEADER_VALUE_COUNT = 1
        private const val VALUES_PER_BAND = 10

        fun defaultBands(): List<DspDynamicEqBandConfig> = listOf(63.0, 250.0, 1_000.0, 4_000.0, 10_000.0)
            .map { frequency -> DspDynamicEqBandConfig(frequencyHz = frequency) }
    }
}
