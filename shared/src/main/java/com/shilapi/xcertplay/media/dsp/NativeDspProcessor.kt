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
        harmonicValues: DoubleArray,
    ): Long
    fun createWithStudio(
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
        harmonicValues: DoubleArray,
        macroValues: DoubleArray,
        smartLoudnessValues: DoubleArray,
        dynamicBassValues: DoubleArray,
    ): Long = create(
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
        harmonicValues,
    )
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
    fun applyHotConfig(handle: Long, update: DspPreparedHotUpdate): Int = -1
    fun prepareLocalPeqUpdate(
        handle: Long,
        coefficients: DoubleArray,
        preparedValues: DoubleArray,
        historySequences: LongArray,
    ): Boolean = false
    fun enqueueLocalPeqUpdate(handle: Long, update: DspPreparedLocalPeqWarmUpdate): Int = -1
    fun cancelLocalPeqUpdate(handle: Long, generationToken: Int): Int = -1
    fun bindHotUpdateContext(
        handle: Long,
        format: DspAudioFormat,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
    ): Boolean = false
    fun hotAckToken(handle: Long): Int = 0
    fun localPeqAckToken(handle: Long): Int = 0
    fun localPeqRejectedToken(handle: Long): Int = 0
    fun latencyFrames(handle: Long): Int
    fun interchannelRelativeDelayFrames(handle: Long): Double = 0.0
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
        harmonicValues: DoubleArray,
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
        harmonicValues,
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.5, 3.0, 120.0, -55.0, -35.0, -10.0, 40.0, 250.0),
    )

    override fun createWithStudio(
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
        harmonicValues: DoubleArray,
        macroValues: DoubleArray,
        smartLoudnessValues: DoubleArray,
        dynamicBassValues: DoubleArray,
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
        harmonicValues,
        macroValues,
        smartLoudnessValues,
        dynamicBassValues,
    )

    override fun applyHotConfig(handle: Long, update: DspPreparedHotUpdate): Int = nativeApplyHotConfig(
        handle,
        update.generation,
        update.editorOwnerEpoch,
        update.rendererOwnerToken,
        update.pcmFormatEpoch,
        update.hotContextToken,
        update.mediaRole,
        update.appliedPreampDb,
        update.macroValues,
        update.smartLoudnessValues,
        update.dynamicBassValues,
    )

    override fun prepareLocalPeqUpdate(
        handle: Long,
        coefficients: DoubleArray,
        preparedValues: DoubleArray,
        historySequences: LongArray,
    ): Boolean = nativePrepareLocalPeqUpdate(handle, coefficients, preparedValues, historySequences)

    override fun enqueueLocalPeqUpdate(handle: Long, update: DspPreparedLocalPeqWarmUpdate): Int =
        nativeEnqueueLocalPeqUpdate(
            handle,
            update.generation,
            update.editorOwnerEpoch,
            update.rendererOwnerToken,
            update.pcmFormatEpoch,
            update.hotContextToken,
            update.mediaRole,
            update.historyEpochSequence,
            update.historyEndSequence,
            update.bandCount,
            update.preparedValues,
        )

    override fun cancelLocalPeqUpdate(handle: Long, generationToken: Int): Int =
        nativeCancelLocalPeqUpdate(handle, generationToken)

    override fun bindHotUpdateContext(
        handle: Long,
        format: DspAudioFormat,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
    ): Boolean = nativeBindHotUpdateContext(
        handle,
        format.sampleRate,
        format.channels,
        editorOwnerEpoch,
        rendererOwnerToken,
        pcmFormatEpoch,
        hotContextToken,
        mediaRole,
    ) == 0

    override fun hotAckToken(handle: Long): Int = nativeGetHotAckToken(handle)

    override fun localPeqAckToken(handle: Long): Int = nativeGetLocalPeqAckToken(handle)

    override fun localPeqRejectedToken(handle: Long): Int = nativeGetLocalPeqRejectedToken(handle)

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
    override fun interchannelRelativeDelayFrames(handle: Long): Double = nativeGetInterchannelRelativeDelayFrames(handle)
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
        harmonicValues: DoubleArray,
        macroValues: DoubleArray,
        smartLoudnessValues: DoubleArray,
        dynamicBassValues: DoubleArray,
    ): Long
    private external fun nativeApplyHotConfig(
        handle: Long,
        generation: Long,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
        gainDb: Double,
        macroValues: DoubleArray,
        smartLoudnessValues: DoubleArray,
        dynamicBassValues: DoubleArray,
    ): Int
    private external fun nativePrepareLocalPeqUpdate(
        handle: Long,
        eqCoefficients: DoubleArray,
        preparedValues: DoubleArray,
        historySequences: LongArray,
    ): Boolean
    private external fun nativeEnqueueLocalPeqUpdate(
        handle: Long,
        generation: Long,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
        historyEpochSequence: Long,
        historyEndSequence: Long,
        bandCount: Int,
        preparedValues: DoubleArray,
    ): Int
    private external fun nativeBindHotUpdateContext(
        handle: Long,
        sampleRate: Int,
        channels: Int,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
    ): Int
    private external fun nativeGetHotAckToken(handle: Long): Int
    private external fun nativeGetLocalPeqAckToken(handle: Long): Int
    private external fun nativeCancelLocalPeqUpdate(handle: Long, generationToken: Int): Int
    private external fun nativeGetLocalPeqRejectedToken(handle: Long): Int
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
    private external fun nativeGetInterchannelRelativeDelayFrames(handle: Long): Double
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
    override val interchannelRelativeDelayFrames: Double = 0.0,
) : DspProcessor {
    @Volatile private var nativeHandle = handle
    private val result = DspProcessResult()
    private val nativeProcessTiming = DspFixedHistogram()
    private val meterValues = DoubleArray(DIAGNOSTIC_VALUE_COUNT)
    private val counters = LongArray(DIAGNOSTIC_COUNTER_COUNT)
    @Volatile private var localNativeErrors = 0L
    @Volatile private var hotEditorOwnerEpoch = 0L
    @Volatile private var hotRendererOwnerToken = 0L
    @Volatile private var hotPcmFormatEpoch = 0L
    @Volatile private var hotContextToken = 0

    override fun bindHotUpdateContext(
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
    ): Boolean {
        val handle = nativeHandle
        if (handle <= 0L || editorOwnerEpoch <= 0L || rendererOwnerToken <= 0L || pcmFormatEpoch <= 0L ||
            hotContextToken <= 0 || !mediaRole
        ) {
            return false
        }
        val bound = try {
            bindings.bindHotUpdateContext(
                handle, format, editorOwnerEpoch, rendererOwnerToken, pcmFormatEpoch, hotContextToken, mediaRole,
            )
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (bound) {
            hotEditorOwnerEpoch = editorOwnerEpoch
            hotRendererOwnerToken = rendererOwnerToken
            hotPcmFormatEpoch = pcmFormatEpoch
            this.hotContextToken = hotContextToken
        }
        return bound
    }

    override fun invalidateHotUpdateContext() {
        val handle = nativeHandle
        hotEditorOwnerEpoch = 0L
        hotRendererOwnerToken = 0L
        hotPcmFormatEpoch = 0L
        hotContextToken = 0
        if (handle <= 0L) return
        try {
            bindings.bindHotUpdateContext(handle, format, 0L, 0L, 0L, 0, mediaRole = false)
        } catch (_: Exception) {
            localNativeErrors++
        } catch (_: LinkageError) {
            localNativeErrors++
        }
    }

    override fun hotUpdateContextToken(): Int = hotContextToken

    override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
        val expectedByteCount = frames.toLong() * format.channels * Float.SIZE_BYTES
        if (
            nativeHandle <= 0L || input === output || frames <= 0 ||
            expectedByteCount > Int.MAX_VALUE || !input.isDirect || !output.isDirect ||
            input.remaining().toLong() != expectedByteCount || output.remaining().toLong() < expectedByteCount
        ) {
            return result.failure(frames, latencyFrames, DspBypassReason.INVALID_BUFFER)
        }

        val startNs = System.nanoTime()
        val status = try {
            try {
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
        } finally {
            nativeProcessTiming.record(System.nanoTime() - startNs)
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

    override fun enqueueHotUpdate(update: DspPreparedHotUpdate): Boolean {
        val handle = nativeHandle
        if (handle <= 0L || update.editorOwnerEpoch != hotEditorOwnerEpoch ||
            update.rendererOwnerToken != hotRendererOwnerToken || update.pcmFormatEpoch != hotPcmFormatEpoch ||
            update.hotContextToken != hotContextToken || !update.mediaRole
        ) return false
        val status = try {
            bindings.applyHotConfig(handle, update)
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
        if (status != NATIVE_OK && status != NATIVE_QUEUE_FULL) localNativeErrors++
        return status == NATIVE_OK
    }

    override fun prepareLocalPeqWarmUpdate(
        coefficients: DoubleArray,
        generation: Long,
        editorOwnerEpoch: Long,
        rendererOwnerToken: Long,
        pcmFormatEpoch: Long,
        hotContextToken: Int,
        mediaRole: Boolean,
    ): DspPreparedLocalPeqWarmUpdate? {
        val handle = nativeHandle
        if (handle <= 0L || generation <= 0L || editorOwnerEpoch != hotEditorOwnerEpoch ||
            rendererOwnerToken != hotRendererOwnerToken || pcmFormatEpoch != hotPcmFormatEpoch ||
            hotContextToken != this.hotContextToken || !mediaRole || coefficients.size % DspEqDesigner.COEFFICIENTS_PER_BAND != 0 ||
            coefficients.size > DspRuntimeConfig.MAX_EQ_BANDS * DspEqDesigner.COEFFICIENTS_PER_BAND
        ) return null
        val bandCount = coefficients.size / DspEqDesigner.COEFFICIENTS_PER_BAND
        val preparedValues = DoubleArray(bandCount * DspPreparedLocalPeqWarmUpdate.VALUES_PER_BAND)
        val historySequences = LongArray(2)
        val prepared = try {
            bindings.prepareLocalPeqUpdate(handle, coefficients, preparedValues, historySequences)
        } catch (_: SecurityException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
        if (!prepared || preparedValues.any { !it.isFinite() } || historySequences[0] < 0L ||
            historySequences[1] < historySequences[0]
        ) return null
        return DspPreparedLocalPeqWarmUpdate(
            generation = generation,
            editorOwnerEpoch = editorOwnerEpoch,
            rendererOwnerToken = rendererOwnerToken,
            pcmFormatEpoch = pcmFormatEpoch,
            hotContextToken = hotContextToken,
            mediaRole = mediaRole,
            bandCount = bandCount,
            historyEpochSequence = historySequences[0],
            historyEndSequence = historySequences[1],
            preparedValues = preparedValues,
        )
    }

    override fun enqueueLocalPeqWarmUpdate(update: DspPreparedLocalPeqWarmUpdate): Int {
        val handle = nativeHandle
        if (handle <= 0L || update.editorOwnerEpoch != hotEditorOwnerEpoch ||
            update.rendererOwnerToken != hotRendererOwnerToken || update.pcmFormatEpoch != hotPcmFormatEpoch ||
            update.hotContextToken != hotContextToken || !update.mediaRole
        ) return DSP_STATUS_STALE_UPDATE
        return try {
            bindings.enqueueLocalPeqUpdate(handle, update)
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
    }

    override fun cancelLocalPeqWarmUpdate(update: DspPreparedLocalPeqWarmUpdate): Int {
        val handle = nativeHandle
        if (handle <= 0L || update.editorOwnerEpoch != hotEditorOwnerEpoch ||
            update.rendererOwnerToken != hotRendererOwnerToken || update.pcmFormatEpoch != hotPcmFormatEpoch ||
            update.hotContextToken != hotContextToken || !update.mediaRole
        ) return DSP_STATUS_STALE_UPDATE
        return try {
            bindings.cancelLocalPeqUpdate(handle, update.generationToken)
        } catch (_: SecurityException) {
            -1
        } catch (_: LinkageError) {
            -1
        } catch (_: Exception) {
            -1
        }
    }

    override fun localPeqWarmAcknowledgedToken(): Int {
        val handle = nativeHandle
        if (handle <= 0L) return 0
        return try {
            bindings.localPeqAckToken(handle)
        } catch (_: SecurityException) {
            0
        } catch (_: LinkageError) {
            0
        } catch (_: Exception) {
            0
        }
    }

    override fun localPeqWarmRejectedToken(): Int {
        val handle = nativeHandle
        if (handle <= 0L) return 0
        return try {
            bindings.localPeqRejectedToken(handle)
        } catch (_: SecurityException) {
            0
        } catch (_: LinkageError) {
            0
        } catch (_: Exception) {
            0
        }
    }

    override fun hotAcknowledgedToken(): Int {
        val handle = nativeHandle
        if (handle <= 0L) return 0
        return try {
            bindings.hotAckToken(handle)
        } catch (_: SecurityException) {
            0
        } catch (_: LinkageError) {
            0
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    override fun diagnostics(): DspDiagnosticsSnapshot {
        val handle = nativeHandle
        if (handle <= 0L) {
            return DspDiagnosticsSnapshot(
                nativeErrorCount = localNativeErrors,
                nativeProcessUs = nativeProcessTiming.snapshot(),
            )
        }
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
            return DspDiagnosticsSnapshot(
                nativeErrorCount = localNativeErrors,
                nativeProcessUs = nativeProcessTiming.snapshot(),
            )
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
            nativeProcessUs = nativeProcessTiming.snapshot(),
        )
    }

    internal fun resetTimingMetrics() {
        nativeProcessTiming.reset()
    }

    override fun close() {
        val handle = nativeHandle
        if (handle <= 0L) return
        nativeHandle = 0L
        bindings.destroy(handle)
    }

    companion object {
        private const val NATIVE_OK = 0
        private const val NATIVE_QUEUE_FULL = 5
        private const val DSP_STATUS_STALE_UPDATE = 7
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
                handle = bindings.createWithStudio(
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
                    harmonicValues = prepared.harmonic,
                    macroValues = prepared.macroTone,
                    smartLoudnessValues = prepared.smartLoudness,
                    dynamicBassValues = prepared.dynamicBass,
                )
                if (handle <= 0L) return null
                val latencyFrames = bindings.latencyFrames(handle)
                val interchannelRelativeDelayFrames = bindings.interchannelRelativeDelayFrames(handle)
                if (latencyFrames < 0 || !interchannelRelativeDelayFrames.isFinite() ||
                    interchannelRelativeDelayFrames < 0.0
                ) {
                    runCatching { bindings.destroy(handle) }
                    return null
                }
                NativeDspProcessor(format, handle, bindings, latencyFrames, interchannelRelativeDelayFrames)
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
