package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspDynamicsConfigTest {
    @Test
    fun compressorIsOffAndSafetyLimiterUsesTheSpecifiedDefault() {
        assertFalse(DspCompressorConfig().enabled)
        assertTrue(DspSafetyLimiterConfig().enabled)
        assertEquals(-1.0, DspSafetyLimiterConfig().thresholdDb, 0.0)
        assertEquals(60.0, DspSafetyLimiterConfig().releaseMs, 0.0)
    }

    @Test
    fun autoHeadroomOnlyIncludesMakeupWhenTheCompressorIsEnabled() {
        val format = DspAudioFormat(48_000, 2)
        val disabled = DspRuntimeConfig(
            enabled = true,
            compressor = DspCompressorConfig(enabled = false, makeupDb = 12.0),
        ).prepare(format)
        val enabled = DspRuntimeConfig(
            enabled = true,
            compressor = DspCompressorConfig(enabled = true, makeupDb = 12.0),
        ).prepare(format)

        assertEquals(1.0, disabled.headroom.reductionDb, 0.0)
        assertEquals(13.0, enabled.headroom.reductionDb, 0.0)
    }

    @Test
    fun compressorAndLimiterRejectUnsafeOrUnsupportedRanges() {
        assertTrue(runCatching { DspCompressorConfig(ratio = 0.5) }.isFailure)
        assertTrue(runCatching { DspCompressorConfig(attackMs = 0.0) }.isFailure)
        assertTrue(runCatching { DspCompressorConfig(kneeDb = 25.0) }.isFailure)
        assertTrue(runCatching { DspSafetyLimiterConfig(thresholdDb = 1.0) }.isFailure)
        assertTrue(runCatching { DspSafetyLimiterConfig(releaseMs = 0.0) }.isFailure)
    }
}
