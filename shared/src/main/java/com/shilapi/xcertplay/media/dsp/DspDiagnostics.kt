package com.shilapi.xcertplay.media.dsp

internal data class DspDiagnosticsSnapshot(
    val processedFrames: Long = 0,
    val processedBlocks: Long = 0,
    val incompleteFrameBytes: Long = 0,
    val nonFiniteInputSamples: Long = 0,
    val nonFiniteOutputSamples: Long = 0,
    val processorFailures: Long = 0,
) {
    companion object {
        val EMPTY = DspDiagnosticsSnapshot()
    }
}

internal class DspDiagnostics {
    var processedFrames = 0L
        private set
    var processedBlocks = 0L
        private set
    var incompleteFrameBytes = 0L
        private set
    var nonFiniteInputSamples = 0L
    var nonFiniteOutputSamples = 0L
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
