package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer

internal interface NativeDspBindings {
    fun create(
        sampleRate: Int,
        channels: Int,
        maxFrames: Int,
        gainDb: Double,
        eqCoefficients: DoubleArray,
        dynamics: DoubleArray,
        multiband: DoubleArray,
        dynamicEq: DoubleArray,
        bassCoefficients: DoubleArray,
        monoBassCoefficients: DoubleArray,
        spatial: DoubleArray,
        convolverConfig: DoubleArray,
        convolverSamples: FloatArray,
    ): Long
    fun process(
        handle: Long,
        input: ByteBuffer,
        inputPosition: Int,
        inputRemaining: Int,
        output: ByteBuffer,
        outputPosition: Int,
        outputRemaining: Int,
        frames: Int,
        channels: Int,
    ): Int
    fun reset(handle: Long): Int
    fun latencyFrames(handle: Long): Int
    fun diagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int
    fun destroy(handle: Long)
}

internal object NativeDspJni : NativeDspBindings {
    override fun create(
        sampleRate: Int,
        channels: Int,
        maxFrames: Int,
        gainDb: Double,
        eqCoefficients: DoubleArray,
        dynamics: DoubleArray,
        multiband: DoubleArray,
        dynamicEq: DoubleArray,
        bassCoefficients: DoubleArray,
        monoBassCoefficients: DoubleArray,
        spatial: DoubleArray,
        convolverConfig: DoubleArray,
        convolverSamples: FloatArray,
    ): Long = nativeCreate(
        sampleRate,
        channels,
        maxFrames,
        gainDb,
        eqCoefficients,
        dynamics,
        multiband,
        dynamicEq,
        bassCoefficients,
        monoBassCoefficients,
        spatial,
        convolverConfig,
        convolverSamples,
    )

    override fun process(
        handle: Long,
        input: ByteBuffer,
        inputPosition: Int,
        inputRemaining: Int,
        output: ByteBuffer,
        outputPosition: Int,
        outputRemaining: Int,
        frames: Int,
        channels: Int,
    ): Int = nativeProcess(
        handle,
        input,
        inputPosition,
        inputRemaining,
        output,
        outputPosition,
        outputRemaining,
        frames,
        channels,
    )

    override fun reset(handle: Long): Int = nativeReset(handle)
    override fun latencyFrames(handle: Long): Int = nativeGetLatencyFrames(handle)
    override fun diagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int =
        nativeGetDiagnostics(handle, values, counters)
    override fun destroy(handle: Long) = nativeDestroy(handle)

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        maxFrames: Int,
        gainDb: Double,
        eqCoefficients: DoubleArray,
        dynamics: DoubleArray,
        multiband: DoubleArray,
        dynamicEq: DoubleArray,
        bassCoefficients: DoubleArray,
        monoBassCoefficients: DoubleArray,
        spatial: DoubleArray,
        convolverConfig: DoubleArray,
        convolverSamples: FloatArray,
    ): Long
    private external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        inputPosition: Int,
        inputRemaining: Int,
        output: ByteBuffer,
        outputPosition: Int,
        outputRemaining: Int,
        frames: Int,
        channels: Int,
    ): Int
    private external fun nativeReset(handle: Long): Int
    private external fun nativeGetLatencyFrames(handle: Long): Int
    private external fun nativeGetDiagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int
    private external fun nativeDestroy(handle: Long)
}

internal object NativeDspLibrary {
    @Volatile private var loaded = false
    private val lock = Any()

    fun ensureLoaded(loader: () -> Unit = { System.loadLibrary(LIBRARY_NAME) }): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            return try {
                loader()
                loaded = true
                true
            } catch (_: SecurityException) {
                false
            } catch (_: LinkageError) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private const val LIBRARY_NAME = "xcertplay_dsp"
}

