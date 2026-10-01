package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer

internal class IdentityDspProcessor : DspProcessor {
    override val latencyFrames: Int = 0

    override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
        val byteCount = input.remaining()
        if (
            input === output ||
            frames < 0 ||
            (frames == 0) != (byteCount == 0) ||
            byteCount % Float.SIZE_BYTES != 0 ||
            output.remaining() < byteCount
        ) {
            return DspProcessResult(
                status = DspProcessStatus.ERROR,
                inputFrames = frames.coerceAtLeast(0),
                outputFrames = 0,
                algorithmicLatencyFrames = latencyFrames,
                bypassReason = DspBypassReason.INVALID_BUFFER,
            )
        }
        output.put(input)
        return DspProcessResult(
            status = DspProcessStatus.SUCCESS,
            inputFrames = frames,
            outputFrames = frames,
            algorithmicLatencyFrames = latencyFrames,
        )
    }

    override fun reset() = Unit

    override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot()

    override fun close() = Unit
}
