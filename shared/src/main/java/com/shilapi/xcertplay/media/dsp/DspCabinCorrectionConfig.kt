package com.shilapi.xcertplay.media.dsp

import java.util.Collections

data class DspCabinCorrectionNotch(
    val enabled: Boolean = false,
    val frequencyHz: Double = 100.0,
    val gainDb: Double = -3.0,
    val q: Double = 4.0,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(q.isFinite() && q in MIN_Q..MAX_Q)
    }

    fun toEqBand(): DspEqBand = DspEqBand(
        type = DspEqType.PEAK,
        frequencyHz = frequencyHz,
        gainDb = gainDb,
        q = q,
        enabled = true,
    )

    companion object {
        const val MIN_FREQUENCY_HZ = 20.0
        const val MAX_FREQUENCY_HZ = 300.0
        const val MIN_GAIN_DB = -12.0
        const val MAX_GAIN_DB = 0.0
        const val MIN_Q = 0.5
        const val MAX_Q = 10.0
    }
}

data class DspCabinMeasurementSummary(
    val sourceSha256: String,
    val pointCount: Int,
    val minimumFrequencyHz: Double,
    val maximumFrequencyHz: Double,
    val noiseFloorDb: Double,
    val clippingDetected: Boolean,
) {
    init {
        require(SHA256_PATTERN.matches(sourceSha256))
        require(pointCount in MIN_POINTS..MAX_POINTS)
        require(minimumFrequencyHz.isFinite() && minimumFrequencyHz in 0.0..300.0)
        require(maximumFrequencyHz.isFinite() && maximumFrequencyHz in 300.0..20_000.0)
        require(noiseFloorDb.isFinite() && noiseFloorDb in MIN_NOISE_FLOOR_DB..MAX_NOISE_FLOOR_DB)
        require(!clippingDetected)
    }

    companion object {
        const val MIN_POINTS = 20
        const val MAX_POINTS = 256
        const val MIN_NOISE_FLOOR_DB = -160.0
        const val MAX_NOISE_FLOOR_DB = 0.0
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}

class DspCabinCorrectionConfig(
    val enabled: Boolean = false,
    val measurement: DspCabinMeasurementSummary? = null,
    notches: List<DspCabinCorrectionNotch> = emptyList(),
) {
    val notches: List<DspCabinCorrectionNotch> = Collections.unmodifiableList(ArrayList(notches))

    init {
        require(this.notches.size <= MAX_NOTCHES)
        require(!enabled || this.notches.any(DspCabinCorrectionNotch::enabled))
    }

    fun approvedEqBands(): List<DspEqBand> = if (enabled) {
        notches.filter(DspCabinCorrectionNotch::enabled).map(DspCabinCorrectionNotch::toEqBand)
    } else {
        emptyList()
    }

    fun copy(
        enabled: Boolean = this.enabled,
        measurement: DspCabinMeasurementSummary? = this.measurement,
        notches: List<DspCabinCorrectionNotch> = this.notches,
    ): DspCabinCorrectionConfig = DspCabinCorrectionConfig(enabled, measurement, notches)

    override fun equals(other: Any?): Boolean = other is DspCabinCorrectionConfig &&
        enabled == other.enabled && measurement == other.measurement && notches == other.notches

    override fun hashCode(): Int = 31 * (31 * enabled.hashCode() + (measurement?.hashCode() ?: 0)) + notches.hashCode()

    override fun toString(): String =
        "DspCabinCorrectionConfig(enabled=$enabled, measurement=$measurement, notches=$notches)"

    companion object {
        const val MAX_NOTCHES = 3
    }
}
