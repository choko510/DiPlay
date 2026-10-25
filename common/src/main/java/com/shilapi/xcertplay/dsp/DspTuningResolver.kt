package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspDynamicBassRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspMacroToneConfig
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspSmartLoudnessRuntimeConfig

internal data class DspTuningBreakdown(
    val macroBassDb: Double,
    val macroVocalDb: Double,
    val macroAirDb: Double,
    val relativeWidth: Double,
    val loudnessAvailable: Boolean,
    val loudnessLowDb: Double,
    val loudnessHighDb: Double,
    val dynamicBassMaxDb: Double,
    val requestedAdditionalBassDb: Double,
    val effectiveAdditionalBassDb: Double,
    val bassBudgetLimited: Boolean,
)

internal data class DspTuningResolution(
    val runtimeConfig: DspRuntimeConfig,
    val breakdown: DspTuningBreakdown,
)

internal object DspTuningResolver {
    const val MACRO_CURVE_VERSION = 1
    const val MAX_ADDITIONAL_BASS_BOOST_DB = 6.0

    fun resolve(
        profile: DspAudioProfile,
        masterEnabled: Boolean,
        macros: DspSoundMacroSettings = DspSoundMacroSettings(),
        loudness: DspSmartLoudnessSettings = DspSmartLoudnessSettings(),
        volumeReading: DspVolumeReading = DspVolumeReading(),
        dynamicBass: DspDynamicBassSettings = DspDynamicBassSettings(),
        impulseResponse: com.shilapi.xcertplay.media.dsp.DspImpulseResponse? = null,
    ): DspTuningResolution {
        require(profile.hasValidEqCapacity())

        val requestedMacroBass = if (macros.enabled) macroGain(macros.bassImpact, negativeDb = 2.0, positiveDb = 4.0) else 0.0
        val macroVocal = if (macros.enabled) macroGain(macros.vocalPresence, negativeDb = 2.0, positiveDb = 2.5) else 0.0
        val macroAir = if (macros.enabled) macroGain(macros.airDetail, negativeDb = 2.0, positiveDb = 2.0) else 0.0
        val relativeWidth = if (macros.enabled) 1.0 + 0.25 * normalizedMacro(macros.soundstageWidth) else 1.0

        val loudnessLevel = when (loudness.mode) {
            DspSmartLoudnessMode.OFF -> null
            DspSmartLoudnessMode.AUTO_CALIBRATED -> calibratedVolumeLevel(loudness.calibration, volumeReading)
            DspSmartLoudnessMode.MANUAL_ESTIMATE -> loudness.manualListeningLevel
            DspSmartLoudnessMode.FIXED -> null
        }
        val loudnessAvailable = when (loudness.mode) {
            DspSmartLoudnessMode.AUTO_CALIBRATED -> loudnessLevel != null
            DspSmartLoudnessMode.OFF -> false
            else -> true
        }
        val requestedLoudnessLow = when (loudness.mode) {
            DspSmartLoudnessMode.OFF -> 0.0
            DspSmartLoudnessMode.FIXED -> loudness.maxLowBoostDb * loudness.strength * loudness.fixedAmount
            else -> loudnessLevel?.let {
                compensationDb(it, loudness.quietListeningLevel, loudness.referenceListeningLevel, loudness.maxLowBoostDb, loudness.strength)
            } ?: 0.0
        }
        val requestedLoudnessHigh = when (loudness.mode) {
            DspSmartLoudnessMode.OFF -> 0.0
            DspSmartLoudnessMode.FIXED -> loudness.maxHighBoostDb * loudness.strength * loudness.fixedAmount
            else -> loudnessLevel?.let {
                compensationDb(it, loudness.quietListeningLevel, loudness.referenceListeningLevel, loudness.maxHighBoostDb, loudness.strength)
            } ?: 0.0
        }

        val requestedDynamicBassMax = if (dynamicBass.enabled) dynamicBass.maxBoostDb * dynamicBass.strength else 0.0
        val requestedBassBoost = requestedMacroBass.coerceAtLeast(0.0) + requestedLoudnessLow + requestedDynamicBassMax
        var remainingBassBudget = MAX_ADDITIONAL_BASS_BOOST_DB

        val effectiveDynamicBassMax = minOf(requestedDynamicBassMax, remainingBassBudget)
        remainingBassBudget -= effectiveDynamicBassMax
        val effectiveMacroBass = if (requestedMacroBass > 0.0) {
            minOf(requestedMacroBass, remainingBassBudget).also { remainingBassBudget -= it }
        } else {
            requestedMacroBass
        }
        val effectiveLoudnessLow = minOf(requestedLoudnessLow, remainingBassBudget)
        val effectiveBassBoost = effectiveMacroBass.coerceAtLeast(0.0) + effectiveLoudnessLow + effectiveDynamicBassMax

        val runtimeMacro = DspMacroToneConfig(
            enabled = macros.enabled,
            bassGainDb = effectiveMacroBass,
            vocalGainDb = macroVocal,
            airGainDb = macroAir,
            relativeWidth = relativeWidth,
        )
        val runtimeLoudness = DspSmartLoudnessRuntimeConfig(
            enabled = loudnessAvailable && loudness.mode != DspSmartLoudnessMode.OFF,
            lowGainDb = effectiveLoudnessLow,
            highGainDb = requestedLoudnessHigh,
        )
        val runtimeDynamicBass = DspDynamicBassRuntimeConfig(
            enabled = dynamicBass.enabled,
            strength = dynamicBass.strength,
            maxBoostDb = if (dynamicBass.strength > 0.0) {
                (effectiveDynamicBassMax / dynamicBass.strength).coerceIn(0.0, dynamicBass.maxBoostDb)
            } else {
                0.0
            },
            detectorCutoffHz = dynamicBass.detectorCutoffHz,
            silenceGateDbfs = dynamicBass.silenceGateDbfs,
            lowThresholdDbfs = dynamicBass.lowThresholdDbfs,
            highThresholdDbfs = dynamicBass.highThresholdDbfs,
            attackMs = dynamicBass.attackMs,
            releaseMs = dynamicBass.releaseMs,
        )
        val runtimeConfig = profile.toRuntimeConfig(
            masterEnabled = masterEnabled,
            impulseResponse = impulseResponse,
            macroTone = runtimeMacro,
            smartLoudness = runtimeLoudness,
            dynamicBass = runtimeDynamicBass,
        )
        return DspTuningResolution(
            runtimeConfig = runtimeConfig,
            breakdown = DspTuningBreakdown(
                macroBassDb = effectiveMacroBass,
                macroVocalDb = macroVocal,
                macroAirDb = macroAir,
                relativeWidth = relativeWidth,
                loudnessAvailable = loudnessAvailable,
                loudnessLowDb = effectiveLoudnessLow,
                loudnessHighDb = requestedLoudnessHigh,
                dynamicBassMaxDb = effectiveDynamicBassMax,
                requestedAdditionalBassDb = requestedBassBoost,
                effectiveAdditionalBassDb = effectiveBassBoost,
                bassBudgetLimited = effectiveBassBoost + GAIN_EPSILON_DB < requestedBassBoost,
            ),
        )
    }

    private fun calibratedVolumeLevel(
        calibration: DspLoudnessCalibration?,
        reading: DspVolumeReading,
    ): Double? {
        if (calibration == null || !reading.trustworthy || reading.routeToken != calibration.routeToken) return null
        val volume = reading.volumeFraction ?: return null
        return ((volume - calibration.quietVolumeFraction) /
            (calibration.referenceVolumeFraction - calibration.quietVolumeFraction)).coerceIn(0.0, 1.0)
    }

    private fun compensationDb(volumeLevel: Double, quietLevel: Double, referenceLevel: Double, maxDb: Double, strength: Double): Double {
        val fraction = ((referenceLevel - volumeLevel) / (referenceLevel - quietLevel)).coerceIn(0.0, 1.0)
        return maxDb * strength * fraction * fraction
    }

    private fun macroGain(value: Int, negativeDb: Double, positiveDb: Double): Double {
        val normalized = normalizedMacro(value)
        return if (normalized < 0.0) negativeDb * normalized else positiveDb * normalized
    }

    private fun normalizedMacro(value: Int): Double = ((value - 50) / 50.0).coerceIn(-1.0, 1.0)

    private const val GAIN_EPSILON_DB = 1e-9
}
