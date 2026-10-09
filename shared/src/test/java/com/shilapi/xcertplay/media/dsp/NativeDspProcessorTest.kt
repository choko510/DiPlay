package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        assertEquals(3, bindings.createdSpatial?.size)
        assertEquals(1.0, bindings.createdSpatial?.get(0) ?: 0.0, 0.0)
        assertEquals(0.0, bindings.createdSpatial?.get(1) ?: -1.0, 0.0)
        assertEquals(120.0, bindings.createdSpatial?.get(2) ?: 0.0, 0.0)
        assertEquals(5, bindings.createdConvolverConfig?.size)
        assertEquals(0.0, bindings.createdConvolverConfig?.get(0) ?: -1.0, 0.0)
        assertEquals(0, bindings.createdConvolverSamples?.size)
        assertEquals(8, bindings.createdSeatFocus?.size)
        assertEquals(0.0, bindings.createdSeatFocus?.get(0) ?: -1.0, 0.0)
        processor?.close()
    }

    @Test
    fun seatFocusConfigurationIsPreparedAndUpdatedWithoutRecreatingTheProcessor() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                seatFocus = DspSeatFocusConfig(
                    enabled = true,
                    leftDelayMs = 0.0,
                    rightDelayMs = 0.65,
                    leftTrimDb = 1.0,
                    rightTrimDb = -1.0,
                    invertRight = true,
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(1.0, bindings.createdSeatFocus?.get(0) ?: 0.0, 0.0)
        assertEquals(0.65, bindings.createdSeatFocus?.get(3) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdSeatFocus?.get(4) ?: 0.0, 0.0)
        assertEquals(-1.0, bindings.createdSeatFocus?.get(5) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdSeatFocus?.get(7) ?: 0.0, 0.0)

        val updated = requireNotNull(processor).updateSeatFocus(
            DspSeatFocusConfig(enabled = true, bypassed = true),
        )

        assertTrue(updated)
        assertEquals(1, bindings.seatFocusUpdateCalls)
        assertEquals(1.0, bindings.lastSeatFocusUpdate?.get(1) ?: 0.0, 0.0)
        processor.close()
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
    fun advancedMsConfigAndSeparateEqBanksArePreparedForNativeCreation() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                advancedMs = DspAdvancedMsConfig(
                    enabled = true,
                    monitorMode = DspAdvancedMsMonitorMode.MID_ONLY,
                    midGainDb = 2.0,
                    sideGainDb = -1.0,
                    midEqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0, enabled = true)),
                    sideEqBands = listOf(DspEqBand(DspEqType.NOTCH, 4_000.0, gainDb = -2.0, enabled = true)),
                    lowWidth = 0.5,
                    midWidth = 1.1,
                    highWidth = 1.5,
                    centerFocusDb = 1.0,
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(DspAdvancedMsConfig.NATIVE_VALUE_COUNT, bindings.createdAdvancedMs?.size)
        assertEquals(1.0, bindings.createdAdvancedMs?.get(0) ?: 0.0, 0.0)
        assertEquals(2.0, bindings.createdAdvancedMs?.get(1) ?: 0.0, 0.0)
        assertEquals(-1.0, bindings.createdAdvancedMs?.get(2) ?: 0.0, 0.0)
        assertEquals(0.5, bindings.createdAdvancedMs?.get(3) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdAdvancedMs?.get(9) ?: 0.0, 0.0)
        assertEquals(2.0, bindings.createdAdvancedMs?.get(10) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdAdvancedMs?.get(11) ?: 0.0, 0.0)
        assertEquals(DspAdvancedMsConfig.NATIVE_EQ_COEFFICIENT_COUNT, bindings.createdAdvancedMsEqCoefficients?.size)
        assertTrue(bindings.createdAdvancedMsEqCoefficients?.all(Double::isFinite) == true)
        assertTrue(bindings.createdAdvancedMsEqCoefficients?.take(5)?.any { it != 0.0 } == true)
        assertTrue(bindings.createdAdvancedMsEqCoefficients?.drop(30)?.take(5)?.any { it != 0.0 } == true)
        processor?.close()
    }

    @Test
    fun crossoversAboveTheCurrentNyquistBypassAdvancedMsWithoutRejectingTheGraph() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(8_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                advancedMs = DspAdvancedMsConfig(enabled = true, midGainDb = 6.0),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(0.0, bindings.createdAdvancedMs?.get(0) ?: -1.0, 0.0)
        assertEquals(0.0, bindings.createdAdvancedMs?.get(10) ?: -1.0, 0.0)
        assertEquals(-1.0, requireNotNull(bindings.createdGainDb), 0.02)
        processor?.close()
    }

    @Test
    fun advancedMsIsAnIdentityBypassForMonoFormats() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 1),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                advancedMs = DspAdvancedMsConfig(enabled = true, midGainDb = 6.0, lowWidth = 0.0),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(0.0, bindings.createdAdvancedMs?.get(0) ?: -1.0, 0.0)
        assertEquals(-1.0, requireNotNull(bindings.createdGainDb), 0.02)
        processor?.close()
    }

    @Test
    fun temporaryAdvancedMsMonitorModeUpdatesWithoutRecreatingTheProcessor() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                advancedMs = DspAdvancedMsConfig(enabled = true),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(requireNotNull(processor).updateAdvancedMsMonitorMode(DspAdvancedMsMonitorMode.SIDE_ONLY))
        assertEquals(DspAdvancedMsMonitorMode.SIDE_ONLY.ordinal, bindings.lastAdvancedMsMonitorMode)
        assertEquals(1, bindings.createCalls)
        requireNotNull(processor).close()
    }

    @Test
    fun cabinStaticFiltersAndMeasurementBoundedDynamicCutsArePreparedSeparately() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                cabinCorrection = DspCabinCorrectionConfig(
                    enabled = true,
                    calibrationId = "cal_front",
                    dynamicControlEnabled = true,
                    maxDynamicCutDb = 2.0,
                    cuts = listOf(
                        DspCabinCut(
                            frequencyHz = 82.0,
                            q = 4.0,
                            reductionDb = -4.0,
                            enabled = true,
                            source = DspCabinCutSource.MEASUREMENT,
                        ),
                    ),
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(processor != null)
        assertEquals(1.0, bindings.createdCabin?.get(0) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdCabin?.get(1) ?: 0.0, 0.0)
        assertEquals(DspEqDesigner.COEFFICIENTS_PER_BAND, bindings.createdCabinStaticCoefficients?.size)
        assertTrue(bindings.createdCabinStaticCoefficients?.all(Double::isFinite) == true)
        assertEquals(DspDynamicEqConfig.NATIVE_VALUE_COUNT, bindings.createdCabinDynamicEq?.size)
        assertEquals(1.0, bindings.createdCabinDynamicEq?.get(0) ?: 0.0, 0.0)
        assertEquals(1.0, bindings.createdCabinDynamicEq?.get(1) ?: 0.0, 0.0)
        assertEquals(DspDynamicEqMode.CUT.ordinal.toDouble(), bindings.createdCabinDynamicEq?.get(2) ?: -1.0, 0.0)
        assertEquals(82.0, bindings.createdCabinDynamicEq?.get(3) ?: 0.0, 0.0)
        assertEquals(2.0, bindings.createdCabinDynamicEq?.get(10) ?: 0.0, 0.0)
        processor?.close()
    }

    @Test
    fun temporaryCabinBypassUpdatesTheActiveNativeGraphInPlace() {
        val bindings = FakeNativeDspBindings()
        val processor = NativeDspProcessor.createOrNull(
            format = DspAudioFormat(48_000, 2),
            runtimeConfig = DspRuntimeConfig(
                enabled = true,
                cabinCorrection = DspCabinCorrectionConfig(
                    enabled = true,
                    cuts = listOf(DspCabinCut(82.0, 4.0, -3.0, enabled = true)),
                ),
            ),
            loadLibrary = { true },
            bindings = bindings,
        )

        assertTrue(requireNotNull(processor).updateCabinBypass(true))
        assertEquals(true, bindings.lastCabinBypass)
        assertEquals(1, bindings.createCalls)
        requireNotNull(processor).close()
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
        var createCalls = 0
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
        var createdSeatFocus: DoubleArray? = null
        var createdAdvancedMs: DoubleArray? = null
        var createdAdvancedMsEqCoefficients: DoubleArray? = null
        var createdCabin: DoubleArray? = null
        var createdCabinStaticCoefficients: DoubleArray? = null
        var createdCabinDynamicEq: DoubleArray? = null
        var lastCabinBypass: Boolean? = null
        var seatFocusUpdateCalls = 0
        var lastSeatFocusUpdate: DoubleArray? = null
        var lastAdvancedMsMonitorMode: Int? = null

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
            seatFocus: DoubleArray,
            advancedMs: DoubleArray,
            advancedMsEqCoefficients: DoubleArray,
            cabin: DoubleArray,
            cabinStaticCoefficients: DoubleArray,
            cabinDynamicEq: DoubleArray,
        ): Long {
            createCalls++
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
            createdSeatFocus = seatFocus.copyOf()
            createdAdvancedMs = advancedMs.copyOf()
            createdAdvancedMsEqCoefficients = advancedMsEqCoefficients.copyOf()
            createdCabin = cabin.copyOf()
            createdCabinStaticCoefficients = cabinStaticCoefficients.copyOf()
            createdCabinDynamicEq = cabinDynamicEq.copyOf()
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
        override fun updateSeatFocus(handle: Long, values: DoubleArray): Int {
            seatFocusUpdateCalls++
            lastSeatFocusUpdate = values.copyOf()
            return 0
        }
        override fun updateAdvancedMsMonitorMode(handle: Long, monitorMode: Int): Int {
            lastAdvancedMsMonitorMode = monitorMode
            return 0
        }
        override fun updateCabinBypass(handle: Long, bypassed: Boolean): Int {
            lastCabinBypass = bypassed
            return 0
        }
        override fun latencyFrames(handle: Long): Int = 0
        override fun diagnostics(handle: Long, values: DoubleArray, counters: LongArray): Int = 0
        override fun destroy(handle: Long) {
            destroyedHandle = handle
        }
    }
}
