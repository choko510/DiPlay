package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSpeedCompensationConfigTest {
    private val format = DspAudioFormat(48_000, 2)

    @Test
    fun nativeValuesMatchTheDocumentedPayloadOrder() {
        val config = DspSpeedCompensationConfig(
            enabled = true,
            volumeGainDb = 2.5,
            lowGainDb = 1.5,
            midGainDb = 0.4,
            highGainDb = 1.0,
            reservedBoostDb = 8.0,
        )
        val values = config.nativeValues()
        assertEquals(DspSpeedCompensationConfig.NATIVE_VALUE_COUNT, values.size)
        assertEquals(1.0, values[0], 0.0)
        assertEquals(2.5, values[1], 0.0)
        assertEquals(1.5, values[2], 0.0)
        assertEquals(0.4, values[3], 0.0)
        assertEquals(1.0, values[4], 0.0)
        assertTrue(config.active)
        assertFalse(config.isNeutral)
    }

    @Test
    fun configRejectsNonFiniteAndOutOfRangeGains() {
        assertTrue(runCatching { DspSpeedCompensationConfig(volumeGainDb = Double.NaN) }.isFailure)
        assertTrue(runCatching { DspSpeedCompensationConfig(lowGainDb = -0.1) }.isFailure)
        assertTrue(
            runCatching { DspSpeedCompensationConfig(midGainDb = DspSpeedCompensationConfig.MAX_MID_GAIN_DB + 0.01) }
                .isFailure,
        )
        assertTrue(
            runCatching { DspSpeedCompensationConfig(highGainDb = DspSpeedCompensationConfig.MAX_HIGH_GAIN_DB + 0.01) }
                .isFailure,
        )
        assertTrue(
            runCatching { DspSpeedCompensationConfig(reservedBoostDb = DspSpeedCompensationConfig.MAX_RESERVED_BOOST_DB + 1.0) }
                .isFailure,
        )
    }

    @Test
    fun autoHeadroomReservesTheWholeCurveSoThePreampDoesNotFollowTheSpeed() {
        val neutral = DspRuntimeConfig(enabled = true).prepare(format)
        val reserved = DspRuntimeConfig(
            enabled = true,
            speedCompensation = DspSpeedCompensationConfig(
                enabled = true,
                reservedBoostDb = 8.0,
            ),
        ).prepare(format)
        assertTrue(reserved.appliedPreampDb < neutral.appliedPreampDb - 7.0)
        assertTrue(reserved.appliedPreampDb >= -10.0)
        assertEquals(8.0, reserved.headroom.reductionDb - 1.0, 0.05)

        // The reserve depends on the configured curve, not on the current speed value, so a
        // speed change alone never re-applies the preamp.
        val driving = DspRuntimeConfig(
            enabled = true,
            speedCompensation = DspSpeedCompensationConfig(
                enabled = true,
                volumeGainDb = 1.5,
                lowGainDb = 0.9,
                midGainDb = 0.2,
                highGainDb = 0.5,
                reservedBoostDb = 8.0,
            ),
        ).prepare(format)
        assertEquals(reserved.appliedPreampDb, driving.appliedPreampDb, 1e-9)
    }

    @Test
    fun disabledFeatureDoesNotChangeHeadroomOrPreparedValues() {
        val withoutSpeed = DspRuntimeConfig(enabled = true).prepare(format)
        val prepared = DspRuntimeConfig(
            enabled = true,
            speedCompensation = DspSpeedCompensationConfig(),
        ).prepare(format)
        assertEquals(withoutSpeed.headroom.reductionDb, prepared.headroom.reductionDb, 1e-9)
        assertEquals(withoutSpeed.appliedPreampDb, prepared.appliedPreampDb, 1e-9)
        assertEquals(0.0, prepared.speedCompensation[0], 1e-9)
        assertEquals(0.0, prepared.speedCompensation[1], 1e-9)
    }

    @Test
    fun failureFallbackPreampIncludesTheSpeedReserve() {
        val config = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = true,
            gainDb = -6.0,
            speedCompensation = DspSpeedCompensationConfig(enabled = true, reservedBoostDb = 8.0),
        )
        val fallback = config.failureFallbackPreampDb(format)
        val withoutSpeed = DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = true,
            gainDb = -6.0,
        ).failureFallbackPreampDb(format)
        assertEquals(-9.0, fallback, 0.05)
        assertEquals(-6.0, withoutSpeed, 0.05)
        assertTrue(fallback < withoutSpeed - 2.0)
    }
}
