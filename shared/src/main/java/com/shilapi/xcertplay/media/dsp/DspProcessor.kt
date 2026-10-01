package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer

internal enum class DspProcessStatus {
    SUCCESS,
    ERROR,
}

internal enum class DspBypassReason {
    INVALID_BUFFER,
}

internal data class DspProcessResult(
    val status: DspProcessStatus,
    val inputFrames: Int,
    val outputFrames: Int,
    val algorithmicLatencyFrames: Int,
    val nativeErrorCode: Int = 0,
    val bypassReason: DspBypassReason? = null,
)

internal class DspDiagnosticsSnapshot

internal interface DspProcessor : Closeable {
    val latencyFrames: Int

    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int,
    ): DspProcessResult

    fun reset()

    fun diagnostics(): DspDiagnosticsSnapshot
}
