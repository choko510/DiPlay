package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicReference

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
    val activeModulesMask: Int = 0,
    val seatLatencyFrames: Int = 0,
    val algorithmicLatencyFrames: Int = 0,
    val harmonicQuality: DspHarmonicQuality? = null,
    val autoHeadroomDb: Double = 0.0,
    val cabinCalibrationId: String? = null,
    val nativeErrorCount: Long = 0,
    val processP99Us: Double = 0.0,
)

object DspAudioMetricsStore {
    private val latest = AtomicReference(
        DspAudioMetricsSnapshot(
            inputCorrelation = 0.0,
            outputCorrelation = 0.0,
            outputPeak = 0.0,
            clipRisk = DspClipRisk.LOW,
        ),
    )

    internal fun update(diagnostics: DspDiagnosticsSnapshot) {
        val inputCorrelation = diagnostics.inputCorrelation.toFloat()
            .takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        val outputCorrelation = diagnostics.outputCorrelation.toFloat()
            .takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        val outputPeak = maxOf(diagnostics.outputPeakL, diagnostics.outputPeakR)
            .toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0.0f) ?: 0.0f
        latest.set(
            DspAudioMetricsSnapshot(
                inputCorrelation = inputCorrelation.toDouble(),
                outputCorrelation = outputCorrelation.toDouble(),
                outputPeak = outputPeak.toDouble(),
                clipRisk = when {
                    outputPeak >= HIGH_CLIP_RISK_PEAK -> DspClipRisk.HIGH
                    outputPeak >= ELEVATED_CLIP_RISK_PEAK -> DspClipRisk.ELEVATED
                    else -> DspClipRisk.LOW
                },
                activeModulesMask = diagnostics.activeModulesMask,
                seatLatencyFrames = diagnostics.seatLatencyFrames,
                algorithmicLatencyFrames = diagnostics.algorithmicLatencyFrames,
                harmonicQuality = diagnostics.harmonicQuality,
                autoHeadroomDb = diagnostics.autoHeadroomDb.takeIf(Double::isFinite) ?: 0.0,
                cabinCalibrationId = diagnostics.cabinCalibrationId,
                nativeErrorCount = diagnostics.nativeErrorCount,
                processP99Us = diagnostics.pipelineProcessUs.p99Us.toDouble(),
            ),
        )
    }

    fun snapshot(): DspAudioMetricsSnapshot = latest.get()

    private const val ELEVATED_CLIP_RISK_PEAK = 0.90f
    private const val HIGH_CLIP_RISK_PEAK = 1.0f
}
