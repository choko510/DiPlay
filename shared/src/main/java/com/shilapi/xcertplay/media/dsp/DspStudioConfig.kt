package com.shilapi.xcertplay.media.dsp

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

data class DspMacroToneConfig(
    val enabled: Boolean = false,
    val bassGainDb: Double = 0.0,
    val vocalGainDb: Double = 0.0,
    val airGainDb: Double = 0.0,
    val relativeWidth: Double = 1.0,
) {
    init {
        require(bassGainDb.isFinite() && bassGainDb in -2.0..4.0)
        require(vocalGainDb.isFinite() && vocalGainDb in -2.0..2.5)
        require(airGainDb.isFinite() && airGainDb in -2.0..2.0)
        require(relativeWidth.isFinite() && relativeWidth in 0.75..1.25)
    }
}

private fun onePoleAlpha(frequencyHz: Double, sampleRate: Int): Double =
    1.0 - exp(-2.0 * Math.PI * frequencyHz / sampleRate)

private inline fun scanOnePolePeakDb(sampleRate: Int, crossinline gainAtOmega: (omega: Double) -> Double): Double {
    val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
    val logMin = kotlin.math.ln(20.0)
    val logMax = kotlin.math.ln(maxFrequency)
    var maxMagnitude = 1.0
    for (point in 0 until 64) {
        val frequency = exp(logMin + (logMax - logMin) * (point / 63.0))
        val omega = 2.0 * Math.PI * frequency / sampleRate
        val mag = gainAtOmega(omega)
        if (mag > maxMagnitude) maxMagnitude = mag
    }
    return 20.0 * log10(maxMagnitude)
}

private fun onePoleComplex(alpha: Double, omega: Double): Pair<Double, Double> {
    val pole = 1.0 - alpha
    val dReal = 1.0 - pole * cos(omega)
    val dImag = pole * sin(omega)
    val dMagSq = dReal * dReal + dImag * dImag
    return (alpha * dReal / dMagSq) to (-alpha * dImag / dMagSq)
}

internal fun DspMacroToneConfig.conservativeBoostDb(sampleRate: Int = 48_000): Double {
    if (!enabled) return 0.0
    val bassGain = 10.0.pow(bassGainDb / 20.0) - 1.0
    val vocalGain = 10.0.pow(vocalGainDb / 20.0) - 1.0
    val airGain = 10.0.pow(airGainDb / 20.0) - 1.0
    val bassAlpha = onePoleAlpha(180.0, sampleRate)
    val vocalLowAlpha = onePoleAlpha(500.0, sampleRate)
    val vocalHighAlpha = onePoleAlpha(4_000.0, sampleRate)
    val airAlpha = onePoleAlpha(minOf(8_000.0, sampleRate * 0.4), sampleRate)
    return scanOnePolePeakDb(sampleRate) { omega ->
        val (bRe, bIm) = onePoleComplex(bassAlpha, omega)
        val (vlRe, vlIm) = onePoleComplex(vocalLowAlpha, omega)
        val (vhRe, vhIm) = onePoleComplex(vocalHighAlpha, omega)
        val (aRe, aIm) = onePoleComplex(airAlpha, omega)
        val re = 1.0 + bassGain * bRe + vocalGain * (vhRe - vlRe) + airGain * (1.0 - aRe)
        val im = bassGain * bIm + vocalGain * (vhIm - vlIm) - airGain * aIm
        kotlin.math.hypot(re, im)
    }
}

data class DspSmartLoudnessRuntimeConfig(
    val enabled: Boolean = false,
    val lowGainDb: Double = 0.0,
    val highGainDb: Double = 0.0,
) {
    init {
        require(lowGainDb.isFinite() && lowGainDb in 0.0..3.0)
        require(highGainDb.isFinite() && highGainDb in 0.0..1.5)
    }
}

internal fun DspSmartLoudnessRuntimeConfig.conservativeBoostDb(sampleRate: Int = 48_000): Double {
    if (!enabled) return 0.0
    val lowGain = 10.0.pow(lowGainDb / 20.0) - 1.0
    val highGain = 10.0.pow(highGainDb / 20.0) - 1.0
    val lowAlpha = onePoleAlpha(100.0, sampleRate)
    val highAlpha = onePoleAlpha(minOf(8_000.0, sampleRate * 0.4), sampleRate)
    return scanOnePolePeakDb(sampleRate) { omega ->
        val (lRe, lIm) = onePoleComplex(lowAlpha, omega)
        val (hRe, hIm) = onePoleComplex(highAlpha, omega)
        val re = 1.0 + lowGain * lRe + highGain * (1.0 - hRe)
        val im = lowGain * lIm - highGain * hIm
        kotlin.math.hypot(re, im)
    }
}

data class DspDynamicBassRuntimeConfig(
    val enabled: Boolean = false,
    val strength: Double = 0.5,
    val maxBoostDb: Double = 3.0,
    val detectorCutoffHz: Double = 120.0,
    val silenceGateDbfs: Double = -55.0,
    val lowThresholdDbfs: Double = -35.0,
    val highThresholdDbfs: Double = -10.0,
    val attackMs: Double = 40.0,
    val releaseMs: Double = 250.0,
) {
    init {
        require(strength.isFinite() && strength in 0.0..1.0)
        require(maxBoostDb.isFinite() && maxBoostDb in 0.0..3.0)
        require(detectorCutoffHz.isFinite() && detectorCutoffHz in 80.0..160.0)
        require(silenceGateDbfs.isFinite() && silenceGateDbfs in -90.0..-30.0)
        require(lowThresholdDbfs.isFinite() && lowThresholdDbfs in -60.0..0.0)
        require(highThresholdDbfs.isFinite() && highThresholdDbfs in -60.0..0.0)
        require(lowThresholdDbfs < highThresholdDbfs)
        require(attackMs.isFinite() && attackMs in 10.0..200.0)
        require(releaseMs.isFinite() && releaseMs in 50.0..1_000.0)
    }
}
