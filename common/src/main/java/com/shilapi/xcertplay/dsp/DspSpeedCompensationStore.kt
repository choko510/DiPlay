package com.shilapi.xcertplay.dsp

import android.content.Context
import android.content.SharedPreferences
import com.shilapi.xcertplay.speed.SpeedCompensationCurve
import com.shilapi.xcertplay.speed.SpeedCompensationPreset
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedCurvePoint

/**
 * Persists the speed compensation settings.
 *
 * The saved DSP profile is never touched: only the feature toggles, the preset, and the
 * custom curve are stored here. Corrupt or unknown data falls back to the defaults
 * (master OFF) instead of throwing, and no location data is written.
 */
internal class DspSpeedCompensationStore(
    context: Context,
    preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val preferences = preferences

    fun load(): SpeedCompensationSettings {
        val schema = preferences.getInt(KEY_SCHEMA, SCHEMA_VERSION)
        if (schema != SCHEMA_VERSION) {
            // No released older schema exists yet. Unknown versions fall back to safe defaults
            // rather than guessing at incompatible data.
            return SpeedCompensationSettings()
        }
        val preset = runCatching {
            SpeedCompensationPreset.valueOf(preferences.getString(KEY_PRESET, null) ?: return@runCatching null)
        }.getOrNull() ?: return SpeedCompensationSettings()
        val curve = loadCurve() ?: SpeedCompensationCurve.STANDARD
        return SpeedCompensationSettings(
            masterEnabled = preferences.getBoolean(KEY_MASTER_ENABLED, false),
            volumeEnabled = preferences.getBoolean(KEY_VOLUME_ENABLED, true),
            eqEnabled = preferences.getBoolean(KEY_EQ_ENABLED, true),
            preset = preset,
            customCurve = curve,
        )
    }

    fun save(settings: SpeedCompensationSettings) {
        val editor = preferences.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putBoolean(KEY_MASTER_ENABLED, settings.masterEnabled)
            .putBoolean(KEY_VOLUME_ENABLED, settings.volumeEnabled)
            .putBoolean(KEY_EQ_ENABLED, settings.eqEnabled)
            .putString(KEY_PRESET, settings.preset.name)
            .putInt(KEY_POINT_COUNT, settings.customCurve.points.size)
        settings.customCurve.points.forEachIndexed { index, point ->
            editor.putLong("${KEY_POINT_PREFIX}${index}_speed", point.speedKmh.toRawBits())
            editor.putLong("${KEY_POINT_PREFIX}${index}_volume", point.volumeDb.toRawBits())
            editor.putLong("${KEY_POINT_PREFIX}${index}_low", point.lowDb.toRawBits())
            editor.putLong("${KEY_POINT_PREFIX}${index}_mid", point.midDb.toRawBits())
            editor.putLong("${KEY_POINT_PREFIX}${index}_high", point.highDb.toRawBits())
        }
        editor.apply()
    }

    fun reset() {
        preferences.edit().clear().apply()
    }

    private fun loadCurve(): SpeedCompensationCurve? = runCatching {
        val count = preferences.getInt(KEY_POINT_COUNT, 0)
        if (count < SpeedCompensationCurve.MIN_POINTS || count > MAX_POINTS) return@runCatching null
        val points = (0 until count).map { index ->
            SpeedCurvePoint(
                speedKmh = readDouble(index, "speed"),
                volumeDb = readDouble(index, "volume"),
                lowDb = readDouble(index, "low"),
                midDb = readDouble(index, "mid"),
                highDb = readDouble(index, "high"),
            )
        }
        SpeedCompensationCurve(points)
    }.getOrNull()

    private fun readDouble(index: Int, name: String): Double =
        Double.fromBits(preferences.getLong("${KEY_POINT_PREFIX}${index}_$name", 0L))

    private companion object {
        const val PREFERENCES_NAME = "diplay_speed_compensation"
        const val SCHEMA_VERSION = 1
        const val MAX_POINTS = 16
        const val KEY_SCHEMA = "schema_version"
        const val KEY_MASTER_ENABLED = "master_enabled"
        const val KEY_VOLUME_ENABLED = "volume_enabled"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_PRESET = "preset"
        const val KEY_POINT_COUNT = "curve_point_count"
        const val KEY_POINT_PREFIX = "curve_point_"
    }
}
