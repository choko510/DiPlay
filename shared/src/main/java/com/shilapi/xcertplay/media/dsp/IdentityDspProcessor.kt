package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer

internal class IdentityDspProcessor(
    override val format: DspAudioFormat,
) : DspProcessor {
    override val latencyFrames: Int = 0
    private val result = DspProcessResult()

    override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
        val expectedByteCount = frames.toLong() * format.channels * Float.SIZE_BYTES
        if (
            input === output ||
            frames <= 0 ||
            expectedByteCount > Int.MAX_VALUE ||
            !input.isDirect ||
            !output.isDirect ||
            input.remaining().toLong() != expectedByteCount ||
            output.remaining().toLong() < expectedByteCount
        ) {
            return result.failure(frames, latencyFrames, DspBypassReason.INVALID_BUFFER)
        }
        val inputPosition = input.position()
        val sampleCount = (expectedByteCount / Float.SIZE_BYTES).toInt()
        repeat(sampleCount) { sample ->
            output.putFloat(input.getFloat(inputPosition + sample * Float.SIZE_BYTES))
        }
        input.position(inputPosition + expectedByteCount.toInt())
        return result.success(frames, latencyFrames)
    }

    override fun reset() = Unit

    override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

    override fun close() = Unit
}
