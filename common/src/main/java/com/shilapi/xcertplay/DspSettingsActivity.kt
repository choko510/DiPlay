package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.dsp.DspAudioProfile
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspProfileSaveResult
import com.shilapi.xcertplay.dsp.DspImpulseImportResult
import com.shilapi.xcertplay.dsp.DspCabinCalibrationData
import com.shilapi.xcertplay.dsp.DspCabinMeasurementPoint
import com.shilapi.xcertplay.dsp.DspCabinCalibrationImportResult
import com.shilapi.xcertplay.dsp.DspCabinCalibrationSummary
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsMonitorMode
import com.shilapi.xcertplay.media.dsp.DspAudioMetricsStore
import com.shilapi.xcertplay.media.dsp.DspClipRisk
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCut
import com.shilapi.xcertplay.media.dsp.DspCabinCutSource
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspLiveUpdateReason
import com.shilapi.xcertplay.media.dsp.DspLiveUpdateState
import com.shilapi.xcertplay.media.dsp.DspLiveUpdateStatus
import com.shilapi.xcertplay.media.dsp.DspLiveUpdateStatusStore
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatFocusConfig
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
import com.shilapi.xcertplay.shared.AppLanguage
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

internal enum class EqBandEditorControl {
    FREQUENCY,
    GAIN,
    Q,
}

internal fun eqBandEditorControls(type: DspEqType): Set<EqBandEditorControl> = when (type) {
    DspEqType.PEAK -> setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.GAIN, EqBandEditorControl.Q)
    DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF ->
        setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.GAIN)
    DspEqType.HIGH_PASS, DspEqType.LOW_PASS, DspEqType.NOTCH, DspEqType.ALL_PASS ->
        setOf(EqBandEditorControl.FREQUENCY, EqBandEditorControl.Q)
}

internal fun convolverWetPercentValue(wet: Double): Double = wet * 100.0

