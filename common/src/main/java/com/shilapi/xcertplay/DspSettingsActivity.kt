package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.dsp.DspAudioProfile
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspProfileSaveResult
import com.shilapi.xcertplay.dsp.DspImpulseImportResult
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.shared.AppLanguage
import java.util.Locale
import java.util.UUID

class DspSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = DspProfileRuntime.get(applicationContext)
        setContent {
            MaterialTheme(colorScheme = dspColorScheme()) {
                DspSettingsScreen(runtime = runtime, onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DspSettingsScreen(runtime: DspProfileRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember(runtime) { mutableStateOf(runtime.availableProfiles()) }
    var profile by remember { mutableStateOf(runtime.selectedProfile()) }
    var dspEnabled by rememberSaveable { mutableStateOf(runtime.isDspEnabled()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var bandTypeDialog by remember { mutableStateOf<Int?>(null) }
    var impulseResponseIds by remember(runtime) { mutableStateOf(runtime.availableImpulseResponseIds()) }
    val openImpulseResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val irId = "ir_${UUID.randomUUID().toString().replace("-", "")}"
            runtime.importImpulseResponseAsync(
                id = irId,
                openInput = { context.contentResolver.openInputStream(uri) },
            ) { result ->
                when (result) {
                    is DspImpulseImportResult.Imported -> {
                        impulseResponseIds = runtime.availableImpulseResponseIds()
                        profile = profile.copy(
                            convolver = profile.convolver.copy(
                                enabled = true,
                                impulseResponseId = result.impulseResponse.id,
                                impulseResponse = null,
                            ),
                        )
                        Toast.makeText(context, R.string.dsp_ir_imported, Toast.LENGTH_SHORT).show()
                    }
                    DspImpulseImportResult.TooLarge -> Toast.makeText(context, R.string.dsp_ir_too_large, Toast.LENGTH_LONG).show()
                    DspImpulseImportResult.WriteFailed -> Toast.makeText(context, R.string.dsp_ir_write_failed, Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(context, R.string.dsp_ir_invalid, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsp_settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹") }
                },
            )
        },
    ) { insetPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insetPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dsp_settings_description), style = MaterialTheme.typography.bodyMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_enabled),
                        description = stringResource(R.string.dsp_enabled_description),
                        checked = dspEnabled,
                        onCheckedChange = { enabled ->
                            dspEnabled = enabled
                            profile = profile.copy(enabled = enabled)
                            runtime.applyAsync(profile, enabled) { }
                        },
                    )
                    Text(stringResource(R.string.dsp_profile), style = MaterialTheme.typography.titleMedium)
                    BoxWithDropdown(
                        expanded = profileMenuExpanded,
                        onExpandedChange = { profileMenuExpanded = it },
                        label = profileLabel(context, profile),
                        items = profiles,
                        itemLabel = { profileLabel(context, it) },
                        onSelect = { selected ->
                            profile = selected
                            profileMenuExpanded = false
                        },
                    )
                }
            }

            SectionCard(title = stringResource(R.string.dsp_basic_controls)) {
                DspValueSlider(stringResource(R.string.dsp_preamp), profile.preampDb, -24.0..12.0, "dB") {
                    profile = profile.copy(preampDb = it)
                }
                DspSwitchRow(
                    title = stringResource(R.string.dsp_bass),
                    checked = profile.bass.enabled,
                    onCheckedChange = { profile = profile.copy(bass = profile.bass.copy(enabled = it)) },
                )
                DspValueSlider(stringResource(R.string.dsp_bass_gain), profile.bass.gainDb, -18.0..18.0, "dB") {
                    profile = profile.copy(bass = profile.bass.copy(gainDb = it))
                }
                DspValueSlider(stringResource(R.string.dsp_bass_frequency), profile.bass.frequencyHz, 20.0..300.0, "Hz") {
                    profile = profile.copy(bass = profile.bass.copy(frequencyHz = it))
                }
                DspValueSlider(stringResource(R.string.dsp_stereo_width), profile.stereoWidth, 0.0..2.0, "") {
                    profile = profile.copy(stereoWidth = it)
                }
                DspSwitchRow(
                    title = stringResource(R.string.dsp_mono_bass),
                    checked = profile.monoBass.enabled,
                    onCheckedChange = { profile = profile.copy(monoBass = profile.monoBass.copy(enabled = it)) },
                )
                ChoiceField(
                    title = stringResource(R.string.dsp_mono_bass_cutoff),
                    selected = "${profile.monoBass.cutoffHz} Hz",
                    choices = DspMonoBassConfig.CUTOFFS_HZ.sorted().map { "$it Hz" },
                    onSelect = { value ->
                        profile = profile.copy(monoBass = profile.monoBass.copy(cutoffHz = value.substringBefore(' ').toInt()))
                    },
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                DspSwitchRow(
                    modifier = Modifier.padding(16.dp),
                    title = stringResource(R.string.dsp_advanced_controls),
                    checked = advanced,
                    onCheckedChange = { advanced = it },
                )
            }

            if (advanced) {
                SectionCard(title = stringResource(R.string.dsp_auto_headroom)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_auto_headroom),
                        checked = profile.autoHeadroomEnabled,
                        onCheckedChange = { profile = profile.copy(autoHeadroomEnabled = it) },
                    )
                    DspValueSlider(
                        stringResource(R.string.dsp_headroom_margin),
                        profile.autoHeadroomMarginDb,
                        0.0..12.0,
                        "dB",
                    ) { profile = profile.copy(autoHeadroomMarginDb = it) }
                }
                SectionCard(title = stringResource(R.string.dsp_parametric_eq)) {
                    profile.eqBands.forEachIndexed { index, band ->
                        EqBandControl(
                            index = index,
                            band = band,
                            onEditType = { bandTypeDialog = index },
                            onChange = { changed ->
                                profile = profile.copy(eqBands = profile.eqBands.toMutableList().apply { set(index, changed) })
                            },
                        )
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_compressor)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_compressor),
                        checked = profile.compressor.enabled,
                        onCheckedChange = { profile = profile.copy(compressor = profile.compressor.copy(enabled = it)) },
                    )
                    DspValueSlider(stringResource(R.string.dsp_threshold), profile.compressor.thresholdDb, -60.0..0.0, "dB") {
                        profile = profile.copy(compressor = profile.compressor.copy(thresholdDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ratio), profile.compressor.ratio, 1.0..20.0, ":1") {
                        profile = profile.copy(compressor = profile.compressor.copy(ratio = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_attack), profile.compressor.attackMs, 0.1..250.0, "ms") {
                        profile = profile.copy(compressor = profile.compressor.copy(attackMs = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_release), profile.compressor.releaseMs, 1.0..500.0, "ms") {
                        profile = profile.copy(compressor = profile.compressor.copy(releaseMs = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_knee), profile.compressor.kneeDb, 0.0..24.0, "dB") {
                        profile = profile.copy(compressor = profile.compressor.copy(kneeDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_makeup), profile.compressor.makeupDb, -24.0..24.0, "dB") {
                        profile = profile.copy(compressor = profile.compressor.copy(makeupDb = it))
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_multiband)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_multiband),
                        checked = profile.multiband.enabled,
                        onCheckedChange = { profile = profile.copy(multiband = profile.multiband.copy(enabled = it)) },
                    )
                    if (profile.multiband.enabled) {
                        DspValueSlider(
                            stringResource(R.string.dsp_low_mid_crossover),
                            profile.multiband.lowMidCrossoverHz,
                            20.0..minOf(1_000.0, profile.multiband.midHighCrossoverHz - 20.0),
                            "Hz",
                        ) { profile = profile.copy(multiband = profile.multiband.copy(lowMidCrossoverHz = it)) }
                        DspValueSlider(
                            stringResource(R.string.dsp_mid_high_crossover),
                            profile.multiband.midHighCrossoverHz,
                            maxOf(
                                DspMultibandConfig.MIN_MID_HIGH_CROSSOVER_HZ,
                                profile.multiband.lowMidCrossoverHz + 20.0,
                            )..DspMultibandConfig.MAX_MID_HIGH_CROSSOVER_HZ,
                            "Hz",
                        ) { profile = profile.copy(multiband = profile.multiband.copy(midHighCrossoverHz = it)) }
                        MultibandCompressorControl(
                            title = stringResource(R.string.dsp_low_band),
                            config = profile.multiband.low,
                            onChange = { profile = profile.copy(multiband = profile.multiband.copy(low = it)) },
                        )
                        MultibandCompressorControl(
                            title = stringResource(R.string.dsp_mid_band),
                            config = profile.multiband.mid,
                            onChange = { profile = profile.copy(multiband = profile.multiband.copy(mid = it)) },
                        )
                        MultibandCompressorControl(
                            title = stringResource(R.string.dsp_high_band),
                            config = profile.multiband.high,
                            onChange = { profile = profile.copy(multiband = profile.multiband.copy(high = it)) },
                        )
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_dynamic_eq)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_dynamic_eq),
                        checked = profile.dynamicEq.enabled,
                        onCheckedChange = {
                            profile = profile.copy(
                                dynamicEq = DspDynamicEqConfig(enabled = it, bands = profile.dynamicEq.bands),
                            )
                        },
                    )
                    if (profile.dynamicEq.enabled) {
                        profile.dynamicEq.bands.forEachIndexed { index, band ->
                            DynamicEqBandControl(index, band) { changed ->
                                profile = profile.copy(
                                    dynamicEq = DspDynamicEqConfig(
                                        enabled = true,
                                        bands = profile.dynamicEq.bands.toMutableList().apply { set(index, changed) },
                                    ),
                                )
                            }
                        }
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_limiter)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_limiter),
                        checked = profile.limiter.enabled,
                        onCheckedChange = { profile = profile.copy(limiter = profile.limiter.copy(enabled = it)) },
                    )
                    DspValueSlider(stringResource(R.string.dsp_threshold), profile.limiter.thresholdDb, -12.0..0.0, "dB") {
                        profile = profile.copy(limiter = profile.limiter.copy(thresholdDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_release), profile.limiter.releaseMs, 1.0..500.0, "ms") {
                        profile = profile.copy(limiter = profile.limiter.copy(releaseMs = it))
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_convolver)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_convolver),
                        checked = profile.convolver.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled && profile.convolver.impulseResponseId == null) {
                                Toast.makeText(context, R.string.dsp_ir_select_first, Toast.LENGTH_SHORT).show()
                            } else {
                                profile = profile.copy(convolver = profile.convolver.copy(enabled = enabled, impulseResponse = null))
                            }
                        },
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_impulse_response),
                        selected = profile.convolver.impulseResponseId ?: stringResource(R.string.dsp_ir_none),
                        choices = listOf(stringResource(R.string.dsp_ir_none)) + impulseResponseIds,
                        onSelect = { selected ->
                            val id = selected.takeUnless { it == context.getString(R.string.dsp_ir_none) }
                            profile = profile.copy(
                                convolver = profile.convolver.copy(
                                    enabled = id != null,
                                    impulseResponseId = id,
                                    impulseResponse = null,
                                ),
                            )
                        },
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openImpulseResponse.launch(arrayOf("audio/*")) },
                    ) { Text(stringResource(R.string.dsp_ir_import)) }
                    DspValueSlider(stringResource(R.string.dsp_wet), profile.convolver.wet, 0.0..1.0, "%") {
                        profile = profile.copy(convolver = profile.convolver.copy(wet = it))
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_diagnostics)) {
                    Text(stringResource(R.string.dsp_current_profile, profileLabel(context, profile)))
                    Text(stringResource(R.string.dsp_dsp_state, if (dspEnabled) "ON" else "OFF"))
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val applied = profile.copy(
                        enabled = dspEnabled,
                        convolver = profile.convolver.copy(impulseResponse = null),
                    )
                    if (applied.id == CUSTOM_PROFILE_1 || applied.id == CUSTOM_PROFILE_2) {
                        runtime.saveCustomAsync(applied, dspEnabled) { result ->
                            showProfileSaveResult(context, result)
                            if (result == DspProfileSaveResult.SAVED) {
                                profile = applied
                                profiles = runtime.availableProfiles()
                            }
                        }
                    } else {
                        runtime.applyAsync(applied, dspEnabled) { profile = applied }
                    }
                },
            ) {
                Text(stringResource(R.string.dsp_apply))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val custom = profile.copy(id = CUSTOM_PROFILE_1, name = context.getString(R.string.dsp_preset_custom_1), enabled = dspEnabled)
                        runtime.saveCustomAsync(custom, dspEnabled) { result ->
                            if (result == DspProfileSaveResult.SAVED) profile = custom
                            if (result == DspProfileSaveResult.SAVED) profiles = runtime.availableProfiles()
                            showProfileSaveResult(context, result)
                        }
                    },
                ) { Text(stringResource(R.string.dsp_save_custom_1)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val custom = profile.copy(id = CUSTOM_PROFILE_2, name = context.getString(R.string.dsp_preset_custom_2), enabled = dspEnabled)
                        runtime.saveCustomAsync(custom, dspEnabled) { result ->
                            if (result == DspProfileSaveResult.SAVED) profile = custom
                            if (result == DspProfileSaveResult.SAVED) profiles = runtime.availableProfiles()
                            showProfileSaveResult(context, result)
                        }
                    },
                ) { Text(stringResource(R.string.dsp_save_custom_2)) }
            }
        }
    }

    bandTypeDialog?.let { index ->
        val band = profile.eqBands[index]
        AlertDialog(
            onDismissRequest = { bandTypeDialog = null },
            title = { Text(stringResource(R.string.dsp_eq_type)) },
            text = {
                Column {
                    DspEqType.entries.forEach { type ->
                        TextButton(
                            onClick = {
                                profile = profile.copy(eqBands = profile.eqBands.toMutableList().apply {
                                    set(index, band.copy(type = type))
                                })
                                bandTypeDialog = null
                            },
                        ) { Text(type.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bandTypeDialog = null }) { Text(stringResource(R.string.dsp_done)) } },
        )
    }
}

@Composable
private fun EqBandControl(
    index: Int,
    band: DspEqBand,
    onEditType: () -> Unit,
    onChange: (DspEqBand) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dsp_eq_band, index + 1), modifier = Modifier.weight(1f))
                Switch(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
            }
            TextButton(onClick = onEditType) { Text(band.type.name) }
            DspValueSlider(stringResource(R.string.dsp_frequency), band.frequencyHz, 20.0..19_800.0, "Hz") {
                onChange(band.copy(frequencyHz = it))
            }
            DspValueSlider(stringResource(R.string.dsp_gain), band.gainDb, -18.0..18.0, "dB") {
                onChange(band.copy(gainDb = it))
            }
            DspValueSlider(stringResource(R.string.dsp_q), band.q, 0.1..20.0, "") {
                onChange(band.copy(q = it))
            }
        }
    }
}

