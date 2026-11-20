package com.shilapi.xcertplay.speed

enum class SpeedCompensationPreset {
    OFF,
    WEAK,
    STANDARD,
    STRONG,
    CUSTOM,
}

/**
 * User facing speed compensation settings. Only [masterEnabled] gates the feature; the
 * volume and EQ layers can be disabled independently and the preset picker is independent
 * from the saved DSP profile, so speed changes never touch the user's EQ or presets.
 */
data class SpeedCompensationSettings(
    val masterEnabled: Boolean = false,
    val volumeEnabled: Boolean = true,
    val eqEnabled: Boolean = true,
    val preset: SpeedCompensationPreset = SpeedCompensationPreset.OFF,
    val customCurve: SpeedCompensationCurve = SpeedCompensationCurve.STANDARD,
) {
    val curve: SpeedCompensationCurve
        get() = when (preset) {
            SpeedCompensationPreset.OFF -> SpeedCompensationCurve.NEUTRAL
            SpeedCompensationPreset.WEAK -> SpeedCompensationCurve.STANDARD.scaledBy(WEAK_FACTOR)
            SpeedCompensationPreset.STANDARD -> SpeedCompensationCurve.STANDARD
            SpeedCompensationPreset.STRONG -> SpeedCompensationCurve.STANDARD.scaledBy(STRONG_FACTOR)
            SpeedCompensationPreset.CUSTOM -> customCurve
        }

    val volumeActive: Boolean get() = masterEnabled && volumeEnabled && curve.maxVolumeDb > 0.0

    val eqActive: Boolean get() = masterEnabled && eqEnabled &&
        (curve.maxLowDb > 0.0 || curve.maxMidDb > 0.0 || curve.maxHighDb > 0.0)

    val active: Boolean get() = volumeActive || eqActive

    /** Worst-case boost that auto headroom must reserve while the feature is active. */
    val reservedBoostDb: Double
        get() {
            if (!active) return 0.0
            val curve = curve
            return (if (volumeActive) curve.maxVolumeDb else 0.0) +
                (if (eqActive) curve.maxLowDb + curve.maxMidDb + curve.maxHighDb else 0.0)
        }

    fun withPreset(preset: SpeedCompensationPreset): SpeedCompensationSettings {
        // Selecting a non-custom preset keeps the custom curve untouched so the user can
        // switch back to their edits without losing them.
        return copy(preset = preset)
    }

    /**
     * Switches the feature on or off. Turning it on without a chosen preset selects the standard
     * one, because the OFF preset intentionally carries a neutral curve.
     */
    fun withMasterEnabled(enabled: Boolean): SpeedCompensationSettings = copy(
        masterEnabled = enabled,
        preset = if (enabled && preset == SpeedCompensationPreset.OFF) {
            SpeedCompensationPreset.STANDARD
        } else {
            preset
        },
    )

    fun withCustomCurve(curve: SpeedCompensationCurve): SpeedCompensationSettings =
        copy(preset = SpeedCompensationPreset.CUSTOM, customCurve = curve)

    fun resetToDefaults(): SpeedCompensationSettings = SpeedCompensationSettings()

    companion object {
        const val WEAK_FACTOR = 0.5
        const val STRONG_FACTOR = 1.4

        fun ofPreset(preset: SpeedCompensationPreset): SpeedCompensationSettings =
            SpeedCompensationSettings(
                masterEnabled = preset != SpeedCompensationPreset.OFF,
                preset = preset,
            )
    }
}
