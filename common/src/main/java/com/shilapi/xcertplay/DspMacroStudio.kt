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
import androidx.compose.ui.res.stringResource
import com.shilapi.xcertplay.host.R
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
    onSettingsChangeFinished: () -> Unit = {},
) {
    val macroPowerLabel = stringResource(R.string.dsp_macro_power)
    val onLabel = stringResource(R.string.dsp_on)
    val offLabel = stringResource(R.string.dsp_off)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.dsp_macro_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dsp_macro_neutral_hint), style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsp_macro_power))
                    Text(
                        stringResource(if (settings.enabled) R.string.dsp_macro_enabled else R.string.dsp_macro_disabled),
                        style = MaterialTheme.typography.bodySmall,
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
                        contentDescription = macroPowerLabel
                        stateDescription = if (settings.enabled) onLabel else offLabel
                    },
                )
            }
            MacroSlider(stringResource(R.string.dsp_macro_bass_impact), settings.bassImpact, enabled, onSettingsChangeFinished) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.BASS_IMPACT, it))
            }
            MacroSlider(stringResource(R.string.dsp_macro_vocal_presence), settings.vocalPresence, enabled, onSettingsChangeFinished) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.VOCAL_PRESENCE, it))
            }
            MacroSlider(stringResource(R.string.dsp_macro_air_detail), settings.airDetail, enabled, onSettingsChangeFinished) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.AIR_DETAIL, it))
            }
            MacroSlider(stringResource(R.string.dsp_macro_soundstage_width), settings.soundstageWidth, enabled, onSettingsChangeFinished) {
                onSettingsChange(updateDspMacro(settings, DspMacroControl.SOUNDSTAGE_WIDTH, it))
            }
        }
    }
}

@Composable
private fun MacroSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChangeFinished: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    val neutralDescription = stringResource(R.string.dsp_macro_value_neutral, value)
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
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..100f,
            steps = 99,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = if (value == 50) neutralDescription else value.toString()
            },
        )
    }
}
