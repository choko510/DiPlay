package com.shilapi.xcertplay.dsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqResponse
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import java.text.DecimalFormatSymbols
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** Reusable editor for the profile's existing 15-band PEQ and approved Cabin EQ. */
@Composable
fun DspEqEditorPane(
    profile: DspAudioProfile,
    cabinCorrection: DspCabinCorrectionConfig = profile.cabinCorrection,
    onProfileChange: (DspAudioProfile) -> Unit,
    onEditBandType: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    sampleRateHz: Int? = null,
) {
    val effectiveSampleRateHz = sampleRateHz ?: DEFAULT_EQ_PREVIEW_SAMPLE_RATE_HZ
    require(effectiveSampleRateHz in 8_000..192_000)
    val cabinBands = cabinCorrection.approvedEqBands()
    val activeTotal = activeEqBandCount(profile.eqBands, cabinBands)
    val maxFrequencyHz = minOf(20_000f, effectiveSampleRateHz * 0.45f)
    var selectedBandIndex by remember { mutableStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rateDescription = if (sampleRateHz == null) {
            stringResource(R.string.dsp_eq_playback_rate_unknown, effectiveSampleRateHz)
        } else {
            stringResource(R.string.dsp_eq_playback_rate_active, effectiveSampleRateHz)
        }
        Text(
            stringResource(
                R.string.dsp_eq_editor_heading,
                activeTotal,
                DspAudioProfile.MAX_EQ_BANDS,
                cabinBands.size,
                rateDescription,
            ),
        )
        AccessibleSlider("Preamp", profile.preampDb.toFloat(), -24f..12f, "dB") {
            onProfileChange(profile.copy(preampDb = it.toDouble()))
        }
        EqResponseGraph(
            bands = profile.eqBands + cabinBands,
            editableBandCount = profile.eqBands.size,
            selectedBandIndex = selectedBandIndex,
            preampDb = profile.preampDb.toFloat(),
            sampleRateHz = effectiveSampleRateHz,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            onSelectBand = { selectedBandIndex = it },
            onBandEdit = { index, band -> updateBand(profile, index, band, onProfileChange) },
        )
        profile.eqBands.forEachIndexed { index, band ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PEQ band ${index + 1}", Modifier.weight(1f))
                        Switch(
                            checked = band.enabled,
                            onCheckedChange = { enabled ->
                                val next = profile.eqBands.toMutableList()
                                next[index] = band.copy(enabled = enabled)
                                if (activeEqBandCount(next, cabinBands) <= DspAudioProfile.MAX_EQ_BANDS) {
                                    onProfileChange(profile.copy(eqBands = next))
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Enable PEQ band ${index + 1}" },
                        )
                    }
                    TextButton(onClick = { onEditBandType(index) }) {
                        Text("Type · ${band.type.name.replace('_', ' ')}")
                    }
                    AccessibleSlider("Band ${index + 1} frequency", band.frequencyHz.toFloat(), 20f..maxFrequencyHz, "Hz") {
                        updateBand(profile, index, band.copy(frequencyHz = it.toDouble()), onProfileChange)
                    }
                    NumericValueField("Band ${index + 1} frequency (Hz)", band.frequencyHz, 20.0..maxFrequencyHz.toDouble()) {
                        updateBand(profile, index, band.copy(frequencyHz = it), onProfileChange)
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
                        AccessibleSlider("Band ${index + 1} gain", band.gainDb.toFloat(), -18f..18f, "dB") {
                            updateBand(profile, index, band.copy(gainDb = it.toDouble()), onProfileChange)
                        }
                        NumericValueField("Band ${index + 1} gain (dB)", band.gainDb, -18.0..18.0) {
                            updateBand(profile, index, band.copy(gainDb = it), onProfileChange)
                        }
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.HIGH_PASS, DspEqType.LOW_PASS, DspEqType.NOTCH)) {
                        AccessibleSlider("Band ${index + 1} Q", band.q.toFloat(), 0.1f..20f, "Q") {
                            updateBand(profile, index, band.copy(q = it.toDouble()), onProfileChange)
                        }
                        NumericValueField("Band ${index + 1} Q", band.q, 0.1..20.0) {
                            updateBand(profile, index, band.copy(q = it), onProfileChange)
                        }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_EQ_PREVIEW_SAMPLE_RATE_HZ = 44_100

private fun updateBand(
    profile: DspAudioProfile,
    index: Int,
    band: DspEqBand,
    onChange: (DspAudioProfile) -> Unit,
) {
    onChange(updatedEqBandProfile(profile, index, band))
}

internal fun updatedEqBandProfile(profile: DspAudioProfile, index: Int, band: DspEqBand): DspAudioProfile {
    val next = profile.eqBands.toMutableList().also { it[index] = band }
    return profile.copy(eqBands = next)
}

/** Reusable controls for modules already represented in DspAudioProfile. */
@Composable
fun DspAdvancedControlsPane(
    profile: DspAudioProfile,
    onProfileChange: (DspAudioProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModuleControls("Bass", profile.bass.enabled, { onProfileChange(profile.copy(bass = profile.bass.copy(enabled = it))) }) {
            ValueSlider("Bass gain", profile.bass.gainDb.toFloat(), -18f..18f, "dB") { onProfileChange(profile.copy(bass = profile.bass.copy(gainDb = it.toDouble()))) }
            ValueSlider("Bass frequency", profile.bass.frequencyHz.toFloat(), 20f..300f, "Hz") { onProfileChange(profile.copy(bass = profile.bass.copy(frequencyHz = it.toDouble()))) }
        }
        ModuleControls("Compressor", profile.compressor.enabled, { onProfileChange(profile.copy(compressor = profile.compressor.copy(enabled = it))) }) {
            ValueSlider("Compressor threshold", profile.compressor.thresholdDb.toFloat(), -60f..0f, "dB") { onProfileChange(profile.copy(compressor = profile.compressor.copy(thresholdDb = it.toDouble()))) }
            ValueSlider("Compressor ratio", profile.compressor.ratio.toFloat(), 1f..20f, "ratio") { onProfileChange(profile.copy(compressor = profile.compressor.copy(ratio = it.toDouble()))) }
        }
        ModuleControls("Multiband compressor", profile.multiband.enabled, { onProfileChange(profile.copy(multiband = profile.multiband.copy(enabled = it))) }) {
            ValueSlider("Low-mid crossover", profile.multiband.lowMidCrossoverHz.toFloat(), 20f..1_000f, "Hz") { onProfileChange(profile.copy(multiband = safeMultiband(profile.multiband, lowMid = it.toDouble()))) }
            ValueSlider("Mid-high crossover", profile.multiband.midHighCrossoverHz.toFloat(), 500f..3_500f, "Hz") { onProfileChange(profile.copy(multiband = safeMultiband(profile.multiband, midHigh = it.toDouble()))) }
        }
        ModuleControls("Dynamic EQ", profile.dynamicEq.enabled, { onProfileChange(profile.copy(dynamicEq = DspDynamicEqConfig(it, profile.dynamicEq.bands))) }) {
            profile.dynamicEq.bands.forEachIndexed { index, band ->
                ValueSlider("Dynamic EQ band ${index + 1} frequency", band.frequencyHz.toFloat(), 20f..19_800f, "Hz") {
                    onProfileChange(profile.copy(dynamicEq = DspDynamicEqConfig(profile.dynamicEq.enabled, profile.dynamicEq.bands.mapIndexed { i, value -> if (i == index) value.copy(frequencyHz = it.toDouble()) else value })))
                }
            }
        }
        ModuleControls("Safety limiter", profile.limiter.enabled, { onProfileChange(profile.copy(limiter = profile.limiter.copy(enabled = it))) }) {
            ValueSlider("Limiter threshold", profile.limiter.thresholdDb.toFloat(), -12f..0f, "dB") { onProfileChange(profile.copy(limiter = profile.limiter.copy(thresholdDb = it.toDouble()))) }
        }
        ModuleControls("Convolver", profile.convolver.enabled, { onProfileChange(profile.copy(convolver = profile.convolver.copy(enabled = it))) }) {
            ValueSlider("Convolver wet mix", profile.convolver.wet.toFloat(), 0f..1f, "mix") { onProfileChange(profile.copy(convolver = profile.convolver.copy(wet = it.toDouble()))) }
        }
        ModuleControls("Cabin correction", profile.cabinCorrection.enabled, { enabled ->
            val cabin = profile.cabinCorrection
            if (!enabled || cabin.notches.any { it.enabled }) onProfileChange(profile.copy(cabinCorrection = cabin.copy(enabled = enabled)))
        }) {
            Text("Approved Cabin EQ bands are included in the EQ graph and share its 15-band runtime limit.")
        }
        ModuleControls("Seat alignment", profile.seatAlignment.enabled, { onProfileChange(profile.copy(seatAlignment = profile.seatAlignment.copy(enabled = it))) }) {
            ValueSlider("Seat left trim", profile.seatAlignment.leftTrimDb.toFloat(), -6f..6f, "dB") { onProfileChange(profile.copy(seatAlignment = profile.seatAlignment.copy(leftTrimDb = it.toDouble()))) }
            ValueSlider("Seat right trim", profile.seatAlignment.rightTrimDb.toFloat(), -6f..6f, "dB") { onProfileChange(profile.copy(seatAlignment = profile.seatAlignment.copy(rightTrimDb = it.toDouble()))) }
        }
        ModuleControls("Advanced M/S", profile.advancedMs.enabled, { onProfileChange(profile.copy(advancedMs = profile.advancedMs.copy(enabled = it))) }) {
            ValueSlider("M/S center focus", profile.advancedMs.centerFocusDb.toFloat(), -6f..6f, "dB") { onProfileChange(profile.copy(advancedMs = profile.advancedMs.copy(centerFocusDb = it.toDouble()))) }
            ValueSlider("M/S low width", profile.advancedMs.lowBandWidth.toFloat(), 0f..2f, "width") { onProfileChange(profile.copy(advancedMs = profile.advancedMs.copy(lowBandWidth = it.toDouble()))) }
        }
        ModuleControls("Harmonic", profile.harmonic.enabled, { onProfileChange(profile.copy(harmonic = profile.harmonic.copy(enabled = it))) }) {
            ValueSlider("Harmonic exciter amount", profile.harmonic.exciterAmount.toFloat(), 0f..DspHarmonicConfig.MAX_AMOUNT.toFloat(), "amount") { onProfileChange(profile.copy(harmonic = profile.harmonic.copy(exciterAmount = it.toDouble()))) }
        }
    }
}

internal fun activeEqBandCount(peqBands: List<DspEqBand>, cabinBands: List<DspEqBand>): Int =
    peqBands.count(DspEqBand::enabled) + cabinBands.count(DspEqBand::enabled)

private fun safeMultiband(config: DspMultibandConfig, lowMid: Double? = null, midHigh: Double? = null): DspMultibandConfig {
    val low = (lowMid ?: config.lowMidCrossoverHz).coerceAtMost(config.midHighCrossoverHz - 1.0)
    val high = (midHigh ?: config.midHighCrossoverHz).coerceAtLeast(config.lowMidCrossoverHz + 1.0)
    return config.copy(lowMidCrossoverHz = low, midHighCrossoverHz = high)
}

private data class CurvePoint(val x: Float, val y: Float)

internal fun eqGraphFrequencyAtX(x: Float, width: Float, minFrequencyHz: Double, maxFrequencyHz: Double): Double {
    require(minFrequencyHz > 0.0 && maxFrequencyHz >= minFrequencyHz)
    if (width <= 0f) return minFrequencyHz
    val fraction = (x / width).coerceIn(0f, 1f).toDouble()
    return minFrequencyHz * (maxFrequencyHz / minFrequencyHz).pow(fraction)
}

internal fun eqGraphGainAtY(y: Float, height: Float): Double {
    if (height <= 0f) return 0.0
    return (18.0 - (y / height).coerceIn(0f, 1f) * 36.0).coerceIn(-18.0, 18.0)
}

internal fun safeEqNumericValue(value: Double, bounds: ClosedFloatingPointRange<Double>): Double =
    if (value.isFinite()) value.coerceIn(bounds) else bounds.start

internal fun parseEqNumericValue(input: String, decimalSeparator: Char): Double? {
    if (input.isEmpty()) return null
    var hasDigit = false
    var hasDecimalSeparator = false
    input.forEachIndexed { index, character ->
        when {
            character.isDigit() -> hasDigit = true
            character == decimalSeparator && !hasDecimalSeparator -> hasDecimalSeparator = true
            (character == '-' || character == '+') && index == 0 -> Unit
            else -> return null
        }
    }
    if (!hasDigit) return null
    return input.replace(decimalSeparator, '.').toDoubleOrNull()?.takeIf(Double::isFinite)
}

private fun formatEqNumericValue(value: Double, decimalSeparator: Char): String =
    value.toString().replace('.', decimalSeparator)

internal fun dspEqResponseDb(
    frequencyHz: Double,
    bands: List<DspEqBand>,
    preampDb: Double = 0.0,
    sampleRateHz: Int = 44_100,
): Double {
    require(sampleRateHz in 8_000..192_000)
    if (frequencyHz <= 0.0) return preampDb
    val maxFrequencyHz = minOf(20_000.0, sampleRateHz * 0.45)
    val safeFrequencyHz = frequencyHz.coerceIn(20.0, maxFrequencyHz)
    return preampDb + bands.filter(DspEqBand::enabled).sumOf { band ->
        val safeBand = band.copy(frequencyHz = band.frequencyHz.coerceIn(20.0, maxFrequencyHz))
        val magnitude = DspEqResponse.magnitudeAt(sampleRateHz, safeFrequencyHz, safeBand).coerceAtLeast(1e-12)
        20.0 * log10(magnitude)
    }
}

@Composable
private fun EqResponseGraph(
    bands: List<DspEqBand>,
    editableBandCount: Int,
    selectedBandIndex: Int,
    preampDb: Float,
    sampleRateHz: Int,
    modifier: Modifier,
    onSelectBand: (Int) -> Unit,
    onBandEdit: (Int, DspEqBand) -> Unit,
) {
    val curveColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val maxFrequencyHz = minOf(20_000.0, sampleRateHz * 0.45)
    val currentBands by rememberUpdatedState(bands)
    val currentOnSelectBand by rememberUpdatedState(onSelectBand)
    val currentOnBandEdit by rememberUpdatedState(onBandEdit)
    val currentEditableBandCount by rememberUpdatedState(editableBandCount)
    val currentSelectedBandIndex by rememberUpdatedState(selectedBandIndex)
    val hitRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    val graphInputModifier = Modifier
        .pointerInput(sampleRateHz, hitRadiusPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val index = nearestEditableBand(
                    currentBands, currentEditableBandCount, down.position, size.width.toFloat(), size.height.toFloat(), maxFrequencyHz, hitRadiusPx,
                )
                if (index >= 0) {
                    currentOnSelectBand(index)
                    val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (dragStart != null) {
                        editGraphBandAt(
                            index, dragStart.position, currentBands, size.width.toFloat(), size.height.toFloat(), maxFrequencyHz, currentOnBandEdit,
                        )
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                change.consume()
                                editGraphBandAt(
                                    index, change.position, currentBands, size.width.toFloat(), size.height.toFloat(), maxFrequencyHz, currentOnBandEdit,
                                )
                            } else {
                                break
                            }
                        }
                    }
                }
            }
        }
    Canvas(modifier.then(graphInputModifier).semantics {
        contentDescription = "Equalizer frequency response graph. Tap or drag a band handle to edit frequency and gain."
    }) {
        val lowDb = -18f
        val highDb = 18f
        val maxFrequencyHz = minOf(20_000.0, sampleRateHz * 0.45)
        val points = (0..160).map { i ->
            val hz = 20.0 * (maxFrequencyHz / 20.0).pow(i / 160.0)
            val x = size.width * i / 160f
            val response = dspEqResponseDb(hz, bands, preampDb.toDouble(), sampleRateHz).toFloat().coerceIn(lowDb, highDb)
            CurvePoint(x, size.height * (highDb - response) / (highDb - lowDb))
        }
        val zeroY = size.height * highDb / (highDb - lowDb)
        drawLine(androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.geometry.Offset(0f, zeroY), androidx.compose.ui.geometry.Offset(size.width, zeroY), 1.dp.toPx())
        val path = Path().apply { points.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
        drawPath(path, curveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        bands.take(editableBandCount).forEachIndexed { index, band ->
            val x = size.width * (ln(band.frequencyHz.coerceIn(20.0, maxFrequencyHz) / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val y = size.height * (18.0 - band.gainDb.coerceIn(-18.0, 18.0)) / 36.0
            drawCircle(
                color = if (band.enabled) curveColor else androidx.compose.ui.graphics.Color.Gray,
                radius = if (index == currentSelectedBandIndex) 7.dp.toPx() else 5.dp.toPx(),
                center = Offset(x, y.toFloat()),
                style = if (index == currentSelectedBandIndex) androidx.compose.ui.graphics.drawscope.Fill else androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun nearestEditableBand(
    bands: List<DspEqBand>,
    editableBandCount: Int,
    position: Offset,
    width: Float,
    height: Float,
    maxFrequencyHz: Double,
    hitRadiusPx: Float,
): Int {
    if (width <= 0f || height <= 0f) return -1
    return bands.take(editableBandCount).indices
        .map { index ->
            val band = bands[index]
            val x = width * (ln(band.frequencyHz.coerceIn(20.0, maxFrequencyHz) / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val y = height * (18.0 - band.gainDb.coerceIn(-18.0, 18.0)) / 36.0
            index to (Offset(x, y.toFloat()) - position).getDistance()
        }
        .filter { it.second <= hitRadiusPx }
        .minByOrNull { it.second }
        ?.first ?: -1
}

private fun editGraphBandAt(
    index: Int,
    position: Offset,
    bands: List<DspEqBand>,
    width: Float,
    height: Float,
    maxFrequencyHz: Double,
    onBandEdit: (Int, DspEqBand) -> Unit,
) {
    val band = bands.getOrNull(index) ?: return
    val frequency = eqGraphFrequencyAtX(position.x, width, 20.0, maxFrequencyHz)
    val gain = if (band.type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
        eqGraphGainAtY(position.y, height)
    } else band.gainDb
    onBandEdit(index, band.copy(frequencyHz = frequency, gainDb = gain))
}

@Composable
private fun ModuleControls(title: String, enabled: Boolean, onEnabled: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Switch(enabled, onEnabled, modifier = Modifier.semantics { contentDescription = "Enable $title" })
            }
            if (enabled) content()
        }
    }
}

@Composable
private fun AccessibleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    val logarithmic = unit == "Hz" && range.start > 0f && range.endInclusive / range.start > 10f
    val sliderValue = if (logarithmic) {
        (ln(value.coerceIn(range).toDouble() / range.start) / ln(range.endInclusive.toDouble() / range.start)).toFloat()
    } else value.coerceIn(range)
    val sliderRange = if (logarithmic) 0f..1f else range
    Column {
        Text("$label: ${"%.1f".format(value)} $unit")
        Slider(
            value = sliderValue,
            onValueChange = { fraction ->
                val mapped = if (logarithmic) {
                    range.start * (range.endInclusive.toDouble() / range.start).pow(fraction.toDouble()).toFloat()
                } else fraction
                onChange(mapped.coerceIn(range))
            },
            valueRange = sliderRange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun NumericValueField(
    label: String,
    value: Double,
    bounds: ClosedFloatingPointRange<Double>,
    onChange: (Double) -> Unit,
) {
    val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
    var text by remember(label) { mutableStateOf(formatEqNumericValue(value, decimalSeparator)) }
    var focused by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = formatEqNumericValue(value, decimalSeparator)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            parseEqNumericValue(input, decimalSeparator)?.let { onChange(safeEqNumericValue(it, bounds)) }
        },
        label = { Text(label) },
        singleLine = true,
        trailingIcon = if (bounds.start < 0.0) {
            {
                IconButton(
                    onClick = {
                        val next = safeEqNumericValue(-value, bounds)
                        text = formatEqNumericValue(next, decimalSeparator)
                        onChange(next)
                    },
                    modifier = Modifier.semantics { contentDescription = "Toggle sign for $label" },
                ) { Text("±") }
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            focused = state.isFocused
            if (!state.isFocused) text = formatEqNumericValue(value, decimalSeparator)
        },
    )
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) =
    AccessibleSlider(label, value, range, unit, onChange)

@Composable
internal fun DspModulePresetPane(
    moduleKey: String,
    moduleKeys: List<String>,
    presets: List<DspModulePreset>,
    selectedPresetId: String?,
    enabled: Boolean,
    onModuleKeyChange: (String) -> Unit,
    onPresetSelect: (String) -> Unit,
    onApply: (DspModulePreset) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFactoryReset: () -> Unit,
) {
    var moduleMenuExpanded by remember { mutableStateOf(false) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var saveDialogVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var factoryResetDialogVisible by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId } ?: presets.firstOrNull()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Module presets", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Module", Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { moduleMenuExpanded = true }, enabled = enabled) {
                        Text("${modulePresetLabel(moduleKey)} ▾")
                    }
                    DropdownMenu(
                        expanded = moduleMenuExpanded,
                        onDismissRequest = { moduleMenuExpanded = false },
                    ) {
                        moduleKeys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(modulePresetLabel(key)) },
                                onClick = {
                                    moduleMenuExpanded = false
                                    onModuleKeyChange(key)
                                },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preset", Modifier.weight(1f))
                Box {
                    OutlinedButton(
                        onClick = { presetMenuExpanded = true },
                        enabled = enabled && presets.isNotEmpty(),
                    ) {
                        Text(selectedPreset?.let { "${it.name} · ${if (it.factory) "Factory" else "User"} ▾" } ?: "Loading…")
                    }
                    DropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false },
                    ) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text("${preset.name} · ${if (preset.factory) "Factory" else "User"}") },
                                onClick = {
                                    presetMenuExpanded = false
                                    onPresetSelect(preset.id)
                                },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selectedPreset?.let(onApply) },
                    enabled = enabled && selectedPreset != null,
                ) {
                    Text("Apply preset to draft")
                }
                OutlinedButton(onClick = { saveDialogVisible = true }, enabled = enabled) {
                    Text("Save as User preset")
                }
            }
            OutlinedButton(
                onClick = { deleteDialogVisible = true },
                enabled = enabled && selectedPreset?.factory == false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dsp_module_preset_delete))
            }
            OutlinedButton(
                onClick = { factoryResetDialogVisible = true },
                enabled = enabled && presets.any(DspModulePreset::factory),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dsp_module_factory_reset))
            }
            Text(
                "Applying changes only this module in the draft. Preview and Save are separate.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (saveDialogVisible) {
        AlertDialog(
            onDismissRequest = { saveDialogVisible = false },
            title = { Text("Save ${modulePresetLabel(moduleKey)} preset") },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { if (it.length <= 48) newPresetName = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveDialogVisible = false
                        onSave(newPresetName.trim())
                        newPresetName = ""
                    },
                    enabled = enabled && newPresetName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { saveDialogVisible = false }) { Text("Cancel") }
            },
        )
    }

    if (deleteDialogVisible && selectedPreset != null) {
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            title = { Text(stringResource(R.string.dsp_module_preset_delete_title)) },
            text = { Text(stringResource(R.string.dsp_module_preset_delete_message, selectedPreset.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteDialogVisible = false
                        onDelete(selectedPreset.id)
                    },
                ) { Text(stringResource(R.string.dsp_module_preset_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteDialogVisible = false }) {
                    Text(stringResource(R.string.dsp_cancel))
                }
            },
        )
    }

    if (factoryResetDialogVisible) {
        AlertDialog(
            onDismissRequest = { factoryResetDialogVisible = false },
            title = { Text(stringResource(R.string.dsp_module_factory_reset)) },
            text = { Text(stringResource(R.string.dsp_module_factory_reset_message, modulePresetLabel(moduleKey))) },
            confirmButton = {
                Button(
                    onClick = {
                        factoryResetDialogVisible = false
                        onFactoryReset()
                    },
                ) { Text(stringResource(R.string.dsp_module_factory_reset)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { factoryResetDialogVisible = false }) {
                    Text(stringResource(R.string.dsp_cancel))
                }
            },
        )
    }
}

private fun modulePresetLabel(key: String): String = when (key) {
    "peq" -> "PEQ"
    "bass" -> "Bass"
    "seat" -> "Seat alignment"
    "advanced_ms" -> "Advanced M/S"
    "compressor" -> "Compressor"
    "multiband" -> "Multiband"
    "dynamic_eq" -> "Dynamic EQ"
    "harmonic" -> "Harmonic"
    "limiter" -> "Safety limiter"
    "convolver" -> "Convolver"
    "cabin" -> "Cabin correction"
    "smart_loudness" -> "Smart Loudness"
    "dynamic_bass" -> "Dynamic Bass"
    "macros" -> "Sound macros"
    else -> key
}
