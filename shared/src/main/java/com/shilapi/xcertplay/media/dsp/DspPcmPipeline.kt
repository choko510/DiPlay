package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DspPcmPipeline(
    val format: DspAudioFormat,
    private val processor: DspProcessor = IdentityDspProcessor(format),
    private val processingChunkFrames: Int = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
    seed: Long = System.nanoTime(),
    private val forceInitialLatencySilence: Boolean = true,
) : Closeable {
    private val diagnostics = DspDiagnostics()
    private val pipelineProcessTiming = DspFixedHistogram()
    private val dither = DspTpdfDither(seed)
    private val floatBufferBytes = DspBufferSizing.floatByteCount(processingChunkFrames, format.channels)
    private val inputFloat = ByteBuffer.allocateDirect(floatBufferBytes).order(ByteOrder.nativeOrder())
    private val outputFloat = ByteBuffer.allocateDirect(floatBufferBytes).order(ByteOrder.nativeOrder())
    private val processedFloat = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
    private val processedPcm16 = ByteArray(
        DspBufferSizing.pcm16ByteCount(DspBufferSizing.MAX_DECODER_PCM_FRAMES, format.channels),
    )
    private var processedLength = 0
    private var processedFrames = 0
    private var outputDelayFrames = 0
    private var outputDelay = FloatArray(0)
    private var outputDelayPosition = 0
    private var startupLatencyRemainingFrames =
        if (forceInitialLatencySilence) processor.latencyFrames.coerceAtLeast(0) else 0

    init {
        require(processingChunkFrames > 0)
        require(processor.format == format)
    }

    val output: ByteArray
        get() = processedPcm16

    val outputLength: Int
        get() = processedLength

    val outputFloats: FloatArray
        get() = processedFloat

    val outputFrameCount: Int
        get() = processedFrames

    internal fun encodeFloatPcm16(
        source: FloatArray,
        frames: Int,
        destination: ByteArray,
        offset: Int,
    ): Boolean = DspPcmConverter.floatArrayToPcm16(
        source = source,
        frames = frames,
        format = format,
        destination = destination,
        offset = offset,
        dither = dither,
        diagnostics = diagnostics,
    )

    val algorithmicLatencyFrames: Int
        get() = processor.latencyFrames + outputDelayFrames

    internal fun preserveLatencyWithDelay(frames: Int) {
        require(frames >= 0)
        require(processedFrames == 0) { "Output delay must be prepared before processing" }
        outputDelayFrames = frames
        outputDelay = FloatArray(DspBufferSizing.floatByteCount(frames, format.channels) / Float.SIZE_BYTES)
        outputDelayPosition = 0
    }

    fun process(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        encodeOutput: Boolean = true,
    ): Int {
        val startNs = System.nanoTime()
        return try {
            processInternal(source, offset, length, encoding, encodeOutput)
        } finally {
            pipelineProcessTiming.record(System.nanoTime() - startNs)
        }
    }

    private fun processInternal(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
        encodeOutput: Boolean,
    ): Int {
        processedLength = 0
        processedFrames = 0
        if (offset < 0 || length < 0 || offset > source.size - length) return fail()
        val frameBytes = encoding.bytesPerSample * format.channels
        val completeLength = length - length % frameBytes
        val trailingBytes = length - completeLength
        if (trailingBytes > 0) diagnostics.recordIncompleteFrameBytes(trailingBytes)
        val frameCount = completeLength / frameBytes
        if (frameCount == 0) return 0
        if (frameCount > DspBufferSizing.MAX_DECODER_PCM_FRAMES) return fail()

        val outputBytesLong = frameCount.toLong() * format.channels * Short.SIZE_BYTES
        if (outputBytesLong > Int.MAX_VALUE) return fail()
        val outputBytes = outputBytesLong.toInt()

        var framesProcessed = 0
        val blockInitialSilenceFrames = minOf(startupLatencyRemainingFrames, frameCount)
        var blockSilenceRemaining = startupLatencyRemainingFrames
        while (framesProcessed < frameCount) {
            val chunkFrames = minOf(processingChunkFrames, frameCount - framesProcessed)
            val chunkInputBytes = chunkFrames * frameBytes
            inputFloat.clear()
            val decodedFrames = DspPcmConverter.pcmToFloat(
                source = source,
                offset = offset + framesProcessed * frameBytes,
                length = chunkInputBytes,
                encoding = encoding,
                format = format,
                destination = inputFloat,
                diagnostics = diagnostics,
            )
            if (decodedFrames != chunkFrames) return fail()
            inputFloat.flip()

            outputFloat.clear()
            val result = processor.process(inputFloat, outputFloat, chunkFrames)
            if (result.status != DspProcessStatus.SUCCESS ||
                result.inputFrames != chunkFrames || result.outputFrames != chunkFrames
            ) {
                return fail()
            }
            outputFloat.flip()
            val silentFrames = minOf(blockSilenceRemaining, chunkFrames)
            val destinationFloatOffset = framesProcessed * format.channels
            for (frame in 0 until chunkFrames) {
                for (channel in 0 until format.channels) {
                    val sampleIndex = frame * format.channels + channel
                    val sample = if (frame < silentFrames) 0f else outputFloat.getFloat(sampleIndex * Float.SIZE_BYTES)
                    if (outputDelayFrames > 0) {
                        val delayedIndex = outputDelayPosition * format.channels + channel
                        processedFloat[destinationFloatOffset + sampleIndex] = outputDelay[delayedIndex]
                        outputDelay[delayedIndex] = sample
                    } else {
                        processedFloat[destinationFloatOffset + sampleIndex] = sample
                    }
                }
                if (outputDelayFrames > 0) {
                    outputDelayPosition++
                    if (outputDelayPosition == outputDelayFrames) outputDelayPosition = 0
                }
            }
            blockSilenceRemaining -= silentFrames

            framesProcessed += chunkFrames
            diagnostics.recordProcessedBlock(chunkFrames)
        }
        startupLatencyRemainingFrames = blockSilenceRemaining
        if (encodeOutput && !DspPcmConverter.floatArrayToPcm16(
                source = processedFloat,
                frames = frameCount,
                format = format,
                destination = processedPcm16,
                offset = 0,
                dither = dither,
                diagnostics = diagnostics,
                initialSilenceFrames = blockInitialSilenceFrames,
            )
        ) {
            return fail()
        }
        processedFrames = frameCount
        processedLength = outputBytes
        return processedLength
    }

    fun reset() {
        processor.reset()
        dither.reset()
        processedLength = 0
        processedFrames = 0
        outputDelay.fill(0f)
        outputDelayPosition = 0
        startupLatencyRemainingFrames = if (forceInitialLatencySilence) processor.latencyFrames.coerceAtLeast(0) else 0
    }

    fun diagnostics(): DspDiagnosticsSnapshot {
        val pipelineSnapshot = diagnostics.snapshot()
        val processorSnapshot = processor.diagnostics()
        return pipelineSnapshot.copy(
            nonFiniteInputSamples =
                pipelineSnapshot.nonFiniteInputSamples + processorSnapshot.nonFiniteInputSamples,
            nonFiniteOutputSamples =
                pipelineSnapshot.nonFiniteOutputSamples + processorSnapshot.nonFiniteOutputSamples,
            nativeErrorCount = processorSnapshot.nativeErrorCount,
            inputPeakL = processorSnapshot.inputPeakL,
            inputPeakR = processorSnapshot.inputPeakR,
            inputRmsL = processorSnapshot.inputRmsL,
            inputRmsR = processorSnapshot.inputRmsR,
            outputPeakL = processorSnapshot.outputPeakL,
            outputPeakR = processorSnapshot.outputPeakR,
            outputRmsL = processorSnapshot.outputRmsL,
            outputRmsR = processorSnapshot.outputRmsR,
            nativeProcessUs = processorSnapshot.nativeProcessUs,
            pipelineProcessUs = pipelineProcessTiming.snapshot(),
        )
    }

    internal fun resetTimingMetrics() {
        pipelineProcessTiming.reset()
        (processor as? NativeDspProcessor)?.resetTimingMetrics()
    }

    override fun close() {
        processor.close()
    }

    private fun fail(): Int {
        diagnostics.recordProcessorFailure()
        processedLength = 0
        processedFrames = 0
        return -1
    }
}
