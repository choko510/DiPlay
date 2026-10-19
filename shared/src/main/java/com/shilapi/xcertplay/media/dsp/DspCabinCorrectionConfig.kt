package com.shilapi.xcertplay.media.dsp

data class DspCabinNotchConfig(
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean = true,
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
        enabled = enabled,
    )

    companion object {
        const val MAX_FREQUENCY_HZ = 300.0
        const val MIN_FREQUENCY_HZ = 20.0
        const val MIN_GAIN_DB = -12.0
        const val MAX_GAIN_DB = -3.0
        const val MIN_Q = 0.7
        const val MAX_Q = 10.0
    }
}

data class DspCabinMeasurementSummary(
    val noiseFloorDb: Double,
    val pointCount: Int,
    val minimumFrequencyHz: Double,
    val maximumFrequencyHz: Double,
    val clippedSamples: Int,
) {
    init {
        require(noiseFloorDb.isFinite() && noiseFloorDb in MIN_NOISE_FLOOR_DB..MAX_NOISE_FLOOR_DB)
        require(pointCount in MIN_POINT_COUNT..MAX_POINT_COUNT)
        require(minimumFrequencyHz.isFinite() && minimumFrequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(maximumFrequencyHz.isFinite() && maximumFrequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ)
        require(minimumFrequencyHz < maximumFrequencyHz)
        require(clippedSamples == 0)
    }

    companion object {
        const val MIN_NOISE_FLOOR_DB = -120.0
        const val MAX_NOISE_FLOOR_DB = -20.0
        const val MIN_FREQUENCY_HZ = 20.0
        const val MAX_FREQUENCY_HZ = 300.0
        const val MIN_POINT_COUNT = 32
        const val MAX_POINT_COUNT = 4_096
    }
}

data class DspCabinCorrectionConfig(
    val enabled: Boolean = false,
    val measurement: DspCabinMeasurementSummary? = null,
    val notches: List<DspCabinNotchConfig> = emptyList(),
) {
    init {
        require(notches.size <= MAX_NOTCHES)
        require(!enabled || (measurement != null && notches.any(DspCabinNotchConfig::enabled)))
    }

    companion object {
        const val MAX_NOTCHES = 3
    }
}

enum class DspCabinUnavailableReason {
    MISSING_QUALITY_METADATA,
    INPUT_CLIPPED,
    NOISE_TOO_HIGH,
    TOO_FEW_POINTS,
    INSUFFICIENT_FREQUENCY_SPAN,
    INSUFFICIENT_FREQUENCY_RESOLUTION,
    NO_SIGNIFICANT_PEAKS,
    INSUFFICIENT_PEQ_CAPACITY,
}

sealed interface DspCabinMeasurementImportResult {
    data class Ready(
        val summary: DspCabinMeasurementSummary,
        val suggestedNotches: List<DspCabinNotchConfig>,
    ) : DspCabinMeasurementImportResult

    data class CalibrationUnavailable(val reason: DspCabinUnavailableReason) : DspCabinMeasurementImportResult
    data object InvalidCsv : DspCabinMeasurementImportResult
    data object TooLarge : DspCabinMeasurementImportResult
}
