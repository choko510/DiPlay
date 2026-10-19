package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDspProcessorTest {
    @Test
    fun loaderFailureAndNativeCreateFailureFallBackWithoutThrowing() {
        val format = DspAudioFormat(48_000, 2)
        assertFalse(NativeDspLibrary.ensureLoaded { throw UnsatisfiedLinkError("missing test library") })
        val config = DspRuntimeConfig(enabled = true)
        assertNull(NativeDspProcessor.createOrNull(format, config, loadLibrary = { false }))
        assertNull(
            NativeDspProcessor.createOrNull(
                format,
                config,
                loadLibrary = { true },
                bindings = FakeNativeDspBindings(createHandle = 0L),
            ),
        )
    }

    @Test
    fun preparationPassesDoubleCoefficientsAndHeadroomAdjustedPreampToNativeCreate() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                gainDb = 4.0,
                peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 6.0, q = 2.0)),
                multiband = DspMultibandConfig(
                    enabled = true,
                    lowMidCrossoverHz = 160.0,
                    midHighCrossoverHz = 2_000.0,
                    high = DspCompressorConfig(enabled = true, thresholdDb = -18.0, ratio = 2.0),
                ),
                dynamicEq = DspDynamicEqConfig(
                    enabled = true,
                    bands = listOf(
                        DspDynamicEqBandConfig(
                            enabled = true,
                            frequencyHz = 2_000.0,
                            mode = DspDynamicEqMode.CUT,
                            maxCutDb = 4.0,
                        ),
                    ),
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(-7.0, requireNotNull(bindings.createdGainDb), 0.02)
        assertEquals(5, bindings.createdEqCoefficients?.size)
        assertTrue(bindings.createdEqCoefficients?.all(Double::isFinite) == true)
        assertEquals(10, bindings.createdDynamics?.size)
        assertEquals(DspMultibandConfig.NATIVE_VALUE_COUNT, bindings.createdMultiband?.size)
        assertEquals(1.0, bindings.createdMultiband?.get(0) ?: 0.0, 0.0)
        assertEquals(160.0, bindings.createdMultiband?.get(1) ?: 0.0, 0.0)
        assertEquals(2_000.0, bindings.createdMultiband?.get(2) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdMultiband?.get(17) ?: 0.0, 0.0)
        assertEquals(DspDynamicEqConfig.NATIVE_VALUE_COUNT, bindings.createdDynamicEq?.size)
        assertEquals(1.0, bindings.createdDynamicEq?.get(0) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdDynamicEq?.get(1) ?: 0.0, 0.0)
        assertEquals(0.0, bindings.createdDynamicEq?.get(2) ?: 1.0, 0.0)
        assertEquals(2_000.0, bindings.createdDynamicEq?.get(3) ?: 0.0, 0.0)
        assertEquals(4.0, bindings.createdDynamicEq?.get(10) ?: 0.0, 0.0)
        assertEquals(0.0, bindings.createdDynamics?.get(0) ?: -1.0, 0.0)
        assertEquals(1.0, bindings.createdDynamics?.get(7) ?: -1.0, 0.0)
        assertEquals(0, bindings.createdBassCoefficients?.size)
        assertEquals(0, bindings.createdMonoBassCoefficients?.size)
        assertEquals(10 + DspAdvancedMsConfig.NATIVE_VALUE_COUNT, bindings.createdSpatial?.size)
        assertEquals(1.0, bindings.createdSpatial?.get(0) ?: 0.0, 0.0)
        assertEquals(0.0, bindings.createdSpatial?.get(1) ?: -1.0, 0.0)
        assertEquals(120.0, bindings.createdSpatial?.get(2) ?: 0.0, 0.0)
        assertEquals(0.0, bindings.createdSpatial?.get(3) ?: -1.0, 0.0)
        assertEquals(1.0, bindings.createdSpatial?.get(6) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdSpatial?.get(7) ?: 0.0, 0.0)
        assertEquals(5, bindings.createdConvolverConfig?.size)
        assertEquals(0.0, bindings.createdConvolverConfig?.get(0) ?: -1.0, 0.0)
        assertEquals(0, bindings.createdConvolverSamples?.size)
        processor?.close()
    }

    @Test
    fun spatialFiltersAndMidsideSettingsArePreparedForNativeCreation() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                bass = DspBassConfig(enabled = true, gainDb = 4.0, frequencyHz = 80.0),
                stereoWidth = 1.5,
                monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 100),
                safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(5, bindings.createdBassCoefficients?.size)
        assertEquals(5, bindings.createdMonoBassCoefficients?.size)
        assertEquals(1.5, bindings.createdSpatial?.get(0) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdSpatial?.get(1) ?: 0.0, 0.0)
        assertEquals(100.0, bindings.createdSpatial?.get(2) ?: 0.0, 0.0)
        processor?.close()
    }

    @Test
    fun seatFocusMeasurementsAndChannelControlsArePreparedForNativeCreation() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                seatFocus = DspSeatFocusConfig(
                    enabled = true,
                    preset = DspSeatFocusPreset.DRIVER,
                    leftDistanceCm = 150.0,
                    rightDistanceCm = 50.0,
                    leftTrimDb = 6.0,
                    rightTrimDb = -6.0,
                    leftPolarityInverted = true,
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )
        val spatial = requireNotNull(bindings.createdSpatial)

        assertEquals(1.0, spatial[3], 0.0)
        assertEquals(100.0583090379, spatial[4], 0.0001)
        assertEquals(240.0, spatial[5], 0.0)
        assertEquals(10.0.pow(6.0 / 20.0), spatial[6], 1e-12)
        assertEquals(10.0.pow(-6.0 / 20.0), spatial[7], 1e-12)
        assertEquals(1.0, spatial[8], 0.0)
        assertEquals(0.0, spatial[9], 0.0)
        assertEquals(-7.0, bindings.createdGainDb ?: 0.0, 0.02)
        processor?.close()
    }

    @Test
    fun advancedMsBandsAndSideCompressorArePreparedForNativeCreation() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                autoHeadroomEnabled = false,
                advancedMs = DspAdvancedMsConfig(
                    enabled = true,
                    crossoverHz = 180.0,
                    low = DspAdvancedMsBandConfig(midGainDb = 1.0, sideGainDb = -1.0, width = 0.5),
                    high = DspAdvancedMsBandConfig(midGainDb = -2.0, sideGainDb = 2.0, width = 1.5),
                    centerFocusDb = 2.5,
                    sideCompressor = DspCompressorConfig(
                        enabled = true,
                        thresholdDb = -26.0,
                        ratio = 3.0,
                        attackMs = 8.0,
                        releaseMs = 150.0,
                        kneeDb = 3.0,
                        makeupDb = 1.0,
                    ),
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )
        val spatial = requireNotNull(bindings.createdSpatial)

        assertEquals(10 + DspAdvancedMsConfig.NATIVE_VALUE_COUNT, spatial.size)
        assertEquals(1.0, spatial[10], 0.0)
        assertEquals(180.0, spatial[11], 0.0)
        assertEquals(1.0, spatial[12], 0.0)
        assertEquals(-1.0, spatial[13], 0.0)
        assertEquals(0.5, spatial[14], 0.0)
        assertEquals(-2.0, spatial[15], 0.0)
        assertEquals(2.0, spatial[16], 0.0)
        assertEquals(1.5, spatial[17], 0.0)
        assertEquals(2.5, spatial[18], 0.0)
        assertEquals(1.0, spatial[19], 0.0)
        assertEquals(-26.0, spatial[20], 0.0)
        assertEquals(3.0, spatial[21], 0.0)
        assertEquals(8.0, spatial[22], 0.0)
        assertEquals(150.0, spatial[23], 0.0)
        assertEquals(3.0, spatial[24], 0.0)
        assertEquals(1.0, spatial[25], 0.0)
        processor?.close()
    }

    @Test
    fun nativeProcessFailureReturnsAnErrorWithoutConsumingBuffers() {
        val format = DspAudioFormat(48_000, 2)
        val bindings = FakeNativeDspBindings(processStatus = 3)
        val processor = NativeDspProcessor(format, 42L, bindings, latencyFrames = 0)
        val input = floatBuffer(0.25f, -0.5f)
        val output = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())

        val result = processor.process(input, output, frames = 1)

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.NATIVE_FAILURE, result.bypassReason)
        assertEquals(0, input.position())
        assertEquals(0, output.position())
        assertEquals(1, bindings.processCalls)
        assertEquals(1L, processor.diagnostics().nativeErrorCount)
        processor.close()
        assertEquals(42L, bindings.destroyedHandle)
    }

    @Test
    fun successfulNativeBlockHonorsFramesChannelsAndAdvancesDirectBufferPositions() {
        val format = DspAudioFormat(48_000, 2)
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor(format, 7L, bindings, latencyFrames = 0)
        val input = floatBuffer(0.25f, -0.5f, 0.75f, -1.0f)
        val output = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder())

        val result = processor.process(input, output, frames = 2)

        assertEquals(DspProcessStatus.SUCCESS, result.status)
        assertEquals(16, input.position())
        assertEquals(16, output.position())
        output.flip()
        assertEquals(0.25f, output.float, 0f)
        assertEquals(-0.5f, output.float, 0f)
        assertEquals(0.75f, output.float, 0f)
        assertEquals(-1.0f, output.float, 0f)
        assertEquals(2, bindings.lastFrames)
        assertEquals(2, bindings.lastChannels)
        processor.close()
    }

    @Test
    fun invalidNativeBufferIsRejectedBeforeCallingBindings() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor(DspAudioFormat(48_000, 2), 9L, bindings, latencyFrames = 0)

        val result = processor.process(
            input = ByteBuffer.allocate(8),
            output = ByteBuffer.allocateDirect(8),
            frames = 1,
        )

        assertEquals(DspProcessStatus.ERROR, result.status)
        assertEquals(DspBypassReason.INVALID_BUFFER, result.bypassReason)
        assertEquals(0, bindings.processCalls)
        processor.close()
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() }

    private class FakeNativeDspBindings(
        private val createHandle: Long = 42L,
        private val processStatus: Int = 0,
    ) : NativeDspBindings {
        var processCalls = 0
        var lastFrames = 0
        var lastChannels = 0
        var destroyedHandle: Long? = null
        var createdGainDb: Double? = null
        var createdEqCoefficients: DoubleArray? = null
        var createdDynamics: DoubleArray? = null
        var createdMultiband: DoubleArray? = null
        var createdDynamicEq: DoubleArray? = null
        var createdBassCoefficients: DoubleArray? = null
        var createdMonoBassCoefficients: DoubleArray? = null
        var createdSpatial: DoubleArray? = null
        var createdConvolverConfig: DoubleArray? = null
        var createdConvolverSamples: FloatArray? = null

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
        ): Long {
            createdGainDb = gainDb
            createdEqCoefficients = eqCoefficients.copyOf()
            createdDynamics = dynamics.copyOf()
            createdMultiband = multiband.copyOf()
            createdDynamicEq = dynamicEq.copyOf()
            createdBassCoefficients = bassCoefficients.copyOf()
            createdMonoBassCoefficients = monoBassCoefficients.copyOf()
            createdSpatial = spatial.copyOf()
            createdConvolverConfig = convolverConfig.copyOf()
            createdConvolverSamples = convolverSamples.copyOf()
            return createHandle
        }

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
        ): Int {
            processCalls++
            lastFrames = frames
            lastChannels = channels
            if (processStatus != 0) return processStatus
            val sampleCount = frames * channels
            for (index in 0 until sampleCount) {
                output.putFloat(outputPosition + index * Float.SIZE_BYTES, input.getFloat(inputPosition + index * Float.SIZE_BYTES))
            }
            return 0
        }

        override fun reset(handle: Long): Int = 0
        override fun latencyFrames(handle: Long): Int = 0
        override fun diagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int = 0
        override fun destroy(handle: Long) {
            destroyedHandle = handle
        }
    }
}
