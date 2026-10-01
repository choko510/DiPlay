package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPcmPipelineTest {
    @Test
    fun audioFormatAcceptsSupportedRatesAndOneOrTwoChannelsOnly() {
        assertEquals(DspAudioFormat(8_000, 1), DspAudioFormat(8_000, 1))
        assertEquals(DspAudioFormat(192_000, 2), DspAudioFormat(192_000, 2))
        assertThrows(IllegalArgumentException::class.java) { DspAudioFormat(7_999, 1) }
        assertThrows(IllegalArgumentException::class.java) { DspAudioFormat(192_001, 2) }
        assertThrows(IllegalArgumentException::class.java) { DspAudioFormat(48_000, 3) }
    }

    @Test
    fun chunksLargeDecoderBuffersAndReusesItsOutputArray() {
        val format = DspAudioFormat(48_000, 2)
        val pipeline = DspPcmPipeline(format, seed = 3)
        val input = pcm16Stereo(1_537)

        val outputLength = pipeline.process(input, 0, input.size, DspPcmEncoding.PCM16)
        val output = pipeline.output
        val diagnostics = pipeline.diagnostics()

        assertEquals(1_537 * 2 * Short.SIZE_BYTES, outputLength)
        assertEquals(4L, diagnostics.processedBlocks)
        assertEquals(1_537L, diagnostics.processedFrames)
        assertSame(output, pipeline.output)
        pipeline.close()
    }

    @Test
    fun splitCallsMatchOneLargeCallAndKeepDitherStateContinuous() {
        val input = pcm16Stereo(1_100)
        val format = DspAudioFormat(44_100, 2)
        val whole = DspPcmPipeline(format, seed = 88)
        val wholeLength = whole.process(input, 0, input.size, DspPcmEncoding.PCM16)
        val expected = whole.output.copyOf(wholeLength)

        val split = DspPcmPipeline(format, seed = 88)
        val actual = ByteArray(expected.size)
        var sourceOffset = 0
        var destinationOffset = 0
        for (frames in intArrayOf(300, 600, 200)) {
            val byteCount = frames * format.channels * Short.SIZE_BYTES
            val outputLength = split.process(input, sourceOffset, byteCount, DspPcmEncoding.PCM16)
            split.output.copyInto(actual, destinationOffset, 0, outputLength)
            sourceOffset += byteCount
            destinationOffset += outputLength
        }

        assertEquals(input.size, sourceOffset)
        assertEquals(expected.size, destinationOffset)
        assertArrayEquals(expected, actual)
        whole.close()
        split.close()
    }

    @Test
    fun incompleteDecoderFrameIsDroppedAndRecordedWithoutFailingThePipeline() {
        val format = DspAudioFormat(48_000, 2)
        val pipeline = DspPcmPipeline(format, seed = 1)
        val input = byteArrayOf(0x00, 0x40, 0x00, 0xc0.toByte(), 0x7f)

        assertEquals(4, pipeline.process(input, 0, input.size, DspPcmEncoding.PCM16))
        assertEquals(1L, pipeline.diagnostics().incompleteFrameBytes)
        assertEquals(1L, pipeline.diagnostics().processedFrames)
        pipeline.close()
    }

    @Test
    fun processFailureDoesNotPublishPartialOutput() {
        val format = DspAudioFormat(48_000, 1)
        val processor = FailingDspProcessor(format)
        val pipeline = DspPcmPipeline(format, processor, seed = 1)

        assertTrue(pipeline.process(byteArrayOf(0x00, 0x40), 0, 2, DspPcmEncoding.PCM16) < 0)
        assertEquals(0, pipeline.outputLength)
        assertEquals(1L, pipeline.diagnostics().processorFailures)
        pipeline.close()
    }

    @Test
    fun startupLatencyFramesRemainExactPcmSilenceAndRestartAfterReset() {
        val format = DspAudioFormat(48_000, 1)
        val pipeline = DspPcmPipeline(format, StartupLatencyDspProcessor(format, latencyFrames = 2), seed = 9)
        val input = byteArrayOf(0xff.toByte(), 0x7f, 0xff.toByte(), 0x7f, 0xff.toByte(), 0x7f)

        val firstLength = pipeline.process(input, 0, input.size, DspPcmEncoding.PCM16)
        val firstOutput = pipeline.output.copyOf(firstLength)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), firstOutput.copyOfRange(0, 4))
        assertEquals(2, pipeline.algorithmicLatencyFrames)

        pipeline.reset()
        val secondLength = pipeline.process(input, 0, input.size, DspPcmEncoding.PCM16)
        val secondOutput = pipeline.output.copyOf(secondLength)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), secondOutput.copyOfRange(0, 4))
        pipeline.close()
    }

    private fun pcm16Stereo(frames: Int): ByteArray {
        val output = ByteArray(frames * 2 * Short.SIZE_BYTES)
        var offset = 0
        for (frame in 0 until frames) {
            val left = ((frame * 97) % 65_536 - 32_768).toShort()
            val right = ((frame * 251 + 17) % 65_536 - 32_768).toShort()
            output[offset++] = left.toByte()
            output[offset++] = (left.toInt() shr 8).toByte()
            output[offset++] = right.toByte()
            output[offset++] = (right.toInt() shr 8).toByte()
        }
        return output
    }

    private class FailingDspProcessor(override val format: DspAudioFormat) : DspProcessor {
        private val result = DspProcessResult()
        override val latencyFrames: Int = 0

        override fun process(
            input: java.nio.ByteBuffer,
            output: java.nio.ByteBuffer,
            frames: Int,
        ): DspProcessResult = result.failure(frames, latencyFrames, DspBypassReason.INVALID_BUFFER)

        override fun reset() = Unit
        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY
        override fun close() = Unit
    }

    private class StartupLatencyDspProcessor(
        override val format: DspAudioFormat,
        override val latencyFrames: Int,
    ) : DspProcessor {
        private val result = DspProcessResult()
        private var processedFrames = 0

        override fun process(
            input: java.nio.ByteBuffer,
            output: java.nio.ByteBuffer,
            frames: Int,
        ): DspProcessResult {
            repeat(frames * format.channels) { index ->
                val frame = processedFrames + index / format.channels
                output.putFloat(if (frame < latencyFrames) 0.0f else input.getFloat(input.position() + index * Float.SIZE_BYTES))
            }
            processedFrames += frames
            input.position(input.position() + frames * format.channels * Float.SIZE_BYTES)
            return result.success(frames, latencyFrames)
        }

        override fun reset() {
            processedFrames = 0
        }

        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

        override fun close() = Unit
    }
}
