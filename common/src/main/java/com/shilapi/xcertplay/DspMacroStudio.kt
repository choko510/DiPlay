package com.shilapi.xcertplay

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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.dsp.DspSoundMacroSettings

internal enum class DspMacroControl {
    BASS_IMPACT,
    VOCAL_PRESENCE,
    AIR_DETAIL,
    SOUNDSTAGE_WIDTH,
}

internal fun updateDspMacro(settings: DspSoundMacroSettings, control: DspMacroControl, value: Int) =
    when (control) {
        DspMacroControl.BASS_IMPACT -> settings.copy(bassImpact = value.coerceIn(0, 100))
        DspMacroControl.VOCAL_PRESENCE -> settings.copy(vocalPresence = value.coerceIn(0, 100))
        DspMacroControl.AIR_DETAIL -> settings.copy(airDetail = value.coerceIn(0, 100))
        DspMacroControl.SOUNDSTAGE_WIDTH -> settings.copy(soundstageWidth = value.coerceIn(0, 100))
    }

@Composable
internal fun DspMacroStudio(
    settings: DspSoundMacroSettings,
    enabled: Boolean = true,
    onSettingsChange: (DspSoundMacroSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sound macros", style = MaterialTheme.typography.titleMedium)
            Text("50 is neutral. Turning Macro Power off keeps your values.", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Macro Power")
                    Text(
                        if (settings.enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
                    modifier = Modifier.semantics {
                        contentDescription = "Macro Power"
                        stateDescription = if (settings.enabled) "On" else "Off"
                    },
                )
            }
            MacroSlider("Bass Impact", settings.bassImpact, enabled) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.BASS_IMPACT, it))
            }
            MacroSlider("Vocal Presence", settings.vocalPresence, enabled) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.VOCAL_PRESENCE, it))
            }
            MacroSlider("Air Detail", settings.airDetail, enabled) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.AIR_DETAIL, it))
            }
            MacroSlider("Soundstage Width", settings.soundstageWidth, enabled) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.SOUNDSTAGE_WIDTH, it))
            }
        }
    }
}

@Composable
private fun MacroSlider(label: String, value: Int, enabled: Boolean, onValueChange: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 99,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = if (value == 50) "50, neutral" else value.toString()
            },
        )
    }
}
