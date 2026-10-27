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
import androidx.compose.ui.res.stringResource
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Reusable Smart Loudness editor; the host owns persistence and volume polling. */
@Composable
fun DspSmartLoudnessPane(
    settings: DspSmartLoudnessSettings,
    volumeReading: DspVolumeReading,
    onSettingsChange: (DspSmartLoudnessSettings) -> Unit,
    onSettingsChangeFinished: () -> Unit = {},
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
    val switchStateDescription = when {
        settings.mode == DspSmartLoudnessMode.OFF -> stringResource(R.string.dsp_off)
        settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED && !autoAvailable ->
            stringResource(R.string.dsp_smart_loudness_unavailable_state)
        else -> stringResource(R.string.dsp_smart_loudness_on_state, stringResource(settings.mode.accessibilityLabelResource()))
    }
    val switchDescription = stringResource(R.string.dsp_smart_loudness_switch)
    val status = when {
        settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED && !autoAvailable -> R.string.dsp_smart_status_auto_unavailable
        settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED -> R.string.dsp_smart_status_auto_calibrated
        settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE -> R.string.dsp_smart_status_manual
        settings.mode == DspSmartLoudnessMode.FIXED -> R.string.dsp_smart_status_fixed
        else -> R.string.dsp_smart_status_off
    }
    val currentCorrection = if (active) {
        stringResource(R.string.dsp_smart_maximum_low_high_format, formatDbValue(lowDb), formatDbValue(highDb))
    } else {
        stringResource(R.string.dsp_smart_zero_correction)
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dsp_smart_loudness_title), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_macro_enabled))
            Switch(
                checked = settings.mode != DspSmartLoudnessMode.OFF,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = switchDescription
                    stateDescription = switchStateDescription
                },
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(mode = if (enabled) DspSmartLoudnessMode.MANUAL_ESTIMATE else DspSmartLoudnessMode.OFF))
                    onSettingsChangeFinished()
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeButton("OFF", settings.mode == DspSmartLoudnessMode.OFF, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.OFF))
                onSettingsChangeFinished()
            }
            ModeButton("AUTO", settings.mode == DspSmartLoudnessMode.AUTO_CALIBRATED, Modifier.weight(1f), enabled = enabled && autoAvailable) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.AUTO_CALIBRATED))
                onSettingsChangeFinished()
            }
            ModeButton("MANUAL", settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.MANUAL_ESTIMATE))
                onSettingsChangeFinished()
            }
            ModeButton("FIXED", settings.mode == DspSmartLoudnessMode.FIXED, Modifier.weight(1f), enabled = enabled) {
                onSettingsChange(settings.copy(mode = DspSmartLoudnessMode.FIXED))
                onSettingsChangeFinished()
            }
        }
        Text(stringResource(status))
        Text(stringResource(R.string.dsp_smart_input, stringResource(volumeInputLabelResource(settings.calibration, volumeReading))))
        Text(stringResource(R.string.dsp_smart_correction, currentCorrection))

        if (settings.mode == DspSmartLoudnessMode.MANUAL_ESTIMATE) {
            LoudnessSlider(stringResource(R.string.dsp_smart_manual_level), settings.manualListeningLevel, enabled, onSettingsChangeFinished) {
                onSettingsChange(settings.copy(manualListeningLevel = it))
            }
        }
        if (settings.mode == DspSmartLoudnessMode.FIXED) {
            LoudnessSlider(stringResource(R.string.dsp_smart_fixed_amount), settings.fixedAmount, enabled, onSettingsChangeFinished) {
                onSettingsChange(settings.copy(fixedAmount = it))
            }
        }
        if (settings.mode != DspSmartLoudnessMode.OFF) {
            LoudnessSlider(stringResource(R.string.dsp_smart_strength), settings.strength, enabled, onSettingsChangeFinished) {
                onSettingsChange(settings.copy(strength = it))
            }
            Text(stringResource(R.string.dsp_smart_maximum, settings.maxLowBoostDb.toString(), settings.maxHighBoostDb.toString()))
        }
        if (onCaptureQuietPoint != null || onCaptureReferencePoint != null) {
            Text(stringResource(R.string.dsp_smart_capture_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCaptureQuietPoint?.invoke() }, enabled = enabled && volumeReading.trustworthy && volumeReading.routeToken != null) {
                    Text(stringResource(R.string.dsp_smart_capture_quiet))
                }
                Button(onClick = { onCaptureReferencePoint?.invoke() }, enabled = enabled && volumeReading.trustworthy && volumeReading.routeToken != null) {
                    Text(stringResource(R.string.dsp_smart_capture_reference))
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
private fun LoudnessSlider(
    label: String,
    value: Double,
    enabled: Boolean,
    onValueChangeFinished: () -> Unit,
    onValueChange: (Double) -> Unit,
) {
    val percentDescription = stringResource(R.string.dsp_smart_percent, (value * 100).toInt())
    Column {
        Text(stringResource(R.string.dsp_smart_slider_value, label, (value * 100).toInt()))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble().coerceIn(0.0, 1.0)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = percentDescription
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

private fun volumeInputLabelResource(calibration: DspLoudnessCalibration?, reading: DspVolumeReading): Int = when {
    !reading.trustworthy -> R.string.dsp_smart_input_untrusted
    reading.routeToken == null || reading.volumeFraction == null -> R.string.dsp_smart_input_invalid
    calibration == null -> R.string.dsp_smart_input_not_calibrated
    reading.routeToken != calibration.routeToken -> R.string.dsp_smart_input_route_mismatch
    else -> R.string.dsp_smart_input_calibrated
}

private fun formatDbValue(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun DspSmartLoudnessMode.accessibilityLabelResource(): Int = when (this) {
    DspSmartLoudnessMode.OFF -> R.string.dsp_smart_mode_off
    DspSmartLoudnessMode.AUTO_CALIBRATED -> R.string.dsp_smart_mode_automatic
    DspSmartLoudnessMode.MANUAL_ESTIMATE -> R.string.dsp_smart_mode_manual
    DspSmartLoudnessMode.FIXED -> R.string.dsp_smart_mode_fixed
}
