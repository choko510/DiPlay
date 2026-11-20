package com.shilapi.xcertplay.dsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.speed.SpeedCompensationPreset
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedCurvePoint
import com.shilapi.xcertplay.speed.SpeedGpsState
import java.util.Locale

internal data class DspSpeedCompensationPaneState(
    val status: DspSpeedCompensationStatus,
    val rendererStatus: DspRendererApplyStatus?,
    val dspMasterEnabled: Boolean,
    val autoHeadroomEnabled: Boolean = true,
)

/**
 * DSP Studio pane for the GPS driven speed layer.
 *
 * It only edits the transient speed settings; nothing here writes to the saved DSP profile,
 * EQ, presets, or workspaces.
 */
@Composable
internal fun DspSpeedCompensationPane(
    settings: SpeedCompensationSettings,
    state: DspSpeedCompensationPaneState,
    enabled: Boolean,
    onSettingsChange: (SpeedCompensationSettings) -> Unit,
    onSettingsChangeFinished: () -> Unit,
    onReset: () -> Unit,
    onTestSpeedChange: (Double?) -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedState = state.status.state
    var selectedPointIndex by rememberSaveable { mutableIntStateOf(DEFAULT_POINT_INDEX) }
    val curve = settings.curve
    val pointIndex = selectedPointIndex.coerceIn(curve.points.indices)
    val point = curve.points[pointIndex]
    val testMode = state.status.state.testMode

    fun updatePoint(speedKmh: Double, volume: Double, low: Double, mid: Double, high: Double) {
        val updated = curve.withPoint(pointIndex, speedKmh, volume, low, mid, high)
        onSettingsChange(settings.withCustomCurve(updated))
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.dsp_speed_title), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_speed_master_switch), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.masterEnabled,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "speed-adaptive-master"
                    stateDescription = if (settings.masterEnabled) {
                        "on"
                    } else {
                        "off"
                    }
                },
                onCheckedChange = { checked ->
                    onSettingsChange(settings.withMasterEnabled(checked))
                    onSettingsChangeFinished()
                },
            )
        }

        Text(
            stringResource(R.string.dsp_speed_gps_state, stringResource(gpsStateLabel(state.status.gpsState))),
        )
        if (!state.status.locationPermissionGranted) {
            Text(stringResource(R.string.dsp_speed_permission_help), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRequestLocationPermission, enabled = enabled) {
                Text(stringResource(R.string.dsp_speed_request_permission))
            }
        }
        val currentSpeed = speedState.speedKmh
        Text(
            if (currentSpeed != null) {
                stringResource(R.string.dsp_speed_current_speed, formatDecimal(currentSpeed))
            } else {
                stringResource(R.string.dsp_speed_no_speed)
            },
        )
        if (speedState.stopped && speedState.speedKmh != null) {
            Text(stringResource(R.string.dsp_speed_stopped), style = MaterialTheme.typography.bodySmall)
        }
        if (testMode) {
            Text(stringResource(R.string.dsp_speed_test_badge), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(applyStateLabel(settings, state, speedState.speedKmh != null)))
        Text(
            stringResource(
                R.string.dsp_speed_gains_summary,
                formatDecimal(speedState.gains.volumeDb),
                formatDecimal(speedState.gains.lowDb),
                formatDecimal(speedState.gains.midDb),
                formatDecimal(speedState.gains.highDb),
            ),
        )
        if (state.status.locationPermissionGranted) {
            Text(
                speedState.speedAccuracyMetersPerSecond?.let {
                    stringResource(R.string.dsp_speed_gps_accuracy, formatDecimal(it))
                } ?: stringResource(R.string.dsp_speed_gps_accuracy_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_speed_volume_switch), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.volumeEnabled,
                enabled = enabled,
                onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(volumeEnabled = checked))
                    onSettingsChangeFinished()
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_speed_eq_switch), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.eqEnabled,
                enabled = enabled,
                onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(eqEnabled = checked))
                    onSettingsChangeFinished()
                },
            )
        }

        Text(stringResource(R.string.dsp_speed_preset))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpeedCompensationPreset.values().forEach { preset ->
                val label = stringResource(presetLabel(preset))
                val selected = settings.preset == preset
                if (selected) {
                    Button(
                        enabled = enabled,
                        onClick = {
                            onSettingsChange(settings.withPreset(preset))
                            onSettingsChangeFinished()
                        },
                    ) { Text("• $label") }
                } else {
                    OutlinedButton(
                        enabled = enabled,
                        onClick = {
                            onSettingsChange(settings.withPreset(preset))
                            onSettingsChangeFinished()
                        },
                    ) { Text(label) }
                }
            }
        }

        Text(stringResource(R.string.dsp_speed_curve_title), style = MaterialTheme.typography.titleSmall)
        if (settings.active && !state.autoHeadroomEnabled) {
            Text(
                stringResource(R.string.dsp_speed_headroom_off_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(stringResource(R.string.dsp_speed_curve_help), style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            curve.points.forEachIndexed { index, curvePoint ->
                val label = stringResource(R.string.dsp_speed_point_button, formatSpeed(curvePoint.speedKmh))
                if (index == pointIndex) {
                    Button(onClick = { selectedPointIndex = index }) { Text("• $label") }
                } else {
                    OutlinedButton(onClick = { selectedPointIndex = index }) { Text(label) }
                }
            }
        }
        CurveSlider(
            label = stringResource(R.string.dsp_speed_curve_volume),
            value = point.volumeDb,
            max = SpeedCurvePoint.MAX_VOLUME_DB,
            enabled = enabled,
            onValueChange = { updatePoint(point.speedKmh, it, point.lowDb, point.midDb, point.highDb) },
            onValueChangeFinished = onSettingsChangeFinished,
        )
        CurveSlider(
            label = stringResource(R.string.dsp_speed_curve_low),
            value = point.lowDb,
            max = SpeedCurvePoint.MAX_LOW_DB,
            enabled = enabled,
            onValueChange = { updatePoint(point.speedKmh, point.volumeDb, it, point.midDb, point.highDb) },
            onValueChangeFinished = onSettingsChangeFinished,
        )
        CurveSlider(
            label = stringResource(R.string.dsp_speed_curve_mid),
            value = point.midDb,
            max = SpeedCurvePoint.MAX_MID_DB,
            enabled = enabled,
            onValueChange = { updatePoint(point.speedKmh, point.volumeDb, point.lowDb, it, point.highDb) },
            onValueChangeFinished = onSettingsChangeFinished,
        )
        CurveSlider(
            label = stringResource(R.string.dsp_speed_curve_high),
            value = point.highDb,
            max = SpeedCurvePoint.MAX_HIGH_DB,
            enabled = enabled,
            onValueChange = { updatePoint(point.speedKmh, point.volumeDb, point.lowDb, point.midDb, it) },
            onValueChangeFinished = onSettingsChangeFinished,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_speed_test_mode), modifier = Modifier.weight(1f))
            Switch(
                checked = testMode,
                enabled = enabled,
                onCheckedChange = { checked -> onTestSpeedChange(if (checked) DEFAULT_TEST_SPEED_KMH else null) },
            )
        }
        if (testMode) {
            Text(stringResource(R.string.dsp_speed_test_mode_help), style = MaterialTheme.typography.bodySmall)
            val testSpeed = speedState.speedKmh ?: 0.0
            Text(stringResource(R.string.dsp_speed_test_speed, formatSpeed(testSpeed)))
            Slider(
                value = testSpeed.toFloat(),
                onValueChange = { onTestSpeedChange(it.toDouble()) },
                valueRange = 0f..MAX_TEST_SPEED_KMH.toFloat(),
                enabled = enabled,
            )
        }

        TextButton(onClick = onReset, enabled = enabled) {
            Text(stringResource(R.string.dsp_speed_reset))
        }
    }
}

