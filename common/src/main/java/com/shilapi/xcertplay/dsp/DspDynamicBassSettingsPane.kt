package com.shilapi.xcertplay.dsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.shilapi.xcertplay.host.R
import java.util.Locale

@Composable
fun DspDynamicBassSettingsPane(
    settings: DspDynamicBassSettings,
    onSettingsChange: (DspDynamicBassSettings) -> Unit,
    onSettingsChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val switchDescription = stringResource(if (settings.enabled) R.string.dsp_db_switch_on else R.string.dsp_db_switch_off)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsp_module_dynamic_bass), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (settings.enabled) R.string.dsp_db_enabled else R.string.dsp_db_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = enabled,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(enabled = it))
                        onSettingsChangeFinished()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = switchDescription
                    },
                )
            }
            Text(
                stringResource(R.string.dsp_db_description),
                style = MaterialTheme.typography.bodySmall,
            )
            val controlsEnabled = enabled && settings.enabled
            val thresholdGap = minOf(0.1, (settings.highThresholdDbfs - settings.lowThresholdDbfs) * 0.5)
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_strength),
                value = settings.strength,
                range = 0.0..1.0,
                unit = "%",
                enabled = controlsEnabled,
                valueScale = 100.0,
                explanation = stringResource(R.string.dsp_db_strength_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(strength = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_max_lift),
                value = settings.maxBoostDb,
                range = 0.0..3.0,
                unit = "dB",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_max_lift_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(maxBoostDb = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_detector_cutoff),
                value = settings.detectorCutoffHz,
                range = 80.0..160.0,
                unit = "Hz",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_detector_cutoff_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(detectorCutoffHz = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_silence_gate),
                value = settings.silenceGateDbfs,
                range = -90.0..-30.0,
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_silence_gate_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(silenceGateDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_full_lift_threshold),
                value = settings.lowThresholdDbfs,
                range = -60.0..(settings.highThresholdDbfs - thresholdGap),
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_full_lift_threshold_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(lowThresholdDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_no_lift_threshold),
                value = settings.highThresholdDbfs,
                range = (settings.lowThresholdDbfs + thresholdGap)..0.0,
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_no_lift_threshold_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(highThresholdDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_attack),
                value = settings.attackMs,
                range = 10.0..200.0,
                unit = "ms",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_attack_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(attackMs = it)) },
            )
            DspDynamicBassSlider(
                label = stringResource(R.string.dsp_db_release),
                value = settings.releaseMs,
                range = 50.0..1_000.0,
                unit = "ms",
                enabled = controlsEnabled,
                explanation = stringResource(R.string.dsp_db_release_help),
                onValueChangeFinished = onSettingsChangeFinished,
                onValueChange = { onSettingsChange(settings.copy(releaseMs = it)) },
            )
        }
    }
}

@Composable
private fun DspDynamicBassSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    unit: String,
    enabled: Boolean,
    valueScale: Double = 1.0,
    explanation: String? = null,
    onValueChangeFinished: () -> Unit,
    onValueChange: (Double) -> Unit,
) {
    val sliderDescription = stringResource(
        R.string.dsp_db_slider_range,
        label,
        formatDspDynamicBassValue(value * valueScale, unit),
        formatDspDynamicBassValue(range.start * valueScale, unit),
        formatDspDynamicBassValue(range.endInclusive * valueScale, unit),
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatDspDynamicBassValue(value * valueScale, unit),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble().coerceIn(range.start, range.endInclusive)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = sliderDescription
            },
        )
        if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDspDynamicBassValue(value: Double, unit: String): String {
    val number = String.format(Locale.ROOT, "%.1f", value)
    return if (unit == "%") "$number$unit" else "$number $unit"
}
