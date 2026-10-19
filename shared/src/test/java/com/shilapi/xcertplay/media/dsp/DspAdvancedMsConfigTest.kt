package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspAdvancedMsConfigTest {
    @Test
    fun defaultsToLegacySpatialPathAndProvidesNativeValues() {
        val config = DspAdvancedMsConfig()

        assertFalse(config.enabled)
        assertEquals(120.0, config.crossoverHz, 0.0)
        assertEquals(1.0, config.low.width, 0.0)
        assertEquals(1.0, config.high.width, 0.0)
        assertEquals(DspAdvancedMsConfig.NATIVE_VALUE_COUNT, config.toNativeValues().size)
        assertEquals(0.0, config.maximumBoostDb, 0.0)
    }

    @Test
    fun mapsIndependentLowHighToneWidthCenterFocusAndSideDynamics() {
        val config = DspAdvancedMsConfig(
            enabled = true,
            crossoverHz = 180.0,
            low = DspAdvancedMsBandConfig(midGainDb = 1.0, sideGainDb = -1.0, width = 0.5),
            high = DspAdvancedMsBandConfig(midGainDb = -2.0, sideGainDb = 2.0, width = 1.5),
            centerFocusDb = 2.5,
            sideCompressor = DspCompressorConfig(enabled = true, makeupDb = 1.0),
        )

        val values = config.toNativeValues()

        assertEquals(1.0, values[0], 0.0)
        assertEquals(180.0, values[1], 0.0)
        assertEquals(1.0, values[2], 0.0)
        assertEquals(-1.0, values[3], 0.0)
        assertEquals(0.5, values[4], 0.0)
        assertEquals(-2.0, values[5], 0.0)
        assertEquals(2.0, values[6], 0.0)
        assertEquals(1.5, values[7], 0.0)
        assertEquals(2.5, values[8], 0.0)
        assertEquals(1.0, values[9], 0.0)
        assertEquals(9.52, config.maximumBoostDb, 0.02)
    }

    @Test
    fun boundsAreValidatedBeforeNativeGraphCreation() {
        assertTrue(runCatching { DspAdvancedMsConfig(crossoverHz = 1_001.0) }.isFailure)
        assertTrue(runCatching { DspAdvancedMsBandConfig(midGainDb = 6.1) }.isFailure)
        assertTrue(runCatching { DspAdvancedMsBandConfig(sideGainDb = Double.NaN) }.isFailure)
        assertTrue(runCatching { DspAdvancedMsBandConfig(width = 2.1) }.isFailure)
        assertTrue(runCatching { DspAdvancedMsConfig(centerFocusDb = -6.1) }.isFailure)
    }

    @Test
    fun autoHeadroomAccountsForTheLargestAdvancedMsBandAndSideMakeup() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                low = DspAdvancedMsBandConfig(midGainDb = 1.0, sideGainDb = -1.0, width = 0.5),
                high = DspAdvancedMsBandConfig(midGainDb = -2.0, sideGainDb = 2.0, width = 1.5),
                centerFocusDb = 2.5,
                sideCompressor = DspCompressorConfig(enabled = true, makeupDb = 1.0),
            ),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(10.52, prepared.headroom.reductionDb, 0.03)
        assertEquals(-10.52, prepared.appliedPreampDb, 0.03)
    }

    @Test
    fun monoFormatBypassesAdvancedMsWithoutAddingHeadroomReduction() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                low = DspAdvancedMsBandConfig(sideGainDb = 6.0, width = 2.0),
                high = DspAdvancedMsBandConfig(sideGainDb = 6.0, width = 2.0),
            ),
        ).prepare(DspAudioFormat(48_000, 1))

        assertEquals(0.0, prepared.spatial[10], 0.0)
        assertEquals(1.0, prepared.headroom.reductionDb, 0.02)
    }

    @Test
    fun advancedMsReplacesLegacyStereoWidthAndMonoBassStages() {
        val prepared = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            stereoWidth = 1.8,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 100),
            advancedMs = DspAdvancedMsConfig(enabled = true),
        ).prepare(DspAudioFormat(48_000, 2))

        assertEquals(1.0, prepared.spatial[0], 0.0)
        assertEquals(0.0, prepared.spatial[1], 0.0)
        assertTrue(prepared.monoBassCoefficients.isEmpty())
        assertEquals(1.0, prepared.spatial[14], 0.0)
        assertEquals(1.0, prepared.spatial[17], 0.0)
    }
}