internal fun convolverWetModelValue(percent: Double): Double = percent.coerceIn(0.0, 100.0) / 100.0

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
    val liveUpdateStatus = remember { mutableStateOf(DspLiveUpdateStatusStore.snapshot()) }
    val liveAudioMetrics = remember { mutableStateOf(DspAudioMetricsStore.snapshot()) }
    DisposableEffect(runtime) {
        val handler = Handler(Looper.getMainLooper())
        val pollStatus = object : Runnable {
            override fun run() {
                liveUpdateStatus.value = DspLiveUpdateStatusStore.snapshot()
                liveAudioMetrics.value = DspAudioMetricsStore.snapshot()
                handler.postDelayed(this, DSP_STATUS_POLL_INTERVAL_MS)
            }
        }
        handler.post(pollStatus)
        onDispose { handler.removeCallbacks(pollStatus) }
    }
    var profiles by remember(runtime) { mutableStateOf(runtime.availableProfiles()) }
    val profileSaver = remember(runtime) {
        Saver<DspAudioProfile, String>(
            save = { runtime.encodeProfileDraftForSavedState(it) },
            restore = { runtime.decodeProfileDraftFromSavedState(it) ?: runtime.selectedProfile() },
        )
    }
    var profile by rememberSaveable(runtime, stateSaver = profileSaver) { mutableStateOf(runtime.selectedProfile()) }
    var dspEnabled by rememberSaveable { mutableStateOf(runtime.isDspEnabled()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var seatFocusBypassed by remember(profile.id) { mutableStateOf(profile.seatFocus.bypassed) }
    var seatDistanceWarning by rememberSaveable(profile.id) { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var bandTypeDialog by remember { mutableStateOf<Int?>(null) }
    var impulseResponseIds by remember(runtime) { mutableStateOf(runtime.availableImpulseResponseIds()) }
    var cabinCalibrations by remember(runtime) { mutableStateOf(emptyList<DspCabinCalibrationSummary>()) }
    var cabinCalibrationData by remember(profile.cabinCorrection.calibrationId) {
        mutableStateOf<DspCabinCalibrationData?>(null)
    }
    LaunchedEffect(runtime) {
        runtime.availableCabinCalibrationsAsync { cabinCalibrations = it }
    }
    LaunchedEffect(profile.cabinCorrection.calibrationId) {
        val calibrationId = profile.cabinCorrection.calibrationId
        if (calibrationId == null) {
            cabinCalibrationData = null
        } else {
            runtime.cabinCalibrationAsync(calibrationId) { cabinCalibrationData = it }
        }
    }
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
    val openCabinMeasurement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val calibrationId =
                "cal_${UUID.randomUUID().toString().replace("-", "")}"
            runtime.importCabinCalibrationAsync(
                id = calibrationId,
                openInput = { context.contentResolver.openInputStream(uri) },
            ) { result ->
                when (result) {
                    is DspCabinCalibrationImportResult.Imported -> {
                        cabinCalibrations = (cabinCalibrations + result.summary).distinctBy(DspCabinCalibrationSummary::id)
                        profile = profile.copy(
                            cabinCorrection = profile.cabinCorrection.copy(
                                enabled = true,
                                calibrationId = result.summary.id,
                                calibrationVersion = DspCabinCorrectionConfig.CALIBRATION_FORMAT_VERSION,
                                cuts = result.candidates.map { it.toCut() },
                                dynamicControlEnabled = false,
                            ),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.dsp_cabin_imported, result.candidates.size),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    DspCabinCalibrationImportResult.TooLarge ->
                        Toast.makeText(context, R.string.dsp_cabin_too_large, Toast.LENGTH_LONG).show()
                    DspCabinCalibrationImportResult.WriteFailed ->
                        Toast.makeText(context, R.string.dsp_cabin_write_failed, Toast.LENGTH_LONG).show()
                    DspCabinCalibrationImportResult.AlreadyExists ->
                        Toast.makeText(context, R.string.dsp_cabin_import_failed, Toast.LENGTH_LONG).show()
                    DspCabinCalibrationImportResult.InvalidMeasurement ->
                        Toast.makeText(context, R.string.dsp_cabin_invalid, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    DisposableEffect(runtime) {
        onDispose {
            val activeProfile = runtime.selectedProfile()
            if (activeProfile.seatFocus.bypassed) {
                runtime.applyAsync(
                    activeProfile.copy(seatFocus = activeProfile.seatFocus.copy(bypassed = false)),
                    runtime.isDspEnabled(),
                ) { }
            }
            if (activeProfile.advancedMs.monitorMode != DspAdvancedMsMonitorMode.NORMAL) {
                runtime.applyAsync(
                    activeProfile.copy(
                        advancedMs = activeProfile.advancedMs.copy(
                            monitorMode = DspAdvancedMsMonitorMode.NORMAL,
                        ),
                    ),
                    runtime.isDspEnabled(),
                ) { }
            }
            if (activeProfile.cabinCorrection.bypassed) {
                runtime.applyAsync(
                    activeProfile.copy(
                        cabinCorrection = activeProfile.cabinCorrection.copy(bypassed = false),
                    ),
                    runtime.isDspEnabled(),
                ) { }
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
                            runtime.setMasterEnabledAsync(enabled) { }
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
                            seatFocusBypassed = selected.seatFocus.bypassed
                            seatDistanceWarning = false
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
                SectionCard(title = stringResource(R.string.dsp_seat_focus)) {
                    val seatFocus = profile.seatFocus
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_focus_enabled),
                        description = stringResource(R.string.dsp_seat_focus_enabled_description),
                        checked = seatFocus.enabled,
                        onCheckedChange = { enabled ->
                            profile = profile.copy(seatFocus = seatFocus.copy(enabled = enabled, bypassed = false))
                            seatFocusBypassed = false
                        },
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_seat_preset),
                        selected = seatPresetLabel(context, seatFocus.preset),
                        choices = DspSeatPreset.entries.map { seatPresetLabel(context, it) },
                        onSelect = { selected ->
                            val preset = DspSeatPreset.entries.first { seatPresetLabel(context, it) == selected }
                            profile = profile.copy(seatFocus = seatFocus.copy(preset = preset))
                        },
                    )
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_distance_mode),
                        description = stringResource(R.string.dsp_seat_distance_mode_description),
                        checked = seatFocus.distanceMode,
                        onCheckedChange = { enabled ->
                            val leftDistance = seatFocus.leftDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM
                            val rightDistance = seatFocus.rightDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM
                            val measured = DspSeatFocusConfig.calculateDistanceDelays(leftDistance, rightDistance)
                            if (enabled && !measured.withinSupportedRange) {
                                seatDistanceWarning = true
                            } else {
                                seatDistanceWarning = false
                                profile = profile.copy(
                                    seatFocus = seatFocus.copy(
                                        distanceMode = enabled,
                                        leftDistanceCm = leftDistance,
                                        rightDistanceCm = rightDistance,
                                    ),
                                )
                            }
                        },
                    )
                    if (seatFocus.distanceMode) {
                        DspValueSlider(
                            title = stringResource(R.string.dsp_seat_left_distance),
                            value = seatFocus.leftDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM,
                            range = DspSeatFocusConfig.MIN_DISTANCE_CM..DspSeatFocusConfig.MAX_DISTANCE_CM,
                            unit = "cm",
                            decimals = 0,
                        ) { value ->
                            val left = value
                            val right = seatFocus.rightDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM
                            val delays = DspSeatFocusConfig.calculateDistanceDelays(left, right)
                            if (delays.withinSupportedRange) {
                                profile = profile.copy(
                                    seatFocus = seatFocus.copy(leftDistanceCm = left, rightDistanceCm = right),
                                )
                                seatDistanceWarning = false
                            } else {
                                seatDistanceWarning = true
                            }
                        }
                        DspValueSlider(
                            title = stringResource(R.string.dsp_seat_right_distance),
                            value = seatFocus.rightDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM,
                            range = DspSeatFocusConfig.MIN_DISTANCE_CM..DspSeatFocusConfig.MAX_DISTANCE_CM,
                            unit = "cm",
                            decimals = 0,
                        ) { value ->
                            val left = seatFocus.leftDistanceCm ?: DEFAULT_SEAT_DISTANCE_CM
                            val right = value
                            val delays = DspSeatFocusConfig.calculateDistanceDelays(left, right)
                            if (delays.withinSupportedRange) {
                                profile = profile.copy(
                                    seatFocus = seatFocus.copy(leftDistanceCm = left, rightDistanceCm = right),
                                )
                                seatDistanceWarning = false
                            } else {
                                seatDistanceWarning = true
                            }
                        }
                        val effectiveDelays = seatFocus.effectiveDelays()
                        Text(
                            stringResource(
                                R.string.dsp_seat_effective_delays,
                                effectiveDelays.leftDelayMs,
                                effectiveDelays.rightDelayMs,
                            ),
                        )
                    } else {
                        DspValueSlider(
                            title = stringResource(R.string.dsp_seat_left_delay),
                            value = seatFocus.leftDelayMs,
                            range = 0.0..DspSeatFocusConfig.MAX_DELAY_MS,
                            unit = "ms",
                            decimals = 2,
                        ) { value ->
                            profile = profile.copy(seatFocus = seatFocus.copy(leftDelayMs = value))
                        }
                        DspValueSlider(
                            title = stringResource(R.string.dsp_seat_right_delay),
                            value = seatFocus.rightDelayMs,
                            range = 0.0..DspSeatFocusConfig.MAX_DELAY_MS,
                            unit = "ms",
                            decimals = 2,
                        ) { value ->
                            profile = profile.copy(seatFocus = seatFocus.copy(rightDelayMs = value))
                        }
                    }
                    DspValueSlider(stringResource(R.string.dsp_seat_left_trim), seatFocus.leftTrimDb, -6.0..6.0, "dB", decimals = 1) {
                        profile = profile.copy(seatFocus = seatFocus.copy(leftTrimDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_seat_right_trim), seatFocus.rightTrimDb, -6.0..6.0, "dB", decimals = 1) {
                        profile = profile.copy(seatFocus = seatFocus.copy(rightTrimDb = it))
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_invert_left),
                        description = stringResource(R.string.dsp_seat_invert_warning),
                        checked = seatFocus.invertLeft,
                        onCheckedChange = { profile = profile.copy(seatFocus = seatFocus.copy(invertLeft = it)) },
                    )
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_invert_right),
                        description = stringResource(R.string.dsp_seat_invert_warning),
                        checked = seatFocus.invertRight,
                        onCheckedChange = { profile = profile.copy(seatFocus = seatFocus.copy(invertRight = it)) },
                    )
                    if (seatFocus.enabled) {
                        DspSwitchRow(
                            title = stringResource(R.string.dsp_seat_ab_bypass),
                            description = stringResource(R.string.dsp_seat_ab_bypass_description),
                            checked = seatFocusBypassed,
                            onCheckedChange = { bypassed ->
                                seatFocusBypassed = bypassed
                                val updatedProfile = profile.copy(
                                    seatFocus = profile.seatFocus.copy(bypassed = bypassed),
                                )
                                profile = updatedProfile
                                runtime.applyAsync(updatedProfile, dspEnabled) { }
                            },
                        )
                    }
                    if (seatDistanceWarning) {
                        Text(stringResource(R.string.dsp_seat_distance_warning), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.dsp_seat_focus_live_note))
                    Text(stringResource(R.string.dsp_seat_focus_routing_note))
                    Text(stringResource(dspLiveUpdateStatusMessage(liveUpdateStatus.value)))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            profile = profile.copy(
                                seatFocus = DspSeatFocusConfig(enabled = seatFocus.enabled),
                            )
                            seatFocusBypassed = false
                            seatDistanceWarning = false
                        },
                    ) { Text(stringResource(R.string.dsp_seat_reset)) }
                }
                SectionCard(title = stringResource(R.string.dsp_advanced_ms)) {
                    val advancedMs = profile.advancedMs
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_advanced_ms_enabled),
                        description = stringResource(R.string.dsp_advanced_ms_enabled_description),
                        checked = advancedMs.enabled,
                        onCheckedChange = { enabled ->
                            profile = profile.copy(advancedMs = advancedMs.copy(enabled = enabled))
                        },
                    )
                    if (advancedMs.enabled) {
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_mid_gain),
                            advancedMs.midGainDb,
                            -6.0..6.0,
                            "dB",
                            decimals = 1,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(midGainDb = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_side_gain),
                            advancedMs.sideGainDb,
                            -6.0..6.0,
                            "dB",
                            decimals = 1,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(sideGainDb = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_low_width),
                            advancedMs.lowWidth,
                            0.0..1.5,
                            "×",
                            decimals = 2,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(lowWidth = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_mid_width),
                            advancedMs.midWidth,
                            0.0..1.5,
                            "×",
                            decimals = 2,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(midWidth = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_high_width),
                            advancedMs.highWidth,
                            0.0..1.5,
                            "×",
                            decimals = 2,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(highWidth = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_low_mid_crossover),
                            advancedMs.lowMidCrossoverHz,
                            DspAdvancedMsConfig.MIN_LOW_MID_CROSSOVER_HZ..
                                DspAdvancedMsConfig.MAX_LOW_MID_CROSSOVER_HZ,
                            "Hz",
                            decimals = 0,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(lowMidCrossoverHz = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_mid_high_crossover),
                            advancedMs.midHighCrossoverHz,
                            DspAdvancedMsConfig.MIN_MID_HIGH_CROSSOVER_HZ..
                                DspAdvancedMsConfig.MAX_MID_HIGH_CROSSOVER_HZ,
                            "Hz",
                            decimals = 0,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(midHighCrossoverHz = value))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_advanced_ms_center_focus),
                            advancedMs.centerFocusDb,
                            -3.0..3.0,
                            "dB",
                            decimals = 1,
                        ) { value ->
                            profile = profile.copy(advancedMs = advancedMs.copy(centerFocusDb = value))
                        }
                        Text(stringResource(R.string.dsp_advanced_ms_width_note))
                        Text(stringResource(R.string.dsp_advanced_ms_center_note))
                        Text(stringResource(dspLiveUpdateStatusMessage(liveUpdateStatus.value)))
                        ChoiceField(
                            title = stringResource(R.string.dsp_advanced_ms_monitor),
                            selected = advancedMsMonitorLabel(context, advancedMs.monitorMode),
                            choices = DspAdvancedMsMonitorMode.entries.map { advancedMsMonitorLabel(context, it) },
                            onSelect = { selected ->
                                val mode = DspAdvancedMsMonitorMode.entries.first {
                                    advancedMsMonitorLabel(context, it) == selected
                                }
                                val updated = profile.copy(
                                    advancedMs = profile.advancedMs.copy(monitorMode = mode),
                                )
                                profile = updated
                                runtime.applyAsync(updated, dspEnabled) { }
                            },
                        )
                        Text(stringResource(R.string.dsp_advanced_ms_monitor_note))
                        Text(stringResource(R.string.dsp_advanced_ms_mid_eq))
                        advancedMs.midEqBands.forEachIndexed { index, band ->
                            AdvancedMsEqBandEditor(
                                band = band,
                                bankLabel = stringResource(R.string.dsp_advanced_ms_mid_eq),
                                index = index,
                                onChange = { changed ->
                                    val bands = profile.advancedMs.midEqBands.toMutableList().apply {
                                        set(index, changed)
                                    }
                                    profile = profile.copy(advancedMs = profile.advancedMs.copy(midEqBands = bands))
                                },
                                onRemove = {
                                    val bands = profile.advancedMs.midEqBands.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    profile = profile.copy(advancedMs = profile.advancedMs.copy(midEqBands = bands))
                                },
                            )
                        }
                        if (advancedMs.midEqBands.size < DspAdvancedMsConfig.MAX_EQ_BANDS) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    profile = profile.copy(
                                        advancedMs = profile.advancedMs.copy(
                                            midEqBands = profile.advancedMs.midEqBands +
                                                DspEqBand(DspEqType.PEAK, 1_000.0, q = 1.0, enabled = true),
                                        ),
                                    )
                                },
                            ) { Text(stringResource(R.string.dsp_advanced_ms_add_mid_band)) }
                        }
                        Text(stringResource(R.string.dsp_advanced_ms_side_eq))
                        advancedMs.sideEqBands.forEachIndexed { index, band ->
                            AdvancedMsEqBandEditor(
                                band = band,
                                bankLabel = stringResource(R.string.dsp_advanced_ms_side_eq),
                                index = index,
                                onChange = { changed ->
                                    val bands = profile.advancedMs.sideEqBands.toMutableList().apply {
                                        set(index, changed)
                                    }
                                    profile = profile.copy(advancedMs = profile.advancedMs.copy(sideEqBands = bands))
                                },
                                onRemove = {
                                    val bands = profile.advancedMs.sideEqBands.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    profile = profile.copy(advancedMs = profile.advancedMs.copy(sideEqBands = bands))
                                },
                            )
                        }
                        if (advancedMs.sideEqBands.size < DspAdvancedMsConfig.MAX_EQ_BANDS) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    profile = profile.copy(
                                        advancedMs = profile.advancedMs.copy(
                                            sideEqBands = profile.advancedMs.sideEqBands +
                                                DspEqBand(DspEqType.PEAK, 1_000.0, q = 1.0, enabled = true),
                                        ),
                                    )
                                },
                            ) { Text(stringResource(R.string.dsp_advanced_ms_add_side_band)) }
                        }
                    }
                    Text(
                        stringResource(
                            R.string.dsp_advanced_ms_correlation,
                            liveAudioMetrics.value.inputCorrelation,
                            liveAudioMetrics.value.outputCorrelation,
                        ),
                    )
                    Text(
                        stringResource(
                            when (liveAudioMetrics.value.clipRisk) {
                                DspClipRisk.LOW -> R.string.dsp_advanced_ms_clip_low
                                DspClipRisk.ELEVATED -> R.string.dsp_advanced_ms_clip_elevated
                                DspClipRisk.HIGH -> R.string.dsp_advanced_ms_clip_high
                            },
                            liveAudioMetrics.value.outputPeak,
                        ),
                    )
                }
                SectionCard(title = stringResource(R.string.dsp_cabin_correction)) {
                    val cabin = profile.cabinCorrection
                    val calibrationId = cabin.calibrationId
                    val selectedCalibration = cabinCalibrations.firstOrNull { it.id == calibrationId }
                    val enabledMeasurementCutExists = cabin.cuts.any {
                        it.enabled && it.source == DspCabinCutSource.MEASUREMENT
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_cabin_enabled),
                        checked = cabin.enabled,
                        onCheckedChange = { profile = profile.copy(cabinCorrection = cabin.copy(enabled = it)) },
                    )
                    Text(stringResource(R.string.dsp_cabin_import_format))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            openCabinMeasurement.launch(arrayOf("text/*", "application/json"))
                        },
                    ) { Text(stringResource(R.string.dsp_cabin_import)) }
                    if (calibrationId != null) {
                        if (selectedCalibration == null) {
                            Text(
                                stringResource(R.string.dsp_cabin_calibration_missing, calibrationId),
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                stringResource(
                                    R.string.dsp_cabin_calibration_active,
                                    selectedCalibration.id,
                                    selectedCalibration.position,
                                    selectedCalibration.measuredAt,
                                    selectedCalibration.sampleRate,
                                    selectedCalibration.pointCount,
                                    stringResource(
                                        if (selectedCalibration.microphoneCorrectionApplied) {
                                            R.string.dsp_cabin_mic_correction_applied
                                        } else {
                                            R.string.dsp_cabin_mic_correction_not_applied
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                    val measurement = cabinCalibrationData?.takeIf { it.id == cabin.calibrationId }
                    if (measurement != null) {
                        DspCabinResponsePreview(measurement, cabin.cuts)
                    }
                    if (cabin.enabled && cabin.cuts.any(DspCabinCut::enabled)) {
                        DspSwitchRow(
                            title = stringResource(R.string.dsp_cabin_ab_bypass),
                            description = stringResource(R.string.dsp_cabin_ab_bypass_note),
                            checked = cabin.bypassed,
                            onCheckedChange = { bypassed ->
                                val updated = profile.copy(
                                    cabinCorrection = profile.cabinCorrection.copy(bypassed = bypassed),
                                )
                                profile = updated
                                runtime.applyAsync(updated, dspEnabled) { }
                            },
                        )
                    }
                    Text(stringResource(R.string.dsp_cabin_cut_list))
                    cabin.cuts.forEachIndexed { index, cut ->
                        DspCabinCutEditor(
                            cut = cut,
                            index = index,
                            onChange = { changed ->
                                val cuts = profile.cabinCorrection.cuts.toMutableList().apply {
                                    set(index, changed)
                                }
                                val dynamicEnabled = profile.cabinCorrection.dynamicControlEnabled && cuts.any {
                                    it.enabled && it.source == DspCabinCutSource.MEASUREMENT
                                }
                                profile = profile.copy(
                                    cabinCorrection = profile.cabinCorrection.copy(
                                        cuts = cuts,
                                        dynamicControlEnabled = dynamicEnabled,
                                    ),
                                )
                            },
                            onRemove = {
                                val cuts = profile.cabinCorrection.cuts.toMutableList().apply {
                                    removeAt(index)
                                }
                                val dynamicEnabled = cuts.any {
                                    it.enabled && it.source == DspCabinCutSource.MEASUREMENT
                                } && profile.cabinCorrection.dynamicControlEnabled
                                profile = profile.copy(
                                    cabinCorrection = profile.cabinCorrection.copy(
                                        cuts = cuts,
                                        dynamicControlEnabled = dynamicEnabled,
                                    ),
                                )
                            },
                        )
                    }
                    if (cabin.cuts.size < DspCabinCorrectionConfig.MAX_CUTS) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                profile = profile.copy(
                                    cabinCorrection = profile.cabinCorrection.copy(
                                        cuts = profile.cabinCorrection.cuts +
                                            DspCabinCut(80.0, 4.0, -3.0, source = DspCabinCutSource.MANUAL),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.dsp_cabin_add_manual_cut)) }
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_cabin_dynamic_control),
                        description = stringResource(R.string.dsp_cabin_dynamic_description),
                        checked = cabin.dynamicControlEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled || enabledMeasurementCutExists && cabin.calibrationId != null) {
                                profile = profile.copy(
                                    cabinCorrection = cabin.copy(dynamicControlEnabled = enabled),
                                )
                            } else {
                                Toast.makeText(context, R.string.dsp_cabin_dynamic_requires_candidate, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                    if (cabin.dynamicControlEnabled) {
                        DspValueSlider(
                            stringResource(R.string.dsp_cabin_max_dynamic_cut),
                            cabin.maxDynamicCutDb,
                            0.0..3.0,
                            "dB",
                            decimals = 1,
                        ) { value ->
                            profile = profile.copy(
                                cabinCorrection = cabin.copy(maxDynamicCutDb = value),
                            )
                        }
                    }
                    Text(stringResource(R.string.dsp_cabin_guided_unavailable))
                    Text(stringResource(R.string.dsp_cabin_parked_note))
                    Text(stringResource(dspLiveUpdateStatusMessage(liveUpdateStatus.value)))
                }
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
                    DspValueSlider(
                        stringResource(R.string.dsp_wet),
                        convolverWetPercentValue(profile.convolver.wet),
                        0.0..100.0,
                        "%",
                    ) {
                        profile = profile.copy(
                            convolver = profile.convolver.copy(wet = convolverWetModelValue(it)),
                        )
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_diagnostics)) {
                    Text(stringResource(R.string.dsp_current_profile, profileLabel(context, profile)))
                    Text(stringResource(R.string.dsp_dsp_state, if (dspEnabled) "ON" else "OFF"))
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !seatDistanceWarning,
                onClick = {
                    val applied = profile.copy(convolver = profile.convolver.copy(impulseResponse = null))
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
                    enabled = !seatDistanceWarning,
                    onClick = {
                        val custom = profile.copy(id = CUSTOM_PROFILE_1, name = context.getString(R.string.dsp_preset_custom_1))
                        runtime.saveCustomAsync(custom, dspEnabled) { result ->
                            if (result == DspProfileSaveResult.SAVED) profile = custom
                            if (result == DspProfileSaveResult.SAVED) profiles = runtime.availableProfiles()
                            showProfileSaveResult(context, result)
                        }
                    },
                ) { Text(stringResource(R.string.dsp_save_custom_1)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !seatDistanceWarning,
                    onClick = {
                        val custom = profile.copy(id = CUSTOM_PROFILE_2, name = context.getString(R.string.dsp_preset_custom_2))
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
            val controls = eqBandEditorControls(band.type)
            if (EqBandEditorControl.FREQUENCY in controls) {
                DspValueSlider(stringResource(R.string.dsp_frequency), band.frequencyHz, 20.0..19_800.0, "Hz") {
                    onChange(band.copy(frequencyHz = it))
                }
            }
            if (EqBandEditorControl.GAIN in controls) {
                DspValueSlider(stringResource(R.string.dsp_gain), band.gainDb, -18.0..18.0, "dB") {
                    onChange(band.copy(gainDb = it))
                }
            }
            if (EqBandEditorControl.Q in controls) {
                DspValueSlider(stringResource(R.string.dsp_q), band.q, 0.1..20.0, "") {
                    onChange(band.copy(q = it))
                }
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
    decimals: Int = 1,
    onChange: (Double) -> Unit,
) {
    Column {
        val formatted = String.format(Locale.getDefault(), "%.${decimals}f", value)
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
            DspValueSlider(
                stringResource(R.string.dsp_frequency),
                band.frequencyHz,
                20.0..DspDynamicEqConfig.UI_MAX_FREQUENCY_HZ,
                "Hz",
            ) {
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
private fun DspCabinCutEditor(
    cut: DspCabinCut,
    index: Int,
    onChange: (DspCabinCut) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = if (cut.source == DspCabinCutSource.MEASUREMENT) {
                stringResource(
                    R.string.dsp_cabin_measurement_cut_title,
                    index + 1,
                    cut.frequencyHz,
                    cut.q,
                    cut.prominenceDb,
                    cut.confidence * 100.0,
                )
            } else {
                stringResource(R.string.dsp_cabin_manual_cut_title, index + 1)
            }
            DspSwitchRow(
                title = title,
                description = if (cut.source == DspCabinCutSource.MEASUREMENT) {
                    stringResource(R.string.dsp_cabin_measurement_cut_note)
                } else {
                    stringResource(R.string.dsp_cabin_manual_cut_note)
                },
                checked = cut.enabled,
                onCheckedChange = { onChange(cut.copy(enabled = it)) },
            )
            DspValueSlider(
                stringResource(R.string.dsp_cabin_cut_frequency),
                cut.frequencyHz,
                40.0..250.0,
                "Hz",
                decimals = 0,
            ) { onChange(cut.copy(frequencyHz = it)) }
            DspValueSlider(
                stringResource(R.string.dsp_cabin_cut_q),
                cut.q,
                0.5..8.0,
                "",
                decimals = 1,
            ) { onChange(cut.copy(q = it)) }
            DspValueSlider(
                stringResource(R.string.dsp_cabin_cut_reduction),
                cut.reductionDb,
                -6.0..0.0,
                "dB",
                decimals = 1,
            ) { onChange(cut.copy(reductionDb = it)) }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRemove) {
                Text(stringResource(R.string.dsp_cabin_remove_cut))
            }
        }
    }
}

@Composable
private fun DspCabinResponsePreview(
    calibration: DspCabinCalibrationData,
    cuts: List<DspCabinCut>,
) {
    val measuredPoints = calibration.points.filter { it.frequencyHz in 40.0..250.0 }
    val stride = maxOf(1, kotlin.math.ceil(measuredPoints.size / 600.0).toInt())
    val points = measuredPoints.filterIndexed { index, _ -> index % stride == 0 || index == measuredPoints.lastIndex }
    if (points.size < 2) return
    val activeCuts = cuts.filter(DspCabinCut::enabled)
    val beforeValues = points.map(DspCabinMeasurementPoint::magnitudeDb)
    val afterValues = points.map { point ->
        point.magnitudeDb + activeCuts.sumOf { cut ->
            cabinPeakFilterGainDb(calibration.sampleRate, cut, point.frequencyHz)
        }
    }
    val minimumDb = minOf(beforeValues.min(), afterValues.min()) - 1.0
    val maximumDb = maxOf(beforeValues.max(), afterValues.max()) + 1.0
    val rangeDb = maxOf(1.0, maximumDb - minimumDb)
    val beforeColor = Color(0xFF4AA3FF)
    val afterColor = Color(0xFFFFB74D)
    Text(stringResource(R.string.dsp_cabin_response_preview))
    Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
        val before = Path()
        val after = Path()
        points.forEachIndexed { index, point ->
            val x = (kotlin.math.ln(point.frequencyHz / 40.0) / kotlin.math.ln(250.0 / 40.0) * size.width).toFloat()
            val beforeY = ((maximumDb - beforeValues[index]) / rangeDb * size.height).toFloat()
            val afterY = ((maximumDb - afterValues[index]) / rangeDb * size.height).toFloat()
            if (index == 0) {
                before.moveTo(x, beforeY)
                after.moveTo(x, afterY)
            } else {
                before.lineTo(x, beforeY)
                after.lineTo(x, afterY)
            }
        }
        drawPath(before, beforeColor, style = Stroke(width = 2.dp.toPx()))
        drawPath(after, afterColor, style = Stroke(width = 2.dp.toPx()))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.dsp_cabin_preview_before), color = beforeColor)
        Text(stringResource(R.string.dsp_cabin_preview_after_static), color = afterColor)
    }
}

private fun cabinPeakFilterGainDb(
    sampleRate: Int,
    cut: DspCabinCut,
    frequencyHz: Double,
): Double {
    val omega = 2.0 * kotlin.math.PI * cut.frequencyHz / sampleRate
    val cosine = kotlin.math.cos(omega)
    val sine = kotlin.math.sin(omega)
    val alpha = sine / (2.0 * cut.q)
    val amplitude = 10.0.pow(cut.reductionDb / 40.0)
    val cosineAt = kotlin.math.cos(2.0 * kotlin.math.PI * frequencyHz / sampleRate)
    val sineAt = kotlin.math.sin(2.0 * kotlin.math.PI * frequencyHz / sampleRate)
    val cosineAt2 = kotlin.math.cos(4.0 * kotlin.math.PI * frequencyHz / sampleRate)
    val sineAt2 = kotlin.math.sin(4.0 * kotlin.math.PI * frequencyHz / sampleRate)
    val b0 = 1.0 + alpha * amplitude
    val b1 = -2.0 * cosine
    val b2 = 1.0 - alpha * amplitude
    val a0 = 1.0 + alpha / amplitude
    val a1 = -2.0 * cosine
    val a2 = 1.0 - alpha / amplitude
    val numerator = kotlin.math.hypot(b0 + b1 * cosineAt + b2 * cosineAt2, -(b1 * sineAt + b2 * sineAt2))
    val denominator = kotlin.math.hypot(a0 + a1 * cosineAt + a2 * cosineAt2, -(a1 * sineAt + a2 * sineAt2))
    return 20.0 * kotlin.math.log10(numerator / denominator)
}

@Composable
private fun AdvancedMsEqBandEditor(
    band: DspEqBand,
    bankLabel: String,
    index: Int,
    onChange: (DspEqBand) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$bankLabel ${index + 1}")
            DspSwitchRow(
                title = stringResource(R.string.dsp_advanced_ms_band_enabled),
                checked = band.enabled,
                onCheckedChange = { onChange(band.copy(enabled = it)) },
            )
            ChoiceField(
                title = stringResource(R.string.dsp_eq_type),
                selected = band.type.name,
                choices = DspEqType.entries.map { it.name },
                onSelect = { selected ->
                    onChange(band.copy(type = DspEqType.entries.first { it.name == selected }))
                },
            )
            val controls = eqBandEditorControls(band.type)
            if (EqBandEditorControl.FREQUENCY in controls) {
                DspValueSlider(
                    stringResource(R.string.dsp_frequency),
                    band.frequencyHz,
                    20.0..20_000.0,
                    "Hz",
                    decimals = 0,
                ) { onChange(band.copy(frequencyHz = it)) }
            }
            if (EqBandEditorControl.GAIN in controls) {
                DspValueSlider(
                    stringResource(R.string.dsp_gain),
                    band.gainDb,
                    -6.0..6.0,
                    "dB",
                    decimals = 1,
                ) { onChange(band.copy(gainDb = it)) }
            }
            if (EqBandEditorControl.Q in controls) {
                DspValueSlider(
                    stringResource(R.string.dsp_q),
                    band.q,
                    0.1..8.0,
                    "",
                    decimals = 1,
                ) { onChange(band.copy(q = it)) }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRemove) {
                Text(stringResource(R.string.dsp_advanced_ms_remove_band))
            }
        }
    }
}

private fun advancedMsMonitorLabel(context: Context, mode: DspAdvancedMsMonitorMode): String = context.getString(
    when (mode) {
        DspAdvancedMsMonitorMode.NORMAL -> R.string.dsp_advanced_ms_monitor_normal
        DspAdvancedMsMonitorMode.MID_ONLY -> R.string.dsp_advanced_ms_monitor_mid
        DspAdvancedMsMonitorMode.SIDE_ONLY -> R.string.dsp_advanced_ms_monitor_side
        DspAdvancedMsMonitorMode.MONO_FOLD_DOWN -> R.string.dsp_advanced_ms_monitor_mono
    },
)

private fun seatPresetLabel(context: Context, preset: DspSeatPreset): String = context.getString(
    when (preset) {
        DspSeatPreset.CUSTOM -> R.string.dsp_seat_preset_custom
        DspSeatPreset.DRIVER -> R.string.dsp_seat_preset_driver
        DspSeatPreset.PASSENGER -> R.string.dsp_seat_preset_passenger
        DspSeatPreset.CENTER -> R.string.dsp_seat_preset_center
    },
)

private fun dspLiveUpdateStatusMessage(status: DspLiveUpdateStatus): Int = when (status.state) {
    DspLiveUpdateState.IDLE -> R.string.dsp_update_idle
    DspLiveUpdateState.PREPARING -> R.string.dsp_update_preparing
    DspLiveUpdateState.PREPARED -> R.string.dsp_update_prepared
    DspLiveUpdateState.APPLIED -> R.string.dsp_update_applied
    DspLiveUpdateState.DEFERRED -> when (status.reason) {
        DspLiveUpdateReason.STATEFUL_HISTORY -> R.string.dsp_update_deferred_stateful
        else -> R.string.dsp_update_deferred_latency
    }
    DspLiveUpdateState.FAILED -> when (status.reason) {
        DspLiveUpdateReason.PROCESS_FAILED -> R.string.dsp_update_failed_process
        else -> R.string.dsp_update_failed_prepare
    }
    DspLiveUpdateState.DISABLED -> R.string.dsp_update_disabled
}

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
private const val DEFAULT_SEAT_DISTANCE_CM = 100.0
private const val DSP_STATUS_POLL_INTERVAL_MS = 500L