@Composable
private fun CurveSlider(
    label: String,
    value: Double,
    max: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Text(stringResource(R.string.dsp_speed_slider_value, label, formatDecimal(value)))
        Slider(
            value = value.toFloat().coerceIn(0f, max.toFloat()),
            onValueChange = { onValueChange(it.toDouble().coerceIn(0.0, max)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private fun gpsStateLabel(state: SpeedGpsState): Int = when (state) {
    SpeedGpsState.STOPPED -> R.string.dsp_speed_gps_stopped
    SpeedGpsState.WAITING_FIX -> R.string.dsp_speed_gps_waiting
    SpeedGpsState.RECEIVING -> R.string.dsp_speed_gps_receiving
    SpeedGpsState.PERMISSION_DENIED -> R.string.dsp_speed_gps_permission_denied
    SpeedGpsState.NO_PROVIDER -> R.string.dsp_speed_gps_no_provider
    SpeedGpsState.PROVIDER_DISABLED -> R.string.dsp_speed_gps_provider_disabled
}

private fun applyStateLabel(
    settings: SpeedCompensationSettings,
    state: DspSpeedCompensationPaneState,
    hasSpeed: Boolean,
): Int = when {
    !state.dspMasterEnabled -> R.string.dsp_speed_apply_dsp_off
    !settings.active -> R.string.dsp_speed_apply_stopped
    state.rendererStatus?.phase == DspRendererApplyPhase.ACTIVE -> R.string.dsp_speed_apply_active
    state.rendererStatus?.phase == DspRendererApplyPhase.DEFERRED -> R.string.dsp_speed_apply_deferred
    state.rendererStatus == null && !hasSpeed -> R.string.dsp_speed_apply_stopped
    else -> R.string.dsp_speed_apply_preparing
}

private fun presetLabel(preset: SpeedCompensationPreset): Int = when (preset) {
    SpeedCompensationPreset.OFF -> R.string.dsp_speed_preset_off
    SpeedCompensationPreset.WEAK -> R.string.dsp_speed_preset_weak
    SpeedCompensationPreset.STANDARD -> R.string.dsp_speed_preset_standard
    SpeedCompensationPreset.STRONG -> R.string.dsp_speed_preset_strong
    SpeedCompensationPreset.CUSTOM -> R.string.dsp_speed_preset_custom
}

private fun formatDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun formatSpeed(value: Double): String = if (value == value.toLong().toDouble()) {
    value.toLong().toString()
} else {
    String.format(Locale.ROOT, "%.0f", value)
}

private const val DEFAULT_POINT_INDEX = 2
private const val DEFAULT_TEST_SPEED_KMH = 60.0
private const val MAX_TEST_SPEED_KMH = 120.0
