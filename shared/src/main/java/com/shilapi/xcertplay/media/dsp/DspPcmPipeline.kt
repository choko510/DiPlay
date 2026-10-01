package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DspPcmPipeline(
    val format: DspAudioFormat,
    private val processor: DspProcessor = IdentityDspProcessor(format),
    private val processingChunkFrames: Int = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
    seed: Long = System.nanoTime(),
) : Closeable {
    private val diagnostics = DspDiagnostics()
    private val dither = DspTpdfDither(seed)
    private val floatBufferBytes = DspBufferSizing.floatByteCount(processingChunkFrames, format.channels)
    private val inputFloat = ByteBuffer.allocateDirect(floatBufferBytes).order(ByteOrder.nativeOrder())
    private val outputFloat = ByteBuffer.allocateDirect(floatBufferBytes).order(ByteOrder.nativeOrder())
    private var processedPcm16 = ByteArray(DspBufferSizing.pcm16ByteCount(processingChunkFrames, format.channels))
    private var processedLength = 0

    init {
        require(processingChunkFrames > 0)
        require(processor.format == format)
    }

    val output: ByteArray
        get() = processedPcm16

    val outputLength: Int
        get() = processedLength

    fun process(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
    ): Int {
        processedLength = 0
        if (offset < 0 || length < 0 || offset > source.size - length) return fail()
        val frameBytes = encoding.bytesPerSample * format.channels
        val completeLength = length - length % frameBytes
        val trailingBytes = length - completeLength
        if (trailingBytes > 0) diagnostics.recordIncompleteFrameBytes(trailingBytes)
        val frameCount = completeLength / frameBytes
        if (frameCount == 0) return 0

        val outputBytesLong = frameCount.toLong() * format.channels * Short.SIZE_BYTES
        if (outputBytesLong > Int.MAX_VALUE) return fail()
        val outputBytes = outputBytesLong.toInt()
        if (processedPcm16.size < outputBytes) {
            processedPcm16 = ByteArray(DspBufferSizing.growCapacity(processedPcm16.size, outputBytes))
        }

        var framesProcessed = 0
        var outputOffset = 0
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
            if (!DspPcmConverter.floatToPcm16(
                    source = outputFloat,
                    frames = chunkFrames,
                    format = format,
                    destination = processedPcm16,
                    offset = outputOffset,
                    dither = dither,
                    diagnostics = diagnostics,
                )
            ) {
                return fail()
            }

            framesProcessed += chunkFrames
            outputOffset += DspBufferSizing.pcm16ByteCount(chunkFrames, format.channels)
            diagnostics.recordProcessedBlock(chunkFrames)
        }
        processedLength = outputOffset
        return processedLength
    }

    fun reset() {
        processor.reset()
        dither.reset()
        processedLength = 0
    }

    fun diagnostics(): DspDiagnosticsSnapshot = diagnostics.snapshot()

    override fun close() {
        processor.close()
    }

    private fun fail(): Int {
        diagnostics.recordProcessorFailure()
        processedLength = 0
        return -1
    }
}
