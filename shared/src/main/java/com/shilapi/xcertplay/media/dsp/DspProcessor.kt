package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer

internal enum class DspProcessStatus {
    SUCCESS,
    ERROR,
}

internal enum class DspBypassReason {
    INVALID_BUFFER,
    NATIVE_FAILURE,
}

internal class DspProcessResult {
    var status: DspProcessStatus = DspProcessStatus.ERROR
        private set
    var inputFrames: Int = 0
        private set
    var outputFrames: Int = 0
        private set
    var algorithmicLatencyFrames: Int = 0
        private set
    var nativeErrorCode: Int = 0
        private set
    var bypassReason: DspBypassReason? = null
        private set

    fun success(frames: Int, latencyFrames: Int): DspProcessResult = apply {
        status = DspProcessStatus.SUCCESS
        inputFrames = frames
        outputFrames = frames
        algorithmicLatencyFrames = latencyFrames
        nativeErrorCode = 0
        bypassReason = null
    }

    fun failure(
        frames: Int,
        latencyFrames: Int,
        reason: DspBypassReason,
        errorCode: Int = 0,
    ): DspProcessResult = apply {
        status = DspProcessStatus.ERROR
        inputFrames = frames.coerceAtLeast(0)
        outputFrames = 0
        algorithmicLatencyFrames = latencyFrames
        nativeErrorCode = errorCode
        bypassReason = reason
    }
}

internal interface DspProcessor : Closeable {
    val format: DspAudioFormat
    val latencyFrames: Int

    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int,
    ): DspProcessResult

    fun reset()

    fun diagnostics(): DspDiagnosticsSnapshot
}
