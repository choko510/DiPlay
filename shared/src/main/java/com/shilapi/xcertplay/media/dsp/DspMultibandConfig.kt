package com.shilapi.xcertplay.media.dsp

data class DspMultibandConfig(
    val enabled: Boolean = false,
    val lowMidCrossoverHz: Double = DEFAULT_LOW_MID_CROSSOVER_HZ,
    val midHighCrossoverHz: Double = DEFAULT_MID_HIGH_CROSSOVER_HZ,
    val low: DspCompressorConfig = DspCompressorConfig(),
    val mid: DspCompressorConfig = DspCompressorConfig(),
    val high: DspCompressorConfig = DspCompressorConfig(),
) {
    init {
        require(lowMidCrossoverHz.isFinite() && lowMidCrossoverHz in MIN_CROSSOVER_HZ..MAX_LOW_MID_CROSSOVER_HZ)
        require(midHighCrossoverHz.isFinite() && midHighCrossoverHz in MIN_MID_HIGH_CROSSOVER_HZ..MAX_MID_HIGH_CROSSOVER_HZ)
        require(lowMidCrossoverHz < midHighCrossoverHz)
    }

    internal fun toNativeValues(): DoubleArray {
        val values = DoubleArray(NATIVE_VALUE_COUNT)
        values[0] = if (enabled) 1.0 else 0.0
        values[1] = lowMidCrossoverHz
        values[2] = midHighCrossoverHz
        listOf(low, mid, high).forEachIndexed { bandIndex, band ->
            val start = HEADER_VALUE_COUNT + bandIndex * VALUES_PER_BAND
            values[start] = if (band.enabled) 1.0 else 0.0
            values[start + 1] = band.thresholdDb
            values[start + 2] = band.ratio
            values[start + 3] = band.attackMs
            values[start + 4] = band.releaseMs
            values[start + 5] = band.kneeDb
            values[start + 6] = band.makeupDb
        }
        return values
    }

    companion object {
        const val DEFAULT_LOW_MID_CROSSOVER_HZ = 120.0
        const val DEFAULT_MID_HIGH_CROSSOVER_HZ = 2_500.0
        const val NATIVE_VALUE_COUNT = 24
        const val MAX_LOW_MID_CROSSOVER_HZ = 1_000.0
        const val MIN_MID_HIGH_CROSSOVER_HZ = 500.0
        const val MAX_MID_HIGH_CROSSOVER_HZ = 3_500.0
        private const val HEADER_VALUE_COUNT = 3
        private const val VALUES_PER_BAND = 7
        private const val MIN_CROSSOVER_HZ = 20.0
    }
}