internal class NativeDspProcessor internal constructor(
    override val format: DspAudioFormat,
    handle: Long,
    private val bindings: NativeDspBindings,
    override val latencyFrames: Int,
) : DspProcessor {
    @Volatile private var nativeHandle = handle
    private val result = DspProcessResult()
    private val meterValues = DoubleArray(DIAGNOSTIC_VALUE_COUNT)
    private val counters = LongArray(DIAGNOSTIC_COUNTER_COUNT)
    @Volatile private var localNativeErrors = 0L

    override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
        val expectedByteCount = frames.toLong() * format.channels * Float.SIZE_BYTES
        if (
            nativeHandle <= 0L || input === output || frames <= 0 ||
            expectedByteCount > Int.MAX_VALUE || !input.isDirect || !output.isDirect ||
            input.remaining().toLong() != expectedByteCount || output.remaining().toLong() < expectedByteCount
        ) {
            return result.failure(frames, latencyFrames, DspBypassReason.INVALID_BUFFER)
        }

        val status = try {
            bindings.process(
                handle = nativeHandle,
                input = input,
                inputPosition = input.position(),
                inputRemaining = input.remaining(),
                output = output,
                outputPosition = output.position(),
                outputRemaining = output.remaining(),
                frames = frames,
                channels = format.channels,
            )
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
        if (status != NATIVE_OK) {
            localNativeErrors++
            return result.failure(frames, latencyFrames, DspBypassReason.NATIVE_FAILURE, status)
        }

        val byteCount = expectedByteCount.toInt()
        input.position(input.position() + byteCount)
        output.position(output.position() + byteCount)
        return result.success(frames, latencyFrames)
    }

    override fun reset() {
        if (nativeHandle <= 0L) return
        val status = try {
            bindings.reset(nativeHandle)
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
        if (status != NATIVE_OK) localNativeErrors++
    }

    @Synchronized
    override fun diagnostics(): DspDiagnosticsSnapshot {
        val handle = nativeHandle
        if (handle <= 0L) return DspDiagnosticsSnapshot(nativeErrorCount = localNativeErrors)
        val status = try {
            bindings.diagnostics(handle, meterValues, counters)
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
        if (status != NATIVE_OK) {
            localNativeErrors++
            return DspDiagnosticsSnapshot(nativeErrorCount = localNativeErrors)
        }
        return DspDiagnosticsSnapshot(
            processedFrames = counters[0],
            processedBlocks = counters[1],
            nonFiniteInputSamples = counters[2],
            nonFiniteOutputSamples = counters[3],
            nativeErrorCount = counters[4] + localNativeErrors,
            inputPeakL = meterValues[0],
            inputPeakR = meterValues[1],
            inputRmsL = meterValues[2],
            inputRmsR = meterValues[3],
            outputPeakL = meterValues[4],
            outputPeakR = meterValues[5],
            outputRmsL = meterValues[6],
            outputRmsR = meterValues[7],
        )
    }

    override fun close() {
        val handle = nativeHandle
        if (handle <= 0L) return
        nativeHandle = 0L
        runCatching { bindings.destroy(handle) }
    }

    companion object {
        private const val NATIVE_OK = 0
        private const val DIAGNOSTIC_VALUE_COUNT = 8
        private const val DIAGNOSTIC_COUNTER_COUNT = 5

        fun createOrNull(
            format: DspAudioFormat,
            runtimeConfig: DspRuntimeConfig,
            loadLibrary: () -> Boolean = { NativeDspLibrary.ensureLoaded() },
            bindings: NativeDspBindings = NativeDspJni,
        ): NativeDspProcessor? {
            if (!loadLibrary()) return null
            val prepared = try {
                runtimeConfig.prepare(format)
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }
            var handle = 0L
            return try {
                handle = bindings.create(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    maxFrames = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
                    gainDb = prepared.appliedPreampDb,
                    eqCoefficients = prepared.eqCoefficients,
                    dynamics = prepared.dynamics,
                    multiband = prepared.multiband,
                    dynamicEq = prepared.dynamicEq,
                    bassCoefficients = prepared.bassCoefficients,
                    monoBassCoefficients = prepared.monoBassCoefficients,
                    spatial = prepared.spatial,
                    convolverConfig = prepared.convolverConfig,
                    convolverSamples = prepared.convolverSamples,
                )
                if (handle <= 0L) return null
                val latencyFrames = bindings.latencyFrames(handle)
                if (latencyFrames < 0) {
                    runCatching { bindings.destroy(handle) }
                    return null
                }
                NativeDspProcessor(format, handle, bindings, latencyFrames)
            } catch (_: SecurityException) {
                if (handle > 0L) runCatching { bindings.destroy(handle) }
                null
            } catch (_: LinkageError) {
                if (handle > 0L) runCatching { bindings.destroy(handle) }
                null
            } catch (_: Exception) {
                if (handle > 0L) runCatching { bindings.destroy(handle) }
                null
            }
        }
    }
}
