package com.shilapi.xcertplay.media.dsp

internal data class DspDiagnosticsSnapshot(
    val processedFrames: Long = 0,
    val processedBlocks: Long = 0,
    val incompleteFrameBytes: Long = 0,
    val nonFiniteInputSamples: Long = 0,
    val nonFiniteOutputSamples: Long = 0,
    val processorFailures: Long = 0,
    val nativeErrorCount: Long = 0,
    val inputPeakL: Double = 0.0,
    val inputPeakR: Double = 0.0,
    val inputRmsL: Double = 0.0,
    val inputRmsR: Double = 0.0,
    val outputPeakL: Double = 0.0,
    val outputPeakR: Double = 0.0,
    val outputRmsL: Double = 0.0,
    val outputRmsR: Double = 0.0,
) {
    companion object {
        val EMPTY = DspDiagnosticsSnapshot()
    }
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
