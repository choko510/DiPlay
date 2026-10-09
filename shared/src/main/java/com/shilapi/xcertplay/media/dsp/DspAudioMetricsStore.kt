package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicInteger

enum class DspClipRisk {
    LOW,
    ELEVATED,
    HIGH,
}

data class DspAudioMetricsSnapshot(
    val inputCorrelation: Double,
    val outputCorrelation: Double,
    val outputPeak: Double,
    val clipRisk: DspClipRisk,
)

object DspAudioMetricsStore {
    private val inputCorrelationBits = AtomicInteger(0)
    private val outputCorrelationBits = AtomicInteger(0)
    private val outputPeakBits = AtomicInteger(0)
    private val clipRisk = AtomicInteger(DspClipRisk.LOW.ordinal)

    internal fun update(diagnostics: DspDiagnosticsSnapshot) {
        val inputCorrelation = diagnostics.inputCorrelation.toFloat().takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        val outputCorrelation = diagnostics.outputCorrelation.toFloat().takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        val outputPeak = maxOf(diagnostics.outputPeakL, diagnostics.outputPeakR)
            .toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0.0f) ?: 0.0f
        inputCorrelationBits.set(java.lang.Float.floatToIntBits(inputCorrelation))
        outputCorrelationBits.set(java.lang.Float.floatToIntBits(outputCorrelation))
        outputPeakBits.set(java.lang.Float.floatToIntBits(outputPeak))
        clipRisk.set(
            when {
                outputPeak >= HIGH_CLIP_RISK_PEAK -> DspClipRisk.HIGH.ordinal
                outputPeak >= ELEVATED_CLIP_RISK_PEAK -> DspClipRisk.ELEVATED.ordinal
                else -> DspClipRisk.LOW.ordinal
            },
        )
    }

    fun snapshot(): DspAudioMetricsSnapshot = DspAudioMetricsSnapshot(
        inputCorrelation = java.lang.Float.intBitsToFloat(inputCorrelationBits.get()).toDouble(),
        outputCorrelation = java.lang.Float.intBitsToFloat(outputCorrelationBits.get()).toDouble(),
        outputPeak = java.lang.Float.intBitsToFloat(outputPeakBits.get()).toDouble(),
        clipRisk = DspClipRisk.entries[clipRisk.get().coerceIn(0, DspClipRisk.entries.lastIndex)],
    )

    private const val ELEVATED_CLIP_RISK_PEAK = 0.90f
    private const val HIGH_CLIP_RISK_PEAK = 1.0f
}
