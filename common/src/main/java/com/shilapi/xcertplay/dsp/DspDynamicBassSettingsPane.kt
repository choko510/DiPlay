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
import java.util.Locale

@Composable
fun DspDynamicBassSettingsPane(
    settings: DspDynamicBassSettings,
    onSettingsChange: (DspDynamicBassSettings) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
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
                    Text("Dynamic Bass", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.enabled) "Enabled · follows the input bass level" else "Off · no dynamic bass lift",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
                    modifier = Modifier.semantics {
                        contentDescription = "Dynamic Bass enabled, ${if (settings.enabled) "on" else "off"}"
                    },
                )
            }
            Text(
                "A linked low-frequency detector reads the input before this lift. Quiet bass can receive more lift; stronger bass receives less, up to the shared gain-safety budget.",
                style = MaterialTheme.typography.bodySmall,
            )
            val controlsEnabled = enabled && settings.enabled
            val thresholdGap = minOf(0.1, (settings.highThresholdDbfs - settings.lowThresholdDbfs) * 0.5)
            DspDynamicBassSlider(
                label = "Strength",
                value = settings.strength,
                range = 0.0..1.0,
                unit = "%",
                enabled = controlsEnabled,
                valueScale = 100.0,
                explanation = "Scales the requested lift: 0% is neutral; 100% allows the full configured maximum.",
                onValueChange = { onSettingsChange(settings.copy(strength = it)) },
            )
            DspDynamicBassSlider(
                label = "Maximum lift",
                value = settings.maxBoostDb,
                range = 0.0..3.0,
                unit = "dB",
                enabled = controlsEnabled,
                explanation = "Upper limit for the lift; the shared safety budget can reduce the effective amount.",
                onValueChange = { onSettingsChange(settings.copy(maxBoostDb = it)) },
            )
            DspDynamicBassSlider(
                label = "Detector cutoff",
                value = settings.detectorCutoffHz,
                range = 80.0..160.0,
                unit = "Hz",
                enabled = controlsEnabled,
                explanation = "Cutoff of the low-pass detector that measures bass before the lift is applied.",
                onValueChange = { onSettingsChange(settings.copy(detectorCutoffHz = it)) },
            )
            DspDynamicBassSlider(
                label = "Silence gate",
                value = settings.silenceGateDbfs,
                range = -90.0..-30.0,
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = "Bass below this detector level is treated as silence and receives no lift.",
                onValueChange = { onSettingsChange(settings.copy(silenceGateDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = "Full-lift threshold",
                value = settings.lowThresholdDbfs,
                range = -60.0..(settings.highThresholdDbfs - thresholdGap),
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = "At or below this bass level, the requested lift reaches its maximum.",
                onValueChange = { onSettingsChange(settings.copy(lowThresholdDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = "No-lift threshold",
                value = settings.highThresholdDbfs,
                range = (settings.lowThresholdDbfs + thresholdGap)..0.0,
                unit = "dBFS",
                enabled = controlsEnabled,
                explanation = "At or above this bass level, no dynamic lift is requested.",
                onValueChange = { onSettingsChange(settings.copy(highThresholdDbfs = it)) },
            )
            DspDynamicBassSlider(
                label = "Attack",
                value = settings.attackMs,
                range = 10.0..200.0,
                unit = "ms",
                enabled = controlsEnabled,
                explanation = "How quickly the detector envelope and requested lift respond to level changes.",
                onValueChange = { onSettingsChange(settings.copy(attackMs = it)) },
            )
            DspDynamicBassSlider(
                label = "Release",
                value = settings.releaseMs,
                range = 50.0..1_000.0,
                unit = "ms",
                enabled = controlsEnabled,
                explanation = "How gradually the lift settles after the bass level changes.",
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
    onValueChange: (Double) -> Unit,
) {
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
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "$label, ${formatDspDynamicBassValue(value * valueScale, unit)}, range " +
                    "${formatDspDynamicBassValue(range.start * valueScale, unit)} to " +
                    formatDspDynamicBassValue(range.endInclusive * valueScale, unit)
            },
        )
        if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDspDynamicBassValue(value: Double, unit: String): String {
    val number = String.format(Locale.ROOT, "%.1f", value)
    return if (unit == "%") "$number$unit" else "$number $unit"
}
