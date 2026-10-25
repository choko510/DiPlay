package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.dsp.DspAudioProfile
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspProfileSaveResult
import com.shilapi.xcertplay.dsp.DspImpulseImportResult
import com.shilapi.xcertplay.dsp.DspEditorOperationKind
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionNotch
import com.shilapi.xcertplay.media.dsp.DspCabinMeasurementParseResult
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspHarmonicMode
import com.shilapi.xcertplay.media.dsp.DspHarmonicQuality
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatAlignmentConfig
import com.shilapi.xcertplay.media.dsp.DspSeatNearSpeaker
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.media.dsp.DspRendererApplyWarning
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

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

internal enum class DspPresentationMode {
    FULL,
    COMPACT_SPLIT,
}

private const val RENDERER_APPLY_STATUS_POLL_INTERVAL_MS = 500L

private enum class DspCompactSection {
    BASIC,
    EQ,
    ADVANCED,
}

@Composable
private fun rendererApplyStatusText(status: DspRendererApplyStatus?): String = when (status?.phase) {
    null -> stringResource(R.string.dsp_renderer_apply_waiting)
    DspRendererApplyPhase.REQUESTED -> stringResource(R.string.dsp_renderer_apply_requested, status.generation)
    DspRendererApplyPhase.PREPARING -> stringResource(R.string.dsp_renderer_apply_preparing, status.generation)
    DspRendererApplyPhase.PREPARED -> stringResource(R.string.dsp_renderer_apply_prepared, status.generation)
    DspRendererApplyPhase.ACTIVE -> if (status.updatesBlocked) {
        stringResource(
            R.string.dsp_renderer_apply_retirement_failed,
            status.retiredGeneration?.toString() ?: stringResource(R.string.dsp_renderer_apply_no_active),
            status.retiredPipelineId?.toString() ?: stringResource(R.string.dsp_renderer_apply_no_active),
            status.activeGeneration?.toString() ?: stringResource(R.string.dsp_renderer_apply_no_active),
        )
    } else {
        stringResource(R.string.dsp_renderer_apply_active, status.generation)
    }
    DspRendererApplyPhase.DEFERRED -> stringResource(
        R.string.dsp_renderer_apply_deferred,
        status.generation,
        status.activeGeneration?.toString() ?: stringResource(R.string.dsp_renderer_apply_no_active),
    )
    DspRendererApplyPhase.FAILED -> stringResource(R.string.dsp_renderer_apply_failed, status.generation)
}

