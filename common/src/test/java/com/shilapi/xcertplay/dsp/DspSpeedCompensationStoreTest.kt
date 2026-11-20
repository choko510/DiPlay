package com.shilapi.xcertplay.dsp

import android.app.Application
import com.shilapi.xcertplay.speed.SpeedCompensationCurve
import com.shilapi.xcertplay.speed.SpeedCompensationPreset
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedCurvePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspSpeedCompensationStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun defaultsAreOffAndDoNotRequireAnyPermissionOrLocationData() {
        val store = DspSpeedCompensationStore(context)
        val settings = store.load()
        assertFalse(settings.masterEnabled)
        assertEquals(SpeedCompensationPreset.OFF, settings.preset)
        assertEquals(SpeedCompensationCurve.STANDARD, settings.customCurve)
        val preferences = context.getSharedPreferences("diplay_speed_compensation", 0)
        assertTrue(preferences.all.keys.none { key -> key.contains("latitude") || key.contains("longitude") })
    }

    @Test
    fun settingsRoundTripThroughPreferences() {
        val store = DspSpeedCompensationStore(context)
        val customCurve = SpeedCompensationCurve(
            listOf(
                SpeedCurvePoint(0.0, 0.0, 0.0, 0.0, 0.0),
                SpeedCurvePoint(40.0, 1.1, 0.6, 0.1, 0.3),
                SpeedCurvePoint(90.0, 2.2, 1.2, 0.3, 0.8),
            ),
        )
        val settings = SpeedCompensationSettings(
            masterEnabled = true,
            volumeEnabled = false,
            eqEnabled = true,
            preset = SpeedCompensationPreset.CUSTOM,
            customCurve = customCurve,
        )
        store.save(settings)
        assertEquals(settings, DspSpeedCompensationStore(context).load())
    }

    @Test
    fun corruptedCurveFallsBackToTheStandardCurveWithoutThrowing() {
        val preferences = context.getSharedPreferences("diplay_speed_compensation", 0)
        preferences.edit()
            .putInt("schema_version", 1)
            .putString("preset", "CUSTOM")
            .putBoolean("master_enabled", true)
            .putInt("curve_point_count", 2)
            .putLong("curve_point_0_speed", 0L)
            .putLong("curve_point_0_volume", 0L)
            .putLong("curve_point_0_low", 0L)
            .putLong("curve_point_0_mid", 0L)
            .putLong("curve_point_0_high", 0L)
            // The second point is a duplicated 0 km/h origin, which the curve rejects.
            .putLong("curve_point_1_speed", 0L)
            .putLong("curve_point_1_volume", 0L)
            .putLong("curve_point_1_low", 0L)
            .putLong("curve_point_1_mid", 0L)
            .putLong("curve_point_1_high", 0L)
            .apply()

        val settings = DspSpeedCompensationStore(context).load()
        assertEquals(SpeedCompensationCurve.STANDARD, settings.customCurve)
        assertTrue(settings.masterEnabled)
    }

    @Test
    fun unknownSchemaFallsBackToTheSafeDefaults() {
        val preferences = context.getSharedPreferences("diplay_speed_compensation", 0)
        preferences.edit().putInt("schema_version", 999).putBoolean("master_enabled", true).apply()
        val settings = DspSpeedCompensationStore(context).load()
        assertFalse(settings.masterEnabled)
        assertEquals(SpeedCompensationPreset.OFF, settings.preset)
    }

    @Test
    fun resetReturnsToTheInitialOffState() {
        val store = DspSpeedCompensationStore(context)
        store.save(SpeedCompensationSettings(masterEnabled = true, preset = SpeedCompensationPreset.STRONG))
        store.reset()
        assertEquals(SpeedCompensationSettings(), store.load())
    }
}
