package com.shilapi.xcertplay.dsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Reusable Smart Loudness editor; the host owns persistence and volume polling. */
@Composable
fun DspSmartLoudnessPane(
    settings: DspSmartLoudnessSettings,
    volumeReading: DspVolumeReading,
    onSettingsChange: (DspSmartLoudnessSettings) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCaptureQuietPoint: (() -> Unit)? = null,
    onCaptureReferencePoint: (() -> Unit)? = null,
) {
    val autoAvailable = isCalibratedReading(settings.calibration, volumeReading)
    val level = when (settings.mode) {
        DspSmartLoudnessMode.AUTO_CALIBRATED -> if (autoAvailable) {
            calibratedLevel(settings.calibration!!, volumeReading)
        } else null
        DspSmartLoudnessMode.MANUAL_ESTIMATE -> settings.manualListeningLevel
        DspSmartLoudnessMode.FIXED, DspSmartLoudnessMode.OFF -> null
    }
    val active = settings.mode != DspSmartLoudnessMode.OFF &&
        (settings.mode != DspSmartLoudnessMode.AUTO_CALIBRATED || autoAvailable)
    val lowDb = when (settings.mode) {
        DspSmartLoudnessMode.FIXED -> settings.maxLowBoostDb * settings.strength * settings.fixedAmount
        DspSmartLoudnessMode.OFF -> 0.0
        else -> level?.let { loudnessCompensation(it, settings.quietListeningLevel, settings.referenceListeningLevel, settings.maxLowBoostDb, settings.strength) } ?: 0.0
    }
    val highDb = when (settings.mode) {
        DspSmartLoudnessMode.FIXED -> settings.maxHighBoostDb * settings.strength * settings.fixedAmount
        DspSmartLoudnessMode.OFF -> 0.0
        else -> level?.let { loudnessCompensation(it, settings.quietListeningLevel, settings.referenceListeningLevel, settings.maxHighBoostDb, settings.strength) } ?: 0.0
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Smart Loudness", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = settings.mode != DspSmartLoudnessMode.OFF,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Smart Loudness enabled"
                    stateDescription = when {
                        settings.mode == DspSmartLoudnessMode.OFF -> "Off"
                        settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED && !autoAvailable -> "On, automatic mode unavailable for this route"
                        else -> "On, ${settings.mode.accessibilityLabel()} mode"
                    }
                },
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(mode = if (enabled) DspSmartLoudnessMode.MANUAL_ESTIMATE else DspSmartLoudnessMode.OFF))
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeButton("OFF", settings.mode == DspSmartLoudnessMode.OFF, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.OFF))
            }
            ModeButton("AUTO", settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED, Modifier.weight(1f), enabled = enabled && autoAvailable) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.AUTO_CALIBRATED))
            }
            ModeButton("MANUAL", settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.MANUAL_ESTIMATE))
            }
            ModeButton("FIXED", settings.mode == DspSmartLoudnessMode.FIXED, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.FIXED))
            }
        }
        Text(when {
            settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED && !autoAvailable -> "AUTO_UNAVAILABLE · calibrate this playback route"
            settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED -> "AUTO_CALIBRATED · trusted route input"
            settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE -> "MANUAL · declared listening level; device volume is unchanged"
            settings.mode == DspSmartLoudnessMode.FIXED -> "FIXED · constant compensation"
            else -> "OFF"
        })
        Text("Input: ${volumeInputLabel(settings.calibration, volumeReading)}")
        Text("Current correction: ${if (active) formatDb(lowDb, highDb) else "0.0 / 0.0 dB"}")

        if (settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE) {
            LoudnessSlider("Manual listening level", settings.manualListeningLevel, enabled) {
                onSettingsChange(settings.copy(manualListeningLevel = it))
            }
        }
        if (settings.mode == DspSmartLoudnessMode.FIXED) {
            LoudnessSlider("Fixed amount", settings.fixedAmount, enabled) {
                onSettingsChange(settings.copy(fixedAmount = it))
            }
        }
        if (settings.mode != DspSmartLoudnessMode.OFF) {
            LoudnessSlider("Strength", settings.strength, enabled) { onSettingsChange(settings.copy(strength = it)) }
            Text("Maximum: low ${settings.maxLowBoostDb} dB · high ${settings.maxHighBoostDb} dB")
        }
        if (onCaptureQuietPoint != null || onCaptureReferencePoint != null) {
            Text("Route calibration uses two trusted playback-volume readings. It is a listening-level preference, not an SPL measurement.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCaptureQuietPoint?.invoke() }, enabled = enabled && volumeReading.trustworthy && volumeReading.routeToken != null) {
                    Text("Capture quiet")
                }
                Button(onClick = { onCaptureReferencePoint?.invoke() }, enabled = enabled && volumeReading.trustworthy && volumeReading.routeToken != null) {
                    Text("Capture reference")
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(if (selected) "• $label" else label)
    }
}

@Composable
private fun LoudnessSlider(label: String, value: Double, enabled: Boolean, onValueChange: (Double) -> Unit) {
    Column {
        Text("$label · ${(value * 100).toInt()}%")
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble().coerceIn(0.0, 1.0)) },
            valueRange = 0f..1f,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = "${(value * 100).toInt()} percent"
            },
        )
    }
}

private fun isCalibratedReading(calibration: DspLoudnessCalibration?, reading: DspVolumeReading): Boolean =
    calibration != null && reading.trustworthy && reading.routeToken == calibration.routeToken &&
        reading.volumeFraction != null && reading.volumeFraction.isFinite() && reading.volumeFraction in 0.0..1.0

private fun calibratedLevel(calibration: DspLoudnessCalibration, reading: DspVolumeReading): Double =
    ((reading.volumeFraction!! - calibration.quietVolumeFraction) /
        (calibration.referenceVolumeFraction - calibration.quietVolumeFraction)).coerceIn(0.0, 1.0)

private fun loudnessCompensation(level: Double, quiet: Double, reference: Double, maxDb: Double, strength: Double): Double {
    val normalized = ((reference - level) / (reference - quiet)).coerceIn(0.0, 1.0)
    return maxDb * strength * normalized * normalized
}

private fun volumeInputLabel(calibration: DspLoudnessCalibration?, reading: DspVolumeReading): String = when {
    !reading.trustworthy -> "untrusted / unavailable"
    reading.routeToken == null || reading.volumeFraction == null -> "invalid reading"
    calibration == null -> "trusted reading · not calibrated"
    reading.routeToken != calibration.routeToken -> "route mismatch · AUTO unavailable"
    else -> "trusted · calibrated route"
}

private fun formatDb(low: Double, high: Double): String = String.format(Locale.ROOT, "%.1f / %.1f dB (low / high)", low, high)

private fun DspSmartLoudnessMode.accessibilityLabel(): String = when (this) {
    DspSmartLoudnessMode.OFF -> "off"
    DspSmartLoudnessMode.AUTO_CALIBRATED -> "automatic"
    DspSmartLoudnessMode.MANUAL_ESTIMATE -> "manual"
    DspSmartLoudnessMode.FIXED -> "fixed"
}
