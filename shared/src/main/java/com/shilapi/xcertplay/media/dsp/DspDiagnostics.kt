package com.shilapi.xcertplay.media.dsp

internal data class DspDiagnosticsSnapshot(
    val processedFrames: Long = 0,
    val processedBlocks: Long = 0,
    val incompleteFrameBytes: Long = 0,
    val nonFiniteInputSamples: Long = 0,
    val nonFiniteOutputSamples: Long = 0,
    val processorFailures: Long = 0,
    val nativeErrorCount: Long = 0,
    val activeModulesMask: Int = 0,
    val seatLatencyFrames: Int = 0,
    val algorithmicLatencyFrames: Int = 0,
    val harmonicQuality: DspHarmonicQuality? = null,
    val autoHeadroomDb: Double = 0.0,
    val cabinCalibrationId: String? = null,
    val inputPeakL: Double = 0.0,
    val inputPeakR: Double = 0.0,
    val inputRmsL: Double = 0.0,
    val inputRmsR: Double = 0.0,
    val outputPeakL: Double = 0.0,
    val outputPeakR: Double = 0.0,
    val outputRmsL: Double = 0.0,
    val outputRmsR: Double = 0.0,
    val inputCorrelation: Double = 0.0,
    val outputCorrelation: Double = 0.0,
    val nativeProcessUs: DspTimingSummary = DspTimingSummary(),
    val pipelineProcessUs: DspTimingSummary = DspTimingSummary(),
) {
    companion object {
        val EMPTY = DspDiagnosticsSnapshot()
    }
}

internal object DspActiveModuleMask {
    const val MASTER = 1 shl 0
    const val PREAMP = 1 shl 1
    const val PARAMETRIC_EQ = 1 shl 2
    const val BASS = 1 shl 3
    const val COMPRESSOR = 1 shl 4
    const val MULTIBAND = 1 shl 5
    const val DYNAMIC_EQ = 1 shl 6
    const val STEREO_WIDTH = 1 shl 7
    const val MONO_BASS = 1 shl 8
    const val SEAT_FOCUS = 1 shl 9
    const val ADVANCED_MS = 1 shl 10
    const val CABIN_CORRECTION = 1 shl 11
    const val HIGH_EXCITER = 1 shl 12
    const val VIRTUAL_BASS = 1 shl 13
    const val CONVOLVER = 1 shl 14
    const val SAFETY_LIMITER = 1 shl 15
    const val AUTO_HEADROOM = 1 shl 16
}

internal class DspDiagnostics {
    @Volatile
    var processedFrames = 0L
        private set
    @Volatile
    var processedBlocks = 0L
        private set
    @Volatile
    var incompleteFrameBytes = 0L
        private set
    @Volatile
    var nonFiniteInputSamples = 0L
    @Volatile
    var nonFiniteOutputSamples = 0L
    @Volatile
    var processorFailures = 0L
        private set

    fun recordProcessedBlock(frames: Int) {
        processedFrames += frames
        processedBlocks++
    }

    fun recordIncompleteFrameBytes(bytes: Int) {
        incompleteFrameBytes += bytes
    }

    fun recordProcessorFailure() {
        processorFailures++
    }

    fun snapshot(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot(
        processedFrames = processedFrames,
        processedBlocks = processedBlocks,
        incompleteFrameBytes = incompleteFrameBytes,
        nonFiniteInputSamples = nonFiniteInputSamples,
        nonFiniteOutputSamples = nonFiniteOutputSamples,
        processorFailures = processorFailures,
    )
}
