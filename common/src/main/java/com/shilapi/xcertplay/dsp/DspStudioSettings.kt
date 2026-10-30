package com.shilapi.xcertplay.dsp

import android.content.SharedPreferences

data class DspSoundMacroSettings(
    val enabled: Boolean = false,
    val bassImpact: Int = 50,
    val vocalPresence: Int = 50,
    val airDetail: Int = 50,
    val soundstageWidth: Int = 50,
) {
    init {
        require(bassImpact in 0..100)
        require(vocalPresence in 0..100)
        require(airDetail in 0..100)
        require(soundstageWidth in 0..100)
    }
}

enum class DspSmartLoudnessMode {
    OFF,
    AUTO_CALIBRATED,
    MANUAL_ESTIMATE,
    FIXED,
}

data class DspLoudnessCalibration(
    val routeToken: String,
    val quietVolumeFraction: Double,
    val referenceVolumeFraction: Double,
) {
    init {
        require(ROUTE_TOKEN_PATTERN.matches(routeToken))
        require(quietVolumeFraction.isFinite() && quietVolumeFraction in 0.0..1.0)
        require(referenceVolumeFraction.isFinite() && referenceVolumeFraction in 0.0..1.0)
        require(referenceVolumeFraction > quietVolumeFraction)
    }

    companion object {
        private val ROUTE_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}

data class DspSmartLoudnessSettings(
    val mode: DspSmartLoudnessMode = DspSmartLoudnessMode.OFF,
    val strength: Double = 0.5,
    val maxLowBoostDb: Double = 3.0,
    val maxHighBoostDb: Double = 1.5,
    val calibration: DspLoudnessCalibration? = null,
    val quietListeningLevel: Double = 0.25,
    val referenceListeningLevel: Double = 0.75,
    val manualListeningLevel: Double = 0.5,
    val fixedAmount: Double = 0.5,
) {
    init {
        require(strength.isFinite() && strength in 0.0..1.0)
        require(maxLowBoostDb.isFinite() && maxLowBoostDb in 0.0..3.0)
        require(maxHighBoostDb.isFinite() && maxHighBoostDb in 0.0..1.5)
        require(quietListeningLevel.isFinite() && quietListeningLevel in 0.0..1.0)
        require(referenceListeningLevel.isFinite() && referenceListeningLevel in 0.0..1.0)
        require(referenceListeningLevel > quietListeningLevel)
        require(manualListeningLevel.isFinite() && manualListeningLevel in 0.0..1.0)
        require(fixedAmount.isFinite() && fixedAmount in 0.0..1.0)
    }
}

data class DspVolumeReading(
    val routeToken: String? = null,
    val volumeFraction: Double? = null,
    val trustworthy: Boolean = false,
) {
    init {
        require(routeToken == null || ROUTE_TOKEN_PATTERN.matches(routeToken))
        require(volumeFraction == null || volumeFraction.isFinite() && volumeFraction in 0.0..1.0)
    }

    companion object {
        private val ROUTE_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}

data class DspDynamicBassSettings(
    val enabled: Boolean = false,
    val strength: Double = 0.5,
    val maxBoostDb: Double = 3.0,
    val detectorCutoffHz: Double = 120.0,
    val silenceGateDbfs: Double = -55.0,
    val lowThresholdDbfs: Double = -35.0,
    val highThresholdDbfs: Double = -10.0,
    val attackMs: Double = 40.0,
    val releaseMs: Double = 250.0,
) {
    init {
        require(strength.isFinite() && strength in 0.0..1.0)
        require(maxBoostDb.isFinite() && maxBoostDb in 0.0..3.0)
        require(detectorCutoffHz.isFinite() && detectorCutoffHz in 80.0..160.0)
        require(silenceGateDbfs.isFinite() && silenceGateDbfs in -90.0..-30.0)
        require(lowThresholdDbfs.isFinite() && lowThresholdDbfs in -60.0..0.0)
        require(highThresholdDbfs.isFinite() && highThresholdDbfs in -60.0..0.0)
        require(lowThresholdDbfs < highThresholdDbfs)
        require(attackMs.isFinite() && attackMs in 10.0..200.0)
        require(releaseMs.isFinite() && releaseMs in 50.0..1_000.0)
    }
}

data class DspStudioWorkspace(
    val profileSourceId: String,
    val baseProfileSnapshot: DspAudioProfile,
    val macros: DspSoundMacroSettings = DspSoundMacroSettings(),
    val smartLoudness: DspSmartLoudnessSettings = DspSmartLoudnessSettings(),
    val dynamicBass: DspDynamicBassSettings = DspDynamicBassSettings(),
    val selectedModulePresetIds: Map<String, String> = emptyMap(),
) {
    init {
        require(profileSourceId.matches(PROFILE_ID_PATTERN))
        require(selectedModulePresetIds.keys.all { it in MODULE_KEYS })
        require(selectedModulePresetIds.values.all { it.matches(PRESET_ID_PATTERN) })
    }

    companion object {
        private val PROFILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val PRESET_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        internal val MODULE_KEYS = listOf(
            "peq", "bass", "seat", "advanced_ms", "compressor", "multiband", "dynamic_eq",
            "harmonic", "limiter", "convolver", "cabin", "smart_loudness", "dynamic_bass", "macros",
        )
    }
}

internal enum class DspStudioFeature {
    WORKSPACE_UI,
    MACRO_OVERLAY,
    SMART_LOUDNESS,
    DYNAMIC_BASS,
    NATIVE_HOT_UPDATES,
    LOCAL_WARM,
    IR_WARM_BUDGET,
}

internal data class DspStudioFeatureFlags(
    val workspaceUiEnabled: Boolean = true,
    val macroOverlayEnabled: Boolean = true,
    val smartLoudnessEnabled: Boolean = true,
    val dynamicBassEnabled: Boolean = true,
    val nativeHotUpdatesEnabled: Boolean = true,
    val localWarmEnabled: Boolean = false,
    val irWarmBudgetEnabled: Boolean = false,
) {
    fun disableEnhancedDsp(): DspStudioFeatureFlags = copy(
        workspaceUiEnabled = false,
        macroOverlayEnabled = false,
        smartLoudnessEnabled = false,
        dynamicBassEnabled = false,
        nativeHotUpdatesEnabled = false,
        localWarmEnabled = false,
        irWarmBudgetEnabled = false,
    )

    fun isEnabled(feature: DspStudioFeature): Boolean = when (feature) {
        DspStudioFeature.WORKSPACE_UI -> workspaceUiEnabled
        DspStudioFeature.MACRO_OVERLAY -> macroOverlayEnabled
        DspStudioFeature.SMART_LOUDNESS -> smartLoudnessEnabled
        DspStudioFeature.DYNAMIC_BASS -> dynamicBassEnabled
        DspStudioFeature.NATIVE_HOT_UPDATES -> nativeHotUpdatesEnabled
        DspStudioFeature.LOCAL_WARM -> localWarmEnabled
        DspStudioFeature.IR_WARM_BUDGET -> irWarmBudgetEnabled
    }

    fun withEnabled(feature: DspStudioFeature, enabled: Boolean): DspStudioFeatureFlags = when (feature) {
        DspStudioFeature.WORKSPACE_UI -> copy(workspaceUiEnabled = enabled)
        DspStudioFeature.MACRO_OVERLAY -> copy(macroOverlayEnabled = enabled)
        DspStudioFeature.SMART_LOUDNESS -> copy(smartLoudnessEnabled = enabled)
        DspStudioFeature.DYNAMIC_BASS -> copy(dynamicBassEnabled = enabled)
        DspStudioFeature.NATIVE_HOT_UPDATES -> copy(nativeHotUpdatesEnabled = enabled)
        DspStudioFeature.LOCAL_WARM -> copy(localWarmEnabled = enabled)
        DspStudioFeature.IR_WARM_BUDGET -> copy(irWarmBudgetEnabled = enabled)
    }

    internal fun writeTo(editor: SharedPreferences.Editor) {
        editor.putBoolean(KEY_WORKSPACE_UI, workspaceUiEnabled)
            .putBoolean(KEY_MACRO_OVERLAY, macroOverlayEnabled)
            .putBoolean(KEY_SMART_LOUDNESS, smartLoudnessEnabled)
            .putBoolean(KEY_DYNAMIC_BASS, dynamicBassEnabled)
            .putBoolean(KEY_NATIVE_HOT_UPDATES, nativeHotUpdatesEnabled)
            .putBoolean(KEY_LOCAL_WARM, localWarmEnabled)
            .putBoolean(KEY_IR_WARM_BUDGET, irWarmBudgetEnabled)
    }

    companion object {
        private const val KEY_WORKSPACE_UI = "dsp_studio_flag_workspace_ui"
        private const val KEY_MACRO_OVERLAY = "dsp_studio_flag_macro_overlay"
        private const val KEY_SMART_LOUDNESS = "dsp_studio_flag_smart_loudness"
        private const val KEY_DYNAMIC_BASS = "dsp_studio_flag_dynamic_bass"
        private const val KEY_NATIVE_HOT_UPDATES = "dsp_studio_flag_native_hot_updates"
        private const val KEY_LOCAL_WARM = "dsp_studio_flag_local_warm"
        private const val KEY_IR_WARM_BUDGET = "dsp_studio_flag_ir_warm_budget"

        fun load(preferences: SharedPreferences): DspStudioFeatureFlags {
            fun read(key: String, defaultValue: Boolean): Boolean =
                runCatching { preferences.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)

            return DspStudioFeatureFlags(
                workspaceUiEnabled = read(KEY_WORKSPACE_UI, true),
                macroOverlayEnabled = read(KEY_MACRO_OVERLAY, true),
                smartLoudnessEnabled = read(KEY_SMART_LOUDNESS, true),
                dynamicBassEnabled = read(KEY_DYNAMIC_BASS, true),
                nativeHotUpdatesEnabled = read(KEY_NATIVE_HOT_UPDATES, true),
                localWarmEnabled = read(KEY_LOCAL_WARM, false),
                irWarmBudgetEnabled = read(KEY_IR_WARM_BUDGET, false),
            )
        }
    }
}
