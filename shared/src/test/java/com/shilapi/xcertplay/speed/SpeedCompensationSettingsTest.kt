package com.shilapi.xcertplay.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCompensationSettingsTest {
    @Test
    fun defaultsKeepTheFeatureOff() {
        val settings = SpeedCompensationSettings()

        assertFalse(settings.masterEnabled)
        assertFalse(settings.active)
        assertEquals(0.0, settings.reservedBoostDb, 1.0e-9)
    }

    @Test
    fun enablingWithoutAChosenPresetSelectsTheStandardCurve() {
        val enabled = SpeedCompensationSettings().withMasterEnabled(true)

        assertTrue(enabled.masterEnabled)
        assertEquals(SpeedCompensationPreset.STANDARD, enabled.preset)
        assertTrue(enabled.active)
        assertTrue(enabled.reservedBoostDb > 0.0)
    }

    @Test
    fun enablingKeepsAnExplicitPreset() {
        val settings = SpeedCompensationSettings(preset = SpeedCompensationPreset.STRONG)

        assertEquals(SpeedCompensationPreset.STRONG, settings.withMasterEnabled(true).preset)
    }

    @Test
    fun disablingKeepsThePresetForTheNextEnable() {
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        val disabled = settings.withMasterEnabled(false)

        assertFalse(disabled.masterEnabled)
        assertFalse(disabled.active)
        assertEquals(SpeedCompensationPreset.STANDARD, disabled.preset)
    }

    @Test
    fun theOffPresetCarriesANeutralCurve() {
        val settings = SpeedCompensationSettings(preset = SpeedCompensationPreset.OFF).copy(masterEnabled = true)

        assertFalse(settings.active)
        assertEquals(0.0, settings.reservedBoostDb, 1.0e-9)
    }

    @Test
    fun disablingBothLayersLeavesNothingToReserve() {
        val settings = SpeedCompensationSettings().withMasterEnabled(true).copy(
            volumeEnabled = false,
            eqEnabled = false,
        )

        assertFalse(settings.active)
        assertEquals(0.0, settings.reservedBoostDb, 1.0e-9)
    }
}
