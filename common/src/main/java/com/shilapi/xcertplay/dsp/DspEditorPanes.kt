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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqResponse
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspMacroToneConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSmartLoudnessRuntimeConfig
import java.text.DecimalFormatSymbols
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun DspEqType.filterLabelResource(): Int = when (this) {
    DspEqType.PEAK -> R.string.dsp_eq_filter_peak
    DspEqType.LOW_SHELF -> R.string.dsp_eq_filter_low_shelf
    DspEqType.HIGH_SHELF -> R.string.dsp_eq_filter_high_shelf
    DspEqType.HIGH_PASS -> R.string.dsp_eq_filter_high_pass
    DspEqType.LOW_PASS -> R.string.dsp_eq_filter_low_pass
    DspEqType.NOTCH -> R.string.dsp_eq_filter_notch
    DspEqType.ALL_PASS -> R.string.dsp_eq_filter_all_pass
}

/** Reusable editor for the profile's existing 15-band PEQ and approved Cabin EQ. */
@Composable
fun DspEqEditorPane(
    profile: DspAudioProfile,
    cabinCorrection: DspCabinCorrectionConfig = profile.cabinCorrection,
    onProfileChange: (DspAudioProfile) -> Unit,
    onEditBandType: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    sampleRateHz: Int? = null,
    onEditFinished: () -> Unit = {},
    macroTone: DspMacroToneConfig = DspMacroToneConfig(),
    smartLoudness: DspSmartLoudnessRuntimeConfig = DspSmartLoudnessRuntimeConfig(),
    dynamicBassEnabled: Boolean = false,
    predictedPreampDb: Double = profile.preampDb,
) {
    val effectiveSampleRateHz = sampleRateHz ?: DEFAULT_EQ_PREVIEW_SAMPLE_RATE_HZ
    require(effectiveSampleRateHz in 8_000..192_000)
    val cabinBands = cabinCorrection.approvedEqBands()
    val activeTotal = activeEqBandCount(profile.eqBands, cabinBands)
    val maxFrequencyHz = minOf(20_000f, effectiveSampleRateHz * 0.45f)
    var selectedBandIndex by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(profile.eqBands.size) {
        selectedBandIndex = selectedBandIndex.coerceIn(0, (profile.eqBands.lastIndex).coerceAtLeast(0))
    }
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
        Text(stringResource(R.string.dsp_eq_predicted_response, maxFrequencyHz.roundToInt(), effectiveSampleRateHz))
        AccessibleSlider(stringResource(R.string.dsp_preamp), profile.preampDb.toFloat(), -24f..12f, "dB", onEditFinished) {
            onProfileChange(profile.copy(preampDb = it.toDouble()))
        }
        EqResponseGraph(
            bands = profile.eqBands + cabinBands,
            editableBandCount = profile.eqBands.size,
            selectedBandIndex = selectedBandIndex,
            preampDb = predictedPreampDb.toFloat(),
            sampleRateHz = effectiveSampleRateHz,
            macroTone = macroTone,
            smartLoudness = smartLoudness,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            onSelectBand = { selectedBandIndex = it },
            onBandEdit = { index, band -> updateBand(profile, index, band, onProfileChange) },
            onEditFinished = onEditFinished,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dsp_eq_curve_peq), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.dsp_eq_curve_tone), color = MaterialTheme.colorScheme.secondary)
            Text(stringResource(R.string.dsp_eq_curve_combined), color = MaterialTheme.colorScheme.tertiary)
        }
        Text(stringResource(R.string.dsp_eq_stereo_width_excluded), style = MaterialTheme.typography.bodySmall)
        if (dynamicBassEnabled) Text(stringResource(R.string.dsp_eq_dynamic_bass_excluded))
        if (profile.eqBands.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                profile.eqBands.indices.forEach { index ->
                    val selected = index == selectedBandIndex
                    val selectionLabel = stringResource(R.string.dsp_eq_select_band, index + 1)
                    val selectionState = stringResource(
                        if (selected) R.string.dsp_eq_selected else R.string.dsp_eq_not_selected,
                    )
                    val modifier = Modifier.semantics {
                        contentDescription = selectionLabel
                        stateDescription = selectionState
                    }
                    if (selected) Button(onClick = { selectedBandIndex = index }, modifier = modifier) {
                        Text("${index + 1}")
                    } else OutlinedButton(onClick = { selectedBandIndex = index }, modifier = modifier) {
                        Text("${index + 1}")
                    }
                }
            }
            val index = selectedBandIndex.coerceIn(profile.eqBands.indices)
            val band = profile.eqBands[index]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val bandTitle = stringResource(R.string.dsp_eq_band_title, index + 1)
                    val enableDescription = stringResource(R.string.dsp_eq_band_enable, index + 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bandTitle, Modifier.weight(1f))
                        Switch(
                            checked = band.enabled,
                            onCheckedChange = { enabled ->
                                val next = profile.eqBands.toMutableList()
                                next[index] = band.copy(enabled = enabled)
                                if (activeEqBandCount(next, cabinBands) <= DspAudioProfile.MAX_EQ_BANDS) {
                                    onProfileChange(profile.copy(eqBands = next))
                                    onEditFinished()
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = enableDescription },
                        )
                    }
                    TextButton(onClick = { onEditBandType(index) }) {
                        Text(stringResource(R.string.dsp_eq_filter_type_summary, stringResource(band.type.filterLabelResource())))
                    }
                    val frequencyLabel = stringResource(R.string.dsp_eq_band_frequency, index + 1)
                    AccessibleSlider(frequencyLabel, band.frequencyHz.toFloat(), 20f..maxFrequencyHz, "Hz", onEditFinished) {
                        updateBand(profile, index, band.copy(frequencyHz = it.toDouble()), onProfileChange)
                    }
                    NumericValueField(
                        stringResource(R.string.dsp_eq_band_frequency_numeric, index + 1),
                        band.frequencyHz,
                        20.0..maxFrequencyHz.toDouble(),
                        onEditFinished,
                    ) {
                        updateBand(profile, index, band.copy(frequencyHz = it), onProfileChange)
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
                        val gainLabel = stringResource(R.string.dsp_eq_band_gain, index + 1)
                        AccessibleSlider(gainLabel, band.gainDb.toFloat(), -18f..18f, "dB", onEditFinished) {
                            updateBand(profile, index, band.copy(gainDb = it.toDouble()), onProfileChange)
                        }
                        NumericValueField(
                            stringResource(R.string.dsp_eq_band_gain_numeric, index + 1),
                            band.gainDb,
                            -18.0..18.0,
                            onEditFinished,
                        ) {
                            updateBand(profile, index, band.copy(gainDb = it), onProfileChange)
                        }
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.HIGH_PASS, DspEqType.LOW_PASS, DspEqType.NOTCH)) {
                        val qLabel = stringResource(R.string.dsp_eq_band_q, index + 1)
                        AccessibleSlider(qLabel, band.q.toFloat(), 0.1f..20f, "Q", onEditFinished) {
                            updateBand(profile, index, band.copy(q = it.toDouble()), onProfileChange)
                        }
                        NumericValueField(qLabel, band.q, 0.1..20.0, onEditFinished) {
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
            Text(stringResource(R.string.dsp_eq_cabin_limit_note))
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

private data class EqGraphResponseSample(val frequencyHz: Double, val eqDb: Double, val toneDb: Double)

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

private data class StudioTransfer(val real: Double, val imaginary: Double) {
    operator fun plus(other: StudioTransfer) = StudioTransfer(real + other.real, imaginary + other.imaginary)
    operator fun minus(other: StudioTransfer) = StudioTransfer(real - other.real, imaginary - other.imaginary)
    operator fun times(scale: Double) = StudioTransfer(real * scale, imaginary * scale)
    fun magnitude() = hypot(real, imaginary)
}

private fun onePoleTransfer(frequencyHz: Double, cutoffHz: Double, sampleRateHz: Int): StudioTransfer {
    val alpha = 1.0 - kotlin.math.exp(-2.0 * Math.PI * cutoffHz / sampleRateHz)
    val pole = 1.0 - alpha
    val omega = 2.0 * Math.PI * frequencyHz / sampleRateHz
    val denominatorReal = 1.0 - pole * kotlin.math.cos(omega)
    val denominatorImaginary = pole * kotlin.math.sin(omega)
    val denominatorMagnitudeSquared = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
    return StudioTransfer(
        real = alpha * denominatorReal / denominatorMagnitudeSquared,
        imaginary = -alpha * denominatorImaginary / denominatorMagnitudeSquared,
    )
}

internal fun dspStudioToneResponseDb(
    frequencyHz: Double,
    macroTone: DspMacroToneConfig,
    smartLoudness: DspSmartLoudnessRuntimeConfig,
    sampleRateHz: Int,
): Double {
    require(sampleRateHz in 8_000..192_000)
    if (frequencyHz <= 0.0) return 0.0
    val unity = StudioTransfer(1.0, 0.0)
    val macroBassGain = if (macroTone.enabled) 10.0.pow(macroTone.bassGainDb / 20.0) else 1.0
    val macroVocalGain = if (macroTone.enabled) 10.0.pow(macroTone.vocalGainDb / 20.0) else 1.0
    val macroAirGain = if (macroTone.enabled) 10.0.pow(macroTone.airGainDb / 20.0) else 1.0
    val airCutoffHz = minOf(8_000.0, sampleRateHz * 0.4)
    val macro = unity +
        onePoleTransfer(frequencyHz, 180.0, sampleRateHz) * (macroBassGain - 1.0) +
        (onePoleTransfer(frequencyHz, 4_000.0, sampleRateHz) -
            onePoleTransfer(frequencyHz, 500.0, sampleRateHz)) * (macroVocalGain - 1.0) +
        (unity - onePoleTransfer(frequencyHz, airCutoffHz, sampleRateHz)) * (macroAirGain - 1.0)
    val loudnessLowGain = if (smartLoudness.enabled) 10.0.pow(smartLoudness.lowGainDb / 20.0) else 1.0
    val loudnessHighGain = if (smartLoudness.enabled) 10.0.pow(smartLoudness.highGainDb / 20.0) else 1.0
    val loudness = unity +
        onePoleTransfer(frequencyHz, 100.0, sampleRateHz) * (loudnessLowGain - 1.0) +
        (unity - onePoleTransfer(frequencyHz, airCutoffHz, sampleRateHz)) * (loudnessHighGain - 1.0)
    return 20.0 * log10((macro.magnitude() * loudness.magnitude()).coerceAtLeast(1e-12))
}

@Composable
private fun EqResponseGraph(
    bands: List<DspEqBand>,
    editableBandCount: Int,
    selectedBandIndex: Int,
    preampDb: Float,
    sampleRateHz: Int,
    macroTone: DspMacroToneConfig,
    smartLoudness: DspSmartLoudnessRuntimeConfig,
    modifier: Modifier,
    onSelectBand: (Int) -> Unit,
    onBandEdit: (Int, DspEqBand) -> Unit,
    onEditFinished: () -> Unit,
) {
    val colorScheme = androidx.compose.material3.MaterialTheme.colorScheme
    val curveColor = colorScheme.primary
    val toneCurveColor = colorScheme.secondary
    val combinedCurveColor = colorScheme.tertiary
    val maxFrequencyHz = minOf(20_000.0, sampleRateHz * 0.45)
    val responseSamples = remember(bands, preampDb, macroTone, smartLoudness, sampleRateHz) {
        (0..160).map { index ->
            val frequencyHz = 20.0 * (maxFrequencyHz / 20.0).pow(index / 160.0)
            EqGraphResponseSample(
                frequencyHz = frequencyHz,
                eqDb = dspEqResponseDb(frequencyHz, bands, preampDb.toDouble(), sampleRateHz),
                toneDb = dspStudioToneResponseDb(frequencyHz, macroTone, smartLoudness, sampleRateHz),
            )
        }
    }
    val currentBands by rememberUpdatedState(bands)
    val currentOnSelectBand by rememberUpdatedState(onSelectBand)
    val currentOnBandEdit by rememberUpdatedState(onBandEdit)
    val currentOnEditFinished by rememberUpdatedState(onEditFinished)
    val currentEditableBandCount by rememberUpdatedState(editableBandCount)
    val currentSelectedBandIndex by rememberUpdatedState(selectedBandIndex)
    val graphDescription = stringResource(
        R.string.dsp_eq_graph_description,
        maxFrequencyHz.roundToInt(),
        sampleRateHz,
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val axisLeftPx = with(density) { 30.dp.toPx() }
    val axisRightPx = with(density) { 4.dp.toPx() }
    val axisTopPx = with(density) { 4.dp.toPx() }
    val axisBottomPx = with(density) { 18.dp.toPx() }
    val axisTextSizePx = with(density) { 9.sp.toPx() }
    val hitRadiusPx = with(density) { 32.dp.toPx() }
    val graphInputModifier = Modifier
        .pointerInput(sampleRateHz, hitRadiusPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val index = nearestEditableBand(
                    currentBands,
                    currentEditableBandCount,
                    currentSelectedBandIndex,
                    down.position,
                    axisLeftPx,
                    axisTopPx,
                    size.width.toFloat() - axisRightPx,
                    size.height.toFloat() - axisBottomPx,
                    maxFrequencyHz,
                    hitRadiusPx,
                )
                if (index >= 0) {
                    currentOnSelectBand(index)
                    val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (dragStart != null) {
                        var edited = false
                        try {
                            editGraphBandAt(
                                index,
                                dragStart.position,
                                currentBands,
                                axisLeftPx,
                                axisTopPx,
                                size.width.toFloat() - axisRightPx,
                                size.height.toFloat() - axisBottomPx,
                                maxFrequencyHz,
                                currentOnBandEdit,
                            )
                            edited = true
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.id != down.id && it.pressed }) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                editGraphBandAt(
                                    index,
                                    change.position,
                                    currentBands,
                                    axisLeftPx,
                                    axisTopPx,
                                    size.width.toFloat() - axisRightPx,
                                    size.height.toFloat() - axisBottomPx,
                                    maxFrequencyHz,
                                    currentOnBandEdit,
                                )
                                if (change.pressed) {
                                    change.consume()
                                } else {
                                    break
                                }
                            }
                        } finally {
                            if (edited) currentOnEditFinished()
                        }
                    }
                }
            }
        }
    Canvas(modifier.then(graphInputModifier).semantics {
        contentDescription = graphDescription
    }) {
        val lowDb = -18f
        val highDb = 18f
        val maxFrequencyHz = minOf(20_000.0, sampleRateHz * 0.45)
        val plotLeft = axisLeftPx
        val plotTop = axisTopPx
        val plotRight = size.width - axisRightPx
        val plotBottom = size.height - axisBottomPx
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val frequencyTicks = listOf(20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000)
            .filter { it <= maxFrequencyHz }
        val gainTicks = listOf(18, 12, 6, 0, -6, -12, -18)
        val gridColor = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.28f)
        frequencyTicks.forEach { frequencyHz ->
            val fraction = (ln(frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val x = plotLeft + plotWidth * fraction
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), 1.dp.toPx())
        }
        gainTicks.forEach { gainDb ->
            val y = plotTop + plotHeight * (highDb - gainDb) / (highDb - lowDb)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.dp.toPx())
        }
        val axisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = curveColor.toArgb()
            textSize = axisTextSizePx
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val yAxisPaint = android.graphics.Paint(axisPaint).apply { textAlign = android.graphics.Paint.Align.RIGHT }
        val labelBaseline = size.height - 2.dp.toPx()
        gainTicks.forEach { gainDb ->
            val y = plotTop + plotHeight * (highDb - gainDb) / (highDb - lowDb)
            val baseline = (y + axisTextSizePx * 0.35f).coerceIn(axisTextSizePx, plotBottom + axisTextSizePx)
            drawContext.canvas.nativeCanvas.drawText(gainDb.toString(), plotLeft - 4.dp.toPx(), baseline, yAxisPaint)
        }
        frequencyTicks.forEach { frequencyHz ->
            val fraction = (ln(frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val x = plotLeft + plotWidth * fraction
            val label = if (frequencyHz >= 1_000) "${frequencyHz / 1_000}k" else frequencyHz.toString()
            val safeX = x.coerceIn(axisTextSizePx, size.width - axisTextSizePx)
            drawContext.canvas.nativeCanvas.drawText(label, safeX, labelBaseline, axisPaint)
        }
        val zeroY = plotTop + plotHeight * highDb / (highDb - lowDb)
        drawLine(androidx.compose.ui.graphics.Color.Gray, Offset(plotLeft, zeroY), Offset(plotRight, zeroY), 1.dp.toPx())
        val eqPath = Path()
        val tonePath = Path()
        val combinedPath = Path()
        responseSamples.forEachIndexed { index, sample ->
            val x = plotLeft + plotWidth *
                (ln(sample.frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val eqY = plotTop + plotHeight *
                (highDb - sample.eqDb.toFloat().coerceIn(lowDb, highDb)) / (highDb - lowDb)
            val toneY = plotTop + plotHeight *
                (highDb - sample.toneDb.toFloat().coerceIn(lowDb, highDb)) / (highDb - lowDb)
            val combinedY = plotTop + plotHeight *
                (highDb - (sample.eqDb + sample.toneDb).toFloat().coerceIn(lowDb, highDb)) / (highDb - lowDb)
            if (index == 0) {
                eqPath.moveTo(x, eqY)
                tonePath.moveTo(x, toneY)
                combinedPath.moveTo(x, combinedY)
            } else {
                eqPath.lineTo(x, eqY)
                tonePath.lineTo(x, toneY)
                combinedPath.lineTo(x, combinedY)
            }
        }
        drawPath(eqPath, curveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        drawPath(tonePath, toneCurveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        drawPath(combinedPath, combinedCurveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
        bands.take(editableBandCount).forEachIndexed { index, band ->
            val x = plotLeft + plotWidth * (ln(band.frequencyHz.coerceIn(20.0, maxFrequencyHz) / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val y = plotTop + plotHeight * (18.0 - band.gainDb.coerceIn(-18.0, 18.0)) / 36.0
            drawCircle(
                color = if (band.enabled) curveColor else androidx.compose.ui.graphics.Color.Gray,
                radius = if (index == currentSelectedBandIndex) 7.dp.toPx() else 5.dp.toPx(),
                center = Offset(x, y.toFloat()),
                style = if (index == currentSelectedBandIndex) androidx.compose.ui.graphics.drawscope.Fill else androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

internal fun nearestEditableBand(
    bands: List<DspEqBand>,
    editableBandCount: Int,
    selectedBandIndex: Int,
    position: Offset,
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    maxFrequencyHz: Double,
    hitRadiusPx: Float,
): Int {
    if (plotRight <= plotLeft || plotBottom <= plotTop) return -1
    val candidates = bands.take(editableBandCount).indices
        .map { index ->
            val band = bands[index]
            val x = plotLeft + (plotRight - plotLeft) * (ln(band.frequencyHz.coerceIn(20.0, maxFrequencyHz) / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat()
            val y = plotTop + (plotBottom - plotTop) * (18.0 - band.gainDb.coerceIn(-18.0, 18.0)) / 36.0
            index to (Offset(x, y.toFloat()) - position).getDistance()
        }
        .filter { it.second <= hitRadiusPx }
    val selectedDistance = candidates.firstOrNull { it.first == selectedBandIndex }?.second
    if (selectedDistance != null) return selectedBandIndex
    return candidates.minWithOrNull(compareBy<Pair<Int, Float>> { it.second }.thenBy { it.first })?.first ?: -1
}

private fun editGraphBandAt(
    index: Int,
    position: Offset,
    bands: List<DspEqBand>,
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    maxFrequencyHz: Double,
    onBandEdit: (Int, DspEqBand) -> Unit,
) {
    val band = bands.getOrNull(index) ?: return
    val frequency = eqGraphFrequencyAtX(position.x - plotLeft, plotRight - plotLeft, 20.0, maxFrequencyHz)
    val gain = if (band.type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
        eqGraphGainAtY(position.y - plotTop, plotBottom - plotTop)
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
private fun AccessibleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
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
            onValueChangeFinished = onFinished,
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
    onFinished: () -> Unit = {},
    onChange: (Double) -> Unit,
) {
    val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
    val toggleSignDescription = stringResource(R.string.dsp_eq_toggle_sign, label)
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
                    modifier = Modifier.semantics { contentDescription = toggleSignDescription },
                ) { Text("±") }
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            val wasFocused = focused
            focused = state.isFocused
            if (wasFocused && !state.isFocused) {
                text = formatEqNumericValue(value, decimalSeparator)
                onFinished()
            }
        },
    )
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) =
    AccessibleSlider(label, value, range, unit, onChange = onChange)

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
    onDelete: (DspModulePreset) -> Unit,
    onFactoryReset: () -> Unit,
    onCompareA: () -> Unit,
    onCompareB: () -> Unit,
) {
    var moduleMenuExpanded by remember { mutableStateOf(false) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var saveDialogVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var resetDialogVisible by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dsp_module_preset_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dsp_module_label), Modifier.weight(1f))
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
                Text(stringResource(R.string.dsp_preset_label), Modifier.weight(1f))
                Box {
                    OutlinedButton(
                        onClick = { presetMenuExpanded = true },
                        enabled = enabled && presets.isNotEmpty(),
                    ) {
                        Text(selectedPreset?.let {
                            stringResource(
                                R.string.dsp_preset_name_with_type,
                                it.name,
                                stringResource(if (it.factory) R.string.dsp_preset_factory else R.string.dsp_preset_user),
                            ) + " ▾"
                        } ?: stringResource(R.string.dsp_module_preset_custom_unsaved))
                    }
                    DropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false },
                    ) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(
                                        R.string.dsp_preset_name_with_type,
                                        preset.name,
                                        stringResource(if (preset.factory) R.string.dsp_preset_factory else R.string.dsp_preset_user),
                                    ))
                                },
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
                    Text(stringResource(R.string.dsp_module_preset_apply_draft))
                }
                OutlinedButton(onClick = { saveDialogVisible = true }, enabled = enabled) {
                    Text(stringResource(R.string.dsp_module_preset_save_as))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { resetDialogVisible = true },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.dsp_module_preset_reset_action))
                }
                OutlinedButton(
                    onClick = { deleteDialogVisible = true },
                    enabled = enabled && selectedPreset != null && !selectedPreset.factory,
                ) {
                    Text(stringResource(R.string.dsp_module_preset_delete_action))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCompareA, enabled = enabled) {
                    Text(stringResource(R.string.dsp_module_compare_a))
                }
                OutlinedButton(onClick = onCompareB, enabled = enabled) {
                    Text(stringResource(R.string.dsp_module_compare_b))
                }
            }
            Text(
                stringResource(R.string.dsp_module_preset_apply_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (saveDialogVisible) {
        AlertDialog(
            onDismissRequest = { saveDialogVisible = false },
            title = { Text(stringResource(R.string.dsp_module_preset_save_title, modulePresetLabel(moduleKey))) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { if (it.length <= 48) newPresetName = it },
                    label = { Text(stringResource(R.string.dsp_module_preset_name)) },
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
                ) { Text(stringResource(R.string.dsp_save)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { saveDialogVisible = false }) { Text(stringResource(R.string.dsp_cancel)) }
            },
        )
    }

    if (deleteDialogVisible && selectedPreset != null && !selectedPreset.factory) {
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            title = { Text(stringResource(R.string.dsp_module_preset_delete_title)) },
            text = {
                Text(stringResource(R.string.dsp_module_preset_delete_message, selectedPreset.name))
            },
            confirmButton = {
                Button(onClick = {
                    deleteDialogVisible = false
                    onDelete(selectedPreset)
                }) { Text(stringResource(R.string.dsp_module_preset_delete_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteDialogVisible = false }) { Text(stringResource(R.string.dsp_cancel)) }
            },
        )
    }

    if (resetDialogVisible) {
        AlertDialog(
            onDismissRequest = { resetDialogVisible = false },
            title = { Text(stringResource(R.string.dsp_module_preset_reset_title)) },
            text = {
                Text(stringResource(R.string.dsp_module_preset_reset_message))
            },
            confirmButton = {
                Button(onClick = {
                    resetDialogVisible = false
                    onFactoryReset()
                }) { Text(stringResource(R.string.dsp_module_preset_reset_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { resetDialogVisible = false }) { Text(stringResource(R.string.dsp_cancel)) }
            },
        )
    }
}

@Composable
private fun modulePresetLabel(key: String): String = stringResource(when (key) {
    "peq" -> R.string.dsp_module_peq
    "bass" -> R.string.dsp_module_bass
    "seat" -> R.string.dsp_module_seat
    "advanced_ms" -> R.string.dsp_module_advanced_ms
    "compressor" -> R.string.dsp_module_compressor
    "multiband" -> R.string.dsp_module_multiband
    "dynamic_eq" -> R.string.dsp_module_dynamic_eq
    "harmonic" -> R.string.dsp_module_harmonic
    "limiter" -> R.string.dsp_module_limiter
    "convolver" -> R.string.dsp_module_convolver
    "cabin" -> R.string.dsp_module_cabin
    "smart_loudness" -> R.string.dsp_module_smart_loudness
    "dynamic_bass" -> R.string.dsp_module_dynamic_bass
    "macros" -> R.string.dsp_macro_module_label
    else -> R.string.dsp_module_label
})