@Composable
private fun DspValueSlider(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    unit: String,
    onChange: (Double) -> Unit,
) {
    Column {
        val formatted = String.format(Locale.getDefault(), "%.1f", value)
        Text(if (unit.isBlank()) "$title: $formatted" else "$title: $formatted $unit")
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

@Composable
private fun MultibandCompressorControl(
    title: String,
    config: DspCompressorConfig,
    onChange: (DspCompressorConfig) -> Unit,
) {
    DspSwitchRow(title = title, checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
    DspValueSlider(stringResource(R.string.dsp_threshold), config.thresholdDb, -60.0..0.0, "dB") {
        onChange(config.copy(thresholdDb = it))
    }
    DspValueSlider(stringResource(R.string.dsp_ratio), config.ratio, 1.0..20.0, ":1") {
        onChange(config.copy(ratio = it))
    }
    DspValueSlider(stringResource(R.string.dsp_attack), config.attackMs, 0.1..250.0, "ms") {
        onChange(config.copy(attackMs = it))
    }
    DspValueSlider(stringResource(R.string.dsp_release), config.releaseMs, 1.0..500.0, "ms") {
        onChange(config.copy(releaseMs = it))
    }
    DspValueSlider(stringResource(R.string.dsp_knee), config.kneeDb, 0.0..24.0, "dB") {
        onChange(config.copy(kneeDb = it))
    }
    DspValueSlider(stringResource(R.string.dsp_makeup), config.makeupDb, -24.0..24.0, "dB") {
        onChange(config.copy(makeupDb = it))
    }
}

@Composable
private fun DynamicEqBandControl(
    index: Int,
    band: DspDynamicEqBandConfig,
    onChange: (DspDynamicEqBandConfig) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DspSwitchRow(
                title = stringResource(R.string.dsp_eq_band, index + 1),
                checked = band.enabled,
                onCheckedChange = { onChange(band.copy(enabled = it)) },
            )
            val cutLabel = stringResource(R.string.dsp_dynamic_eq_cut)
            val boostLabel = stringResource(R.string.dsp_dynamic_eq_boost)
            val modes = listOf(cutLabel to DspDynamicEqMode.CUT, boostLabel to DspDynamicEqMode.BOOST)
            ChoiceField(
                title = stringResource(R.string.dsp_dynamic_eq_mode),
                selected = modes.first { it.second == band.mode }.first,
                choices = modes.map { it.first },
                onSelect = { selected -> onChange(band.copy(mode = modes.first { it.first == selected }.second)) },
            )
            DspValueSlider(stringResource(R.string.dsp_frequency), band.frequencyHz, 20.0..20_000.0, "Hz") {
                onChange(band.copy(frequencyHz = it))
            }
            DspValueSlider(stringResource(R.string.dsp_q), band.q, 0.1..20.0, "") {
                onChange(band.copy(q = it))
            }
            DspValueSlider(stringResource(R.string.dsp_threshold), band.thresholdDb, -60.0..0.0, "dB") {
                onChange(band.copy(thresholdDb = it))
            }
            DspValueSlider(stringResource(R.string.dsp_ratio), band.ratio, 1.0..20.0, ":1") {
                onChange(band.copy(ratio = it))
            }
            DspValueSlider(stringResource(R.string.dsp_attack), band.attackMs, 0.1..250.0, "ms") {
                onChange(band.copy(attackMs = it))
            }
            DspValueSlider(stringResource(R.string.dsp_release), band.releaseMs, 1.0..500.0, "ms") {
                onChange(band.copy(releaseMs = it))
            }
            DspValueSlider(stringResource(R.string.dsp_dynamic_eq_max_boost), band.maxBoostDb, 0.0..12.0, "dB") {
                onChange(band.copy(maxBoostDb = it))
            }
            DspValueSlider(stringResource(R.string.dsp_dynamic_eq_max_cut), band.maxCutDb, 0.0..12.0, "dB") {
                onChange(band.copy(maxCutDb = it))
            }
        }
    }
}

@Composable
private fun DspSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ChoiceField(title: String, selected: String, choices: List<String>, onSelect: (String) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    Column {
        Text(title)
        BoxWithDropdown(
            expanded = expanded.value,
            onExpandedChange = { expanded.value = it },
            label = selected,
            items = choices,
            itemLabel = { it },
            onSelect = { onSelect(it); expanded.value = false },
        )
    }
}

@Composable
private fun <T> BoxWithDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = { onSelect(item) })
            }
        }
    }
}

private fun profileLabel(context: Context, profile: DspAudioProfile): String = context.getString(
    when (profile.id) {
        "flat" -> R.string.dsp_preset_flat
        "daily" -> R.string.dsp_preset_daily
        "vocal" -> R.string.dsp_preset_vocal
        "bass" -> R.string.dsp_preset_bass
        "highway" -> R.string.dsp_preset_highway
        "night" -> R.string.dsp_preset_night
        "custom1" -> R.string.dsp_preset_custom_1
        "custom2" -> R.string.dsp_preset_custom_2
        else -> R.string.dsp_preset_flat
    },
)

@Composable
private fun dspColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF10243C),
    background = Color(0xFF0C111B),
    surface = Color(0xFF151E2C),
    onSurface = Color(0xFFF1F5FC),
)

private fun showProfileSaveResult(context: Context, result: DspProfileSaveResult) {
    val message = when (result) {
        DspProfileSaveResult.SAVED -> R.string.dsp_profile_saved
        DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED -> R.string.dsp_profile_future_schema
        else -> R.string.dsp_profile_save_failed
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private const val CUSTOM_PROFILE_1 = "custom1"
private const val CUSTOM_PROFILE_2 = "custom2"
