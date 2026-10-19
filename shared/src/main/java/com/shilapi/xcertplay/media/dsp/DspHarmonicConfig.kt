package com.shilapi.xcertplay.media.dsp

import kotlin.math.log10

enum class DspHarmonicMode(val nativeValue: Int) {
    EVEN(0),
    ODD(1),
    MIXED(2),
}

enum class DspHarmonicQuality(val taps: Int) {
    LOW(9),
    MEDIUM(17),
    HIGH(33),
}

internal const val DSP_HARMONIC_NATIVE_VALUE_COUNT = 10

data class DspHarmonicConfig(
    val enabled: Boolean = false,
    val mode: DspHarmonicMode = DspHarmonicMode.MIXED,
    val quality: DspHarmonicQuality = DspHarmonicQuality.MEDIUM,
    val exciterEnabled: Boolean = false,
    val exciterAmount: Double = 0.2,
    val exciterCutoffHz: Double = 6_000.0,
    val virtualBassEnabled: Boolean = false,
    val virtualBassAmount: Double = 0.2,
    val virtualBassCutoffHz: Double = 120.0,
    val levelMatchEnabled: Boolean = true,
) {
    init {
        require(exciterAmount.isFinite() && exciterAmount in MIN_AMOUNT..MAX_AMOUNT)
        require(exciterCutoffHz.isFinite() && exciterCutoffHz in MIN_EXCITER_CUTOFF_HZ..MAX_EXCITER_CUTOFF_HZ)
        require(virtualBassAmount.isFinite() && virtualBassAmount in MIN_AMOUNT..MAX_AMOUNT)
        require(virtualBassCutoffHz.isFinite() && virtualBassCutoffHz in MIN_VIRTUAL_BASS_CUTOFF_HZ..MAX_VIRTUAL_BASS_CUTOFF_HZ)
    }

    val isActive: Boolean
        get() = enabled && (
            exciterEnabled && exciterAmount > 0.0 ||
                virtualBassEnabled && virtualBassAmount > 0.0
            )

    val requiresStateContinuity: Boolean
        get() = isActive

    val algorithmicLatencyFrames: Int
        get() = if (isActive) (quality.taps - 1) / 2 else 0

    val worstCaseBoostDb: Double
        get() {
            if (!isActive) return 0.0
            val additiveAmount =
                (if (exciterEnabled) exciterAmount else 0.0) +
                    (if (virtualBassEnabled) virtualBassAmount else 0.0)
            return 20.0 * log10(1.0 + additiveAmount)
        }

    fun validateForSampleRate(sampleRate: Int) {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        val maximumCutoff = sampleRate * MAX_NORMALIZED_CUTOFF
        if (isActive && exciterEnabled) require(exciterCutoffHz < maximumCutoff)
        if (isActive && virtualBassEnabled) require(virtualBassCutoffHz < maximumCutoff)
    }

    companion object {
        const val MIN_AMOUNT = 0.0
        const val MAX_AMOUNT = 0.5
        const val MIN_EXCITER_CUTOFF_HZ = 1_000.0
        const val MAX_EXCITER_CUTOFF_HZ = 18_000.0
        const val MIN_VIRTUAL_BASS_CUTOFF_HZ = 60.0
        const val MAX_VIRTUAL_BASS_CUTOFF_HZ = 250.0
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000
        private const val MAX_NORMALIZED_CUTOFF = 0.45
    }
}
