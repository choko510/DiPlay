package com.shilapi.xcertplay.dsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import kotlin.math.ln
import kotlin.math.pow

/** Reusable editor for the profile's existing 15-band PEQ and approved Cabin EQ. */
@Composable
fun DspEqEditorPane(
    profile: DspAudioProfile,
    cabinCorrection: DspCabinCorrectionConfig = profile.cabinCorrection,
    onProfileChange: (DspAudioProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cabinBands = cabinCorrection.approvedEqBands()
    val activeTotal = activeEqBandCount(profile.eqBands, cabinBands)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Parametric EQ · $activeTotal/${DspAudioProfile.MAX_EQ_BANDS} total active · ${cabinBands.size} Cabin bands")
        AccessibleSlider("Preamp", profile.preampDb.toFloat(), -24f..12f, "dB") {
            onProfileChange(profile.copy(preampDb = it.toDouble()))
        }
        EqResponseGraph(profile.eqBands + cabinBands, profile.preampDb.toFloat(), Modifier.fillMaxWidth().height(150.dp))
        profile.eqBands.forEachIndexed { index, band ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PEQ band ${index + 1} · ${band.type.name.replace('_', ' ')}", Modifier.weight(1f))
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
                    AccessibleSlider("Band ${index + 1} frequency", band.frequencyHz.toFloat(), 20f..20_000f, "Hz") {
                        updateBand(profile, index, band.copy(frequencyHz = it.toDouble()), onProfileChange)
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
                        AccessibleSlider("Band ${index + 1} gain", band.gainDb.toFloat(), -18f..18f, "dB") {
                            updateBand(profile, index, band.copy(gainDb = it.toDouble()), onProfileChange)
                        }
                    }
                    if (band.type in setOf(DspEqType.PEAK, DspEqType.HIGH_PASS, DspEqType.LOW_PASS, DspEqType.NOTCH)) {
                        AccessibleSlider("Band ${index + 1} Q", band.q.toFloat(), 0.1f..20f, "Q") {
                            updateBand(profile, index, band.copy(q = it.toDouble()), onProfileChange)
                        }
                    }
                }
            }
        }
    }
}

private fun updateBand(
    profile: DspAudioProfile,
    index: Int,
    band: DspEqBand,
    onChange: (DspAudioProfile) -> Unit,
) {
    val next = profile.eqBands.toMutableList().also { it[index] = band }
    onChange(profile.copy(eqBands = next))
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

internal fun dspEqResponseDb(frequencyHz: Double, bands: List<DspEqBand>, preampDb: Double = 0.0): Double {
    if (frequencyHz <= 0.0) return preampDb
    return preampDb + bands.filter(DspEqBand::enabled).sumOf { band ->
        if (band.type != DspEqType.PEAK) return@sumOf 0.0
        val octaveDistance = ln(frequencyHz / band.frequencyHz) / ln(2.0)
        band.gainDb * kotlin.math.exp(-0.5 * (2.0 * band.q * octaveDistance).let { it * it })
    }
}

@Composable
private fun EqResponseGraph(bands: List<DspEqBand>, preampDb: Float, modifier: Modifier) {
    val curveColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "Equalizer frequency response graph" }) {
        val lowDb = -18f
        val highDb = 18f
        val points = (0..160).map { i ->
            val hz = 20.0 * 1_000.0.pow(i / 160.0)
            val x = size.width * i / 160f
            val response = dspEqResponseDb(hz, bands, preampDb.toDouble()).toFloat().coerceIn(lowDb, highDb)
            CurvePoint(x, size.height * (highDb - response) / (highDb - lowDb))
        }
        val zeroY = size.height * highDb / (highDb - lowDb)
        drawLine(androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.geometry.Offset(0f, zeroY), androidx.compose.ui.geometry.Offset(size.width, zeroY), 1.dp.toPx())
        val path = Path().apply { points.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
        drawPath(path, curveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
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
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) =
    AccessibleSlider(label, value, range, unit, onChange)