@Composable
private fun rendererApplyWarningText(status: DspRendererApplyStatus?): String? = when (status?.warning) {
    null -> null
    DspRendererApplyWarning.CONVOLVER_IR_UNAVAILABLE ->
        stringResource(R.string.dsp_renderer_apply_convolver_ir_unavailable)
    DspRendererApplyWarning.CONVOLVER_SAMPLE_RATE_MISMATCH ->
        stringResource(R.string.dsp_renderer_apply_convolver_rate_mismatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DspSettingsScreen(
    runtime: DspProfileRuntime,
    presentation: DspPresentationMode = DspPresentationMode.FULL,
    isVisible: Boolean = true,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val screenActive = remember { AtomicBoolean(true) }
    DisposableEffect(screenActive) {
        onDispose { screenActive.set(false) }
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val leaveEditor = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onBack()
    }
    val editorSession = remember(runtime) { runtime.editorSession }
    val operationPending = editorSession.pendingOperation != null
    var profiles by remember(runtime) { mutableStateOf(runtime.availableProfiles()) }
    var profile by remember(editorSession) { editorSession.draftProfileState }
    var dspEnabled by remember(editorSession) { editorSession.masterEnabledMutableState }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var compactSection by rememberSaveable { mutableStateOf(DspCompactSection.BASIC) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var bandTypeDialog by remember { mutableStateOf<Int?>(null) }
    var impulseResponseIds by remember(runtime) { mutableStateOf(runtime.availableImpulseResponseIds()) }
    var rendererApplyStatus by remember(runtime) { mutableStateOf(runtime.configProvider.rendererApplyStatus()) }
    LaunchedEffect(runtime, isVisible) {
        if (isVisible) {
            while (true) {
                rendererApplyStatus = runtime.configProvider.rendererApplyStatus()
                delay(RENDERER_APPLY_STATUS_POLL_INTERVAL_MS)
            }
        }
    }
    val openImpulseResponse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val operation = editorSession.beginOperation(DspEditorOperationKind.IMPORT)
            if (operation != null) {
                val sourceProfileId = profile.id
                val irId = "ir_${UUID.randomUUID().toString().replace("-", "")}"
                runtime.importImpulseResponseAsync(
                    id = irId,
                    openInput = { context.contentResolver.openInputStream(uri) },
                ) { result ->
                    val finished = editorSession.finishOperation(operation, runtime.selectedProfile(), runtime.isDspEnabled())
                    var adopted = false
                    if (finished && screenActive.get() && profile.id == sourceProfileId) {
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
                                adopted = true
                                Toast.makeText(context, R.string.dsp_ir_imported, Toast.LENGTH_SHORT).show()
                            }
                            DspImpulseImportResult.TooLarge -> Toast.makeText(context, R.string.dsp_ir_too_large, Toast.LENGTH_LONG).show()
                            DspImpulseImportResult.WriteFailed -> Toast.makeText(context, R.string.dsp_ir_write_failed, Toast.LENGTH_LONG).show()
                            else -> Toast.makeText(context, R.string.dsp_ir_invalid, Toast.LENGTH_LONG).show()
                        }
                    }
                    if (result is DspImpulseImportResult.Imported && !adopted) {
                        runtime.discardImportedImpulseResponse(result.impulseResponse.id)
                        if (screenActive.get()) impulseResponseIds = runtime.availableImpulseResponseIds()
                    }
                }
            }
        }
    }
    val openCabinMeasurement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val operation = editorSession.beginOperation(DspEditorOperationKind.IMPORT)
            if (operation != null) {
                val sourceProfileId = profile.id
                runtime.importCabinMeasurementCsvAsync(
                    openInput = { context.contentResolver.openInputStream(uri) },
                ) { result ->
                    val finished = editorSession.finishOperation(operation, runtime.selectedProfile(), runtime.isDspEnabled())
                    if (finished && screenActive.get() && profile.id == sourceProfileId) {
                        when (result) {
                            is DspCabinMeasurementParseResult.Available -> {
                                profile = profile.copy(cabinCorrection = result.correction)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.dsp_cabin_measurement_imported, result.suggestedNotchCount),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            is DspCabinMeasurementParseResult.CalibrationUnavailable -> {
                                Toast.makeText(context, R.string.dsp_cabin_unavailable, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    val saveCustomProfile: (String, Int) -> Unit = { id, nameResource ->
        val custom = profile.copy(id = id, name = context.getString(nameResource))
        val operation = editorSession.beginOperation(DspEditorOperationKind.SAVE)
        if (operation != null) {
            runtime.saveCustomAsync(custom, dspEnabled) { result ->
                if (editorSession.finishOperation(
                        operation,
                        runtime.selectedProfile(),
                        runtime.isDspEnabled(),
                        acceptAppliedDraft = result == DspProfileSaveResult.SAVED,
                    )
                ) {
                    if (screenActive.get()) {
                        if (result == DspProfileSaveResult.SAVED) profiles = runtime.availableProfiles()
                        showProfileSaveResult(context, result)
                    }
                }
            }
        }
    }
    val applyChanges: () -> Unit = {
        val applied = profile.copy(convolver = profile.convolver.copy(impulseResponse = null))
        if (applied.id == CUSTOM_PROFILE_1 || applied.id == CUSTOM_PROFILE_2) {
            val operation = editorSession.beginOperation(DspEditorOperationKind.SAVE)
            if (operation != null) {
                runtime.saveCustomAsync(applied, dspEnabled) { result ->
                    if (editorSession.finishOperation(
                            operation,
                            runtime.selectedProfile(),
                            runtime.isDspEnabled(),
                            acceptAppliedDraft = result == DspProfileSaveResult.SAVED,
                        )
                    ) {
                        if (screenActive.get()) {
                            if (result == DspProfileSaveResult.SAVED) profiles = runtime.availableProfiles()
                            showProfileSaveResult(context, result)
                        }
                    }
                }
            }
        } else {
            val operation = editorSession.beginOperation(DspEditorOperationKind.APPLY)
            if (operation != null) {
                runtime.applyAsync(applied, dspEnabled) { accepted ->
                    if (editorSession.finishOperation(
                            operation,
                            runtime.selectedProfile(),
                            runtime.isDspEnabled(),
                            acceptAppliedDraft = accepted,
                        ) && !accepted && screenActive.get()
                    ) {
                        Toast.makeText(context, R.string.dsp_cabin_peq_capacity, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val compact = presentation == DspPresentationMode.COMPACT_SPLIT
    val showBasic = !compact || compactSection == DspCompactSection.BASIC
    val showEq = (compact && compactSection == DspCompactSection.EQ) || (!compact && advanced)
    val showAdvanced = (compact && compactSection == DspCompactSection.ADVANCED) || (!compact && advanced)
    val compactHeightCap = LocalConfiguration.current.screenHeightDp.coerceAtLeast(1).dp
    val compactBodyConstraint = if (compact) {
        Modifier.layout { measurable, constraints ->
            val capPx = compactHeightCap.roundToPx()
            if (constraints.maxHeight == Constraints.Infinity) {
                val measurement = "DSP_SPLIT_BODY_UNBOUNDED " +
                    "minHeight=${constraints.minHeight} maxHeight=${constraints.maxHeight} capHeight=$capPx"
                DspSplitMeasureDiagnostics.record(measurement)
                Log.w(
                    "xcertplay-usb",
                    "$measurement recentViewMeasures=${DspSplitMeasureDiagnostics.recentMeasurements()}",
                )
            }
            val boundedMaxHeight = maxOf(constraints.minHeight, minOf(constraints.maxHeight, capPx))
            val placeable = measurable.measure(constraints.copy(maxHeight = boundedMaxHeight))
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
    } else {
        Modifier
    }

    Scaffold(
        modifier = if (compact) {
            // Cap an unbounded OEM pane to the current window before its body creates a scroll viewport.
            Modifier.heightIn(max = compactHeightCap).fillMaxSize()
        } else {
            Modifier.fillMaxSize()
        },
        topBar = {
            if (compact) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.dsp_settings_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = leaveEditor) {
                            Text("×", modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.dsp_close_settings)
                            })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = { compactSection = DspCompactSection.BASIC }) {
                            Text(
                                stringResource(R.string.dsp_basic_controls),
                                color = if (compactSection == DspCompactSection.BASIC) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = { compactSection = DspCompactSection.EQ }) {
                            Text(
                                stringResource(R.string.dsp_compact_eq_tab),
                                color = if (compactSection == DspCompactSection.EQ) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = { compactSection = DspCompactSection.ADVANCED }) {
                            Text(
                                stringResource(R.string.dsp_advanced_controls),
                                color = if (compactSection == DspCompactSection.ADVANCED) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.dsp_settings_title)) },
                    navigationIcon = {
                        TextButton(onClick = leaveEditor) { Text("‹") }
                    },
                )
            }
        },
        bottomBar = {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(onClick = applyChanges, enabled = !operationPending, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dsp_apply))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            enabled = !operationPending,
                            modifier = Modifier.weight(1f),
                            onClick = { saveCustomProfile(CUSTOM_PROFILE_1, R.string.dsp_preset_custom_1) },
                        ) { Text(stringResource(R.string.dsp_save_custom_1)) }
                        OutlinedButton(
                            enabled = !operationPending,
                            modifier = Modifier.weight(1f),
                            onClick = { saveCustomProfile(CUSTOM_PROFILE_2, R.string.dsp_preset_custom_2) },
                        ) { Text(stringResource(R.string.dsp_save_custom_2)) }
                    }
                    editorSession.pendingOperation?.let {
                        Text(stringResource(R.string.dsp_operation_in_progress), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    ) { insetPadding ->
        Column(
            modifier = compactBodyConstraint
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insetPadding)
                .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!compact) {
                Text(stringResource(R.string.dsp_settings_description), style = MaterialTheme.typography.bodyMedium)
            }

            if (showBasic) Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_enabled),
                        description = stringResource(R.string.dsp_enabled_description),
                        checked = dspEnabled,
                        enabled = !operationPending,
                        onCheckedChange = { enabled ->
                            val operation = editorSession.beginOperation(DspEditorOperationKind.MASTER)
                            if (operation != null) {
                                dspEnabled = enabled
                                runtime.setMasterEnabledAsync(enabled) {
                                    editorSession.finishOperation(
                                        operation,
                                        runtime.selectedProfile(),
                                        runtime.isDspEnabled(),
                                    )
                                }
                            }
                        },
                    )
                    editorSession.pendingOperation?.let {
                        Text(stringResource(R.string.dsp_operation_in_progress), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(stringResource(R.string.dsp_profile), style = MaterialTheme.typography.titleMedium)
                    BoxWithDropdown(
                        expanded = profileMenuExpanded,
                        onExpandedChange = {
                            profileMenuExpanded = it
                            if (it) profiles = runtime.availableProfiles()
                        },
                        label = profileLabel(context, profile),
                        items = profiles,
                        itemLabel = { profileLabel(context, it) },
                        enabled = !operationPending,
                        onSelect = { selected ->
                            profile = selected
                            profileMenuExpanded = false
                        },
                    )
                    Text(
                        stringResource(
                            if (editorSession.hasUnappliedChanges) R.string.dsp_unapplied_changes
                            else R.string.dsp_changes_applied,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (showBasic) SectionCard(title = stringResource(R.string.dsp_basic_controls)) {
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
                if (!profile.advancedMs.enabled) {
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
            }

            if (!compact) Card(modifier = Modifier.fillMaxWidth()) {
                DspSwitchRow(
                    modifier = Modifier.padding(16.dp),
                    title = stringResource(R.string.dsp_advanced_controls),
                    checked = advanced,
                    onCheckedChange = { advanced = it },
                )
            }

            if (showAdvanced) {
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
                SectionCard(title = stringResource(R.string.dsp_seat_alignment)) {
                    val seat = profile.seatAlignment
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_alignment),
                        checked = seat.enabled,
                        onCheckedChange = { profile = profile.copy(seatAlignment = seat.copy(enabled = it)) },
                        description = stringResource(R.string.dsp_seat_near_speaker_hint),
                    )
                    val presetOptions = listOf(
                        DspSeatPreset.DRIVER to stringResource(R.string.dsp_seat_driver),
                        DspSeatPreset.PASSENGER to stringResource(R.string.dsp_seat_passenger),
                        DspSeatPreset.CENTER to stringResource(R.string.dsp_seat_center),
                        DspSeatPreset.CUSTOM to stringResource(R.string.dsp_seat_custom),
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_seat_preset),
                        selected = presetOptions.first { it.first == seat.preset }.second,
                        choices = presetOptions.map { it.second },
                        onSelect = { selected ->
                            val preset = presetOptions.first { it.second == selected }.first
                            profile = profile.copy(seatAlignment = seat.withPreset(preset))
                        },
                    )
                    val speakerOptions = listOf(
                        DspSeatNearSpeaker.CENTER to stringResource(R.string.dsp_seat_near_center),
                        DspSeatNearSpeaker.LEFT to stringResource(R.string.dsp_seat_left),
                        DspSeatNearSpeaker.RIGHT to stringResource(R.string.dsp_seat_right),
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_seat_near_speaker),
                        selected = speakerOptions.first { it.first == seat.nearSpeaker }.second,
                        choices = speakerOptions.map { it.second },
                        onSelect = { selected ->
                            val nearSpeaker = speakerOptions.first { it.second == selected }.first
                            profile = profile.copy(seatAlignment = seat.withNearSpeaker(nearSpeaker))
                        },
                    )
                    if (seat.nearSpeaker != DspSeatNearSpeaker.CENTER) {
                        DspValueSlider(
                            stringResource(R.string.dsp_seat_distance_difference),
                            seat.distanceDifferenceCm,
                            0.0..DspSeatAlignmentConfig.MAX_DISTANCE_DIFFERENCE_CM,
                            "cm",
                        ) { profile = profile.copy(seatAlignment = seat.copy(distanceDifferenceCm = it)) }
                    }
                    DspValueSlider(stringResource(R.string.dsp_seat_left_trim), seat.leftTrimDb, -6.0..6.0, "dB") {
                        profile = profile.copy(seatAlignment = seat.copy(leftTrimDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_seat_right_trim), seat.rightTrimDb, -6.0..6.0, "dB") {
                        profile = profile.copy(seatAlignment = seat.copy(rightTrimDb = it))
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_invert_left),
                        checked = seat.invertLeft,
                        onCheckedChange = { profile = profile.copy(seatAlignment = seat.copy(invertLeft = it)) },
                    )
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_seat_invert_right),
                        checked = seat.invertRight,
                        onCheckedChange = { profile = profile.copy(seatAlignment = seat.copy(invertRight = it)) },
                    )
                }
                SectionCard(title = stringResource(R.string.dsp_ms_advanced)) {
                    val ms = profile.advancedMs
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_ms_advanced),
                        checked = ms.enabled,
                        description = stringResource(R.string.dsp_ms_description),
                        onCheckedChange = { enabled ->
                            val next = if (enabled && !ms.enabled) {
                                DspAdvancedMsConfig.fromLegacy(profile.stereoWidth, profile.monoBass)
                            } else {
                                ms.copy(enabled = enabled)
                            }
                            profile = profile.copy(advancedMs = next)
                        },
                    )
                    DspValueSlider(stringResource(R.string.dsp_ms_crossover), ms.crossoverHz, 60.0..1_000.0, "Hz") {
                        profile = profile.copy(advancedMs = ms.copy(crossoverHz = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_center_focus), ms.centerFocusDb, -6.0..6.0, "dB") {
                        profile = profile.copy(advancedMs = ms.copy(centerFocusDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_mid_low), ms.midLowGainDb, -6.0..6.0, "dB") {
                        profile = profile.copy(advancedMs = ms.copy(midLowGainDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_mid_high), ms.midHighGainDb, -6.0..6.0, "dB") {
                        profile = profile.copy(advancedMs = ms.copy(midHighGainDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_side_low), ms.sideLowGainDb, -6.0..6.0, "dB") {
                        profile = profile.copy(advancedMs = ms.copy(sideLowGainDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_side_high), ms.sideHighGainDb, -6.0..6.0, "dB") {
                        profile = profile.copy(advancedMs = ms.copy(sideHighGainDb = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_low_width), ms.lowBandWidth, 0.0..2.0, "") {
                        profile = profile.copy(advancedMs = ms.copy(lowBandWidth = it))
                    }
                    DspValueSlider(stringResource(R.string.dsp_ms_high_width), ms.highBandWidth, 0.0..2.0, "") {
                        profile = profile.copy(advancedMs = ms.copy(highBandWidth = it))
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_ms_side_compressor),
                        checked = ms.sideCompressor.enabled,
                        onCheckedChange = { profile = profile.copy(advancedMs = ms.copy(
                            sideCompressor = ms.sideCompressor.copy(enabled = it),
                        )) },
                    )
                    if (ms.sideCompressor.enabled) {
                        DspValueSlider(
                            stringResource(R.string.dsp_ms_side_threshold),
                            ms.sideCompressor.thresholdDb,
                            -60.0..0.0,
                            "dB",
                        ) { profile = profile.copy(advancedMs = ms.copy(
                            sideCompressor = ms.sideCompressor.copy(thresholdDb = it),
                        )) }
                        DspValueSlider(
                            stringResource(R.string.dsp_ms_side_ratio),
                            ms.sideCompressor.ratio,
                            1.0..8.0,
                            "",
                        ) { profile = profile.copy(advancedMs = ms.copy(
                            sideCompressor = ms.sideCompressor.copy(ratio = it),
                        )) }
                        DspValueSlider(
                            stringResource(R.string.dsp_ms_side_attack),
                            ms.sideCompressor.attackMs,
                            0.1..200.0,
                            "ms",
                        ) { profile = profile.copy(advancedMs = ms.copy(
                            sideCompressor = ms.sideCompressor.copy(attackMs = it),
                        )) }
                        DspValueSlider(
                            stringResource(R.string.dsp_ms_side_release),
                            ms.sideCompressor.releaseMs,
                            20.0..500.0,
                            "ms",
                        ) { profile = profile.copy(advancedMs = ms.copy(
                            sideCompressor = ms.sideCompressor.copy(releaseMs = it),
                        )) }
                    }
                }
                SectionCard(title = stringResource(R.string.dsp_cabin_correction)) {
                    val cabin = profile.cabinCorrection
                    Text(stringResource(R.string.dsp_cabin_description))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !operationPending,
                            onClick = {
                                openCabinMeasurement.launch(arrayOf("text/csv", "text/plain", "application/csv"))
                            },
                        ) {
                            Text(stringResource(R.string.dsp_cabin_import))
                        }
                        TextButton(
                            onClick = { profile = profile.copy(cabinCorrection = DspCabinCorrectionConfig()) },
                            enabled = !operationPending,
                        ) {
                            Text(stringResource(R.string.dsp_cabin_undo))
                        }
                    }
                    cabin.measurement?.let { measurement ->
                        Text(
                            stringResource(
                                R.string.dsp_cabin_measurement_summary,
                                measurement.pointCount,
                                measurement.minimumFrequencyHz,
                                measurement.maximumFrequencyHz,
                                measurement.sourceSha256.take(8),
                            ),
                        )
                    }
                    cabin.notches.forEachIndexed { index, notch ->
                        DspSwitchRow(
                            title = stringResource(R.string.dsp_cabin_notch, notch.frequencyHz),
                            checked = notch.enabled,
                            onCheckedChange = { enabled ->
                                val next = cabin.notches.mapIndexed { notchIndex, candidate ->
                                    if (notchIndex == index) candidate.copy(enabled = enabled) else candidate
                                }
                                val nextActiveCount = next.count(DspCabinCorrectionNotch::enabled)
                                val nextProfile = profile.copy(
                                    cabinCorrection = cabin.copy(
                                        enabled = cabin.enabled && nextActiveCount > 0,
                                        notches = next,
                                    ),
                                )
                                if (!nextProfile.hasValidEqCapacity()) {
                                    Toast.makeText(context, R.string.dsp_cabin_peq_capacity, Toast.LENGTH_LONG).show()
                                } else {
                                    profile = nextProfile
                                }
                            },
                        )
                        DspValueSlider(
                            stringResource(R.string.dsp_cabin_frequency),
                            notch.frequencyHz,
                            DspCabinCorrectionNotch.MIN_FREQUENCY_HZ..DspCabinCorrectionNotch.MAX_FREQUENCY_HZ,
                            "Hz",
                        ) { value ->
                            profile = profile.copy(cabinCorrection = cabin.copy(
                                notches = cabin.notches.mapIndexed { notchIndex, candidate ->
                                    if (notchIndex == index) candidate.copy(frequencyHz = value) else candidate
                                },
                            ))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_cabin_gain),
                            notch.gainDb,
                            DspCabinCorrectionNotch.MIN_GAIN_DB..DspCabinCorrectionNotch.MAX_GAIN_DB,
                            "dB",
                        ) { value ->
                            profile = profile.copy(cabinCorrection = cabin.copy(
                                notches = cabin.notches.mapIndexed { notchIndex, candidate ->
                                    if (notchIndex == index) candidate.copy(gainDb = value) else candidate
                                },
                            ))
                        }
                        DspValueSlider(
                            stringResource(R.string.dsp_cabin_q),
                            notch.q,
                            DspCabinCorrectionNotch.MIN_Q..DspCabinCorrectionNotch.MAX_Q,
                            "",
                        ) { value ->
                            profile = profile.copy(cabinCorrection = cabin.copy(
                                notches = cabin.notches.mapIndexed { notchIndex, candidate ->
                                    if (notchIndex == index) candidate.copy(q = value) else candidate
                                },
                            ))
                        }
                        TextButton(onClick = {
                            val next = cabin.notches.filterIndexed { notchIndex, _ -> notchIndex != index }
                            profile = profile.copy(cabinCorrection = cabin.copy(
                                enabled = cabin.enabled && next.any(DspCabinCorrectionNotch::enabled),
                                notches = next,
                            ))
                        }) {
                            Text(stringResource(R.string.dsp_cabin_remove_notch))
                        }
                    }
                    if (cabin.notches.size < DspCabinCorrectionConfig.MAX_NOTCHES) {
                        OutlinedButton(onClick = {
                            profile = profile.copy(
                                cabinCorrection = cabin.copy(notches = cabin.notches + DspCabinCorrectionNotch()),
                            )
                        }) {
                            Text(stringResource(R.string.dsp_cabin_add_manual_notch))
                        }
                    }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_cabin_enabled),
                        checked = cabin.enabled,
                        onCheckedChange = { enabled ->
                            val activeNotches = cabin.notches.count(DspCabinCorrectionNotch::enabled)
                            val nextProfile = profile.copy(cabinCorrection = cabin.copy(enabled = enabled))
                            if (enabled && activeNotches == 0) {
                                Toast.makeText(context, R.string.dsp_cabin_choose_notch, Toast.LENGTH_SHORT).show()
                            } else if (!nextProfile.hasValidEqCapacity()) {
                                Toast.makeText(context, R.string.dsp_cabin_peq_capacity, Toast.LENGTH_LONG).show()
                            } else {
                                profile = nextProfile
                            }
                        },
                    )
                }
                SectionCard(title = stringResource(R.string.dsp_harmonic_engine)) {
                    val harmonic = profile.harmonic
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_harmonic_engine),
                        checked = harmonic.enabled,
                        description = stringResource(R.string.dsp_harmonic_description),
                        onCheckedChange = { profile = profile.copy(harmonic = harmonic.copy(enabled = it)) },
                    )
                    val modeOptions = listOf(
                        DspHarmonicMode.EVEN to stringResource(R.string.dsp_harmonic_even),
                        DspHarmonicMode.ODD to stringResource(R.string.dsp_harmonic_odd),
                        DspHarmonicMode.MIXED to stringResource(R.string.dsp_harmonic_mixed),
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_harmonic_mode),
                        selected = modeOptions.first { it.first == harmonic.mode }.second,
                        choices = modeOptions.map { it.second },
                        onSelect = { selected ->
                            profile = profile.copy(harmonic = harmonic.copy(mode = modeOptions.first { it.second == selected }.first))
                        },
                    )
                    val qualityOptions = listOf(
                        DspHarmonicQuality.LOW to stringResource(R.string.dsp_harmonic_quality_low),
                        DspHarmonicQuality.MEDIUM to stringResource(R.string.dsp_harmonic_quality_medium),
                        DspHarmonicQuality.HIGH to stringResource(R.string.dsp_harmonic_quality_high),
                    )
                    ChoiceField(
                        title = stringResource(R.string.dsp_harmonic_quality),
                        selected = qualityOptions.first { it.first == harmonic.quality }.second,
                        choices = qualityOptions.map { it.second },
                        onSelect = { selected ->
                            profile = profile.copy(
                                harmonic = harmonic.copy(quality = qualityOptions.first { it.second == selected }.first),
                            )
                        },
                    )
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_harmonic_exciter),
                        checked = harmonic.exciterEnabled,
                        onCheckedChange = { profile = profile.copy(harmonic = harmonic.copy(exciterEnabled = it)) },
                    )
                    DspValueSlider(
                        stringResource(R.string.dsp_harmonic_exciter_amount),
                        harmonic.exciterAmount,
                        0.0..DspHarmonicConfig.MAX_AMOUNT,
                        "",
                    ) { profile = profile.copy(harmonic = harmonic.copy(exciterAmount = it)) }
                    DspValueSlider(
                        stringResource(R.string.dsp_harmonic_exciter_cutoff),
                        harmonic.exciterCutoffHz,
                        DspHarmonicConfig.MIN_EXCITER_CUTOFF_HZ..DspHarmonicConfig.MAX_EXCITER_CUTOFF_HZ,
                        "Hz",
                    ) { profile = profile.copy(harmonic = harmonic.copy(exciterCutoffHz = it)) }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_harmonic_virtual_bass),
                        checked = harmonic.virtualBassEnabled,
                        onCheckedChange = { profile = profile.copy(harmonic = harmonic.copy(virtualBassEnabled = it)) },
                    )
                    DspValueSlider(
                        stringResource(R.string.dsp_harmonic_virtual_bass_amount),
                        harmonic.virtualBassAmount,
                        0.0..DspHarmonicConfig.MAX_AMOUNT,
                        "",
                    ) { profile = profile.copy(harmonic = harmonic.copy(virtualBassAmount = it)) }
                    DspValueSlider(
                        stringResource(R.string.dsp_harmonic_virtual_bass_cutoff),
                        harmonic.virtualBassCutoffHz,
                        DspHarmonicConfig.MIN_VIRTUAL_BASS_CUTOFF_HZ..DspHarmonicConfig.MAX_VIRTUAL_BASS_CUTOFF_HZ,
                        "Hz",
                    ) { profile = profile.copy(harmonic = harmonic.copy(virtualBassCutoffHz = it)) }
                    DspSwitchRow(
                        title = stringResource(R.string.dsp_harmonic_level_match),
                        checked = harmonic.levelMatchEnabled,
                        onCheckedChange = { profile = profile.copy(harmonic = harmonic.copy(levelMatchEnabled = it)) },
                    )
                }
            }

            if (showEq) SectionCard(title = stringResource(R.string.dsp_parametric_eq)) {
                profile.eqBands.forEachIndexed { index, band ->
                    EqBandControl(
                        index = index,
                        band = band,
                        onEditType = { bandTypeDialog = index },
                        onChange = { changed ->
                            val next = profile.copy(
                                eqBands = profile.eqBands.toMutableList().apply { set(index, changed) },
                            )
                            if (!next.hasValidEqCapacity()) {
                                Toast.makeText(context, R.string.dsp_cabin_peq_capacity, Toast.LENGTH_LONG).show()
                            } else {
                                profile = next
                            }
                        },
                    )
                }
            }

            if (showAdvanced) {
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
                        enabled = !operationPending,
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
                        enabled = !operationPending,
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
                        enabled = !operationPending,
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
                    Text(stringResource(R.string.dsp_selected_profile, profileLabel(context, profile)))
                    Text(stringResource(R.string.dsp_applied_profile, profileLabel(context, editorSession.appliedProfile)))
                    Text(stringResource(R.string.dsp_dsp_state, if (dspEnabled) "ON" else "OFF"))
                    Text(stringResource(R.string.dsp_renderer_apply_status))
                    Text(rendererApplyStatusText(rendererApplyStatus))
                    Text(stringResource(R.string.dsp_renderer_apply_status_note))
                    rendererApplyWarningText(rendererApplyStatus)?.let { warning -> Text(warning) }
                }
            }

            if (!compact) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !operationPending,
                    onClick = applyChanges,
                ) {
                    Text(stringResource(R.string.dsp_apply))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !operationPending,
                        onClick = { saveCustomProfile(CUSTOM_PROFILE_1, R.string.dsp_preset_custom_1) },
                    ) { Text(stringResource(R.string.dsp_save_custom_1)) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !operationPending,
                        onClick = { saveCustomProfile(CUSTOM_PROFILE_2, R.string.dsp_preset_custom_2) },
                    ) { Text(stringResource(R.string.dsp_save_custom_2)) }
                }
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
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
private fun ChoiceField(
    title: String,
    selected: String,
    choices: List<String>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    Column {
        Text(title)
        BoxWithDropdown(
            expanded = expanded.value,
            onExpandedChange = { expanded.value = it },
            label = selected,
            items = choices,
            itemLabel = { it },
            enabled = enabled,
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
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column {
        OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, enabled = enabled, onClick = { onSelect(item) })
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
internal fun dspColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF10243C),
    background = Color(0xFF0C111B),
    surface = Color(0xFF151E2C),
    onSurface = Color(0xFFF1F5FC),
)

private fun showProfileSaveResult(context: Context, result: DspProfileSaveResult) {
    val message = when (result) {
        DspProfileSaveResult.SAVED -> R.string.dsp_profile_saved
        DspProfileSaveResult.INVALID_EQ_CAPACITY -> R.string.dsp_cabin_peq_capacity
        DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED -> R.string.dsp_profile_future_schema
        else -> R.string.dsp_profile_save_failed
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private const val CUSTOM_PROFILE_1 = "custom1"
private const val CUSTOM_PROFILE_2 = "custom2"
