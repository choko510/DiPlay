package com.shilapi.xcertplay.media.dsp

import java.util.Collections
import kotlin.math.log10

class DspRuntimeConfig(
    val enabled: Boolean,
    val gainDb: Double = 0.0,
    peqBands: List<DspEqBand> = emptyList(),
    val autoHeadroomEnabled: Boolean = true,
    val autoHeadroomMarginDb: Double = 1.0,
    val bass: DspBassConfig = DspBassConfig(),
    val compressor: DspCompressorConfig = DspCompressorConfig(),
    val safetyLimiter: DspSafetyLimiterConfig = DspSafetyLimiterConfig(),
    val stereoWidth: Double = 1.0,
    val monoBass: DspMonoBassConfig = DspMonoBassConfig(),
    val convolver: DspConvolverConfig = DspConvolverConfig(),
    val multiband: DspMultibandConfig = DspMultibandConfig(),
    val dynamicEq: DspDynamicEqConfig = DspDynamicEqConfig(),
    val seatAlignment: DspSeatAlignmentConfig = DspSeatAlignmentConfig(),
    val advancedMs: DspAdvancedMsConfig = DspAdvancedMsConfig(),
    val cabinCorrection: DspCabinCorrectionConfig = DspCabinCorrectionConfig(),
    val harmonic: DspHarmonicConfig = DspHarmonicConfig(),
) {
    val peqBands: List<DspEqBand> = Collections.unmodifiableList(ArrayList(peqBands))
    val preampDb: Double
        get() = gainDb

    internal fun withConvolver(convolver: DspConvolverConfig): DspRuntimeConfig = DspRuntimeConfig(
        enabled = enabled,
        gainDb = gainDb,
        peqBands = peqBands,
        autoHeadroomEnabled = autoHeadroomEnabled,
        autoHeadroomMarginDb = autoHeadroomMarginDb,
        bass = bass,
        compressor = compressor,
        safetyLimiter = safetyLimiter,
        seatAlignment = seatAlignment,
        stereoWidth = stereoWidth,
        monoBass = monoBass,
        convolver = convolver,
        multiband = multiband,
        dynamicEq = dynamicEq,
        advancedMs = advancedMs,
        cabinCorrection = cabinCorrection,
        harmonic = harmonic,
    )

    init {
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(this.peqBands.size <= DspEqDesigner.MAX_BANDS)
        val activeEqBandCount = this.peqBands.count(DspEqBand::enabled) +
            cabinCorrection.approvedEqBands().count(DspEqBand::enabled)
        require(activeEqBandCount <= MAX_EQ_BANDS) {
            "PEQ_CAPACITY_EXCEEDED: $activeEqBandCount active bands exceeds $MAX_EQ_BANDS"
        }
        require(autoHeadroomMarginDb.isFinite() && autoHeadroomMarginDb in 0.0..MAX_HEADROOM_MARGIN_DB)
        require(stereoWidth.isFinite() && stereoWidth in 0.0..MAX_STEREO_WIDTH)
    }

    companion object {
        const val MAX_EQ_BANDS = 15
        fun disabled(): DspRuntimeConfig = DspRuntimeConfig(enabled = false)

        private const val MIN_GAIN_DB = -120.0
        private const val MAX_GAIN_DB = 24.0
        private const val MAX_HEADROOM_MARGIN_DB = 12.0
        private const val MAX_STEREO_WIDTH = 2.0
    }
}

internal fun DspRuntimeConfig.failureFallbackPreampDb(format: DspAudioFormat): Double {
    if (!enabled || !autoHeadroomEnabled || format.channels != 2) return 0.0

    val seatTrimDb = seatAlignment.positiveTrimForHeadroomDb
    if (seatTrimDb <= 0.0) return 0.0

    var eqBoostDb = 0.0
    for (band in peqBands) {
        if (band.enabled) eqBoostDb += band.gainDb.coerceAtLeast(0.0)
    }
    val bassBoostDb = if (bass.enabled) bass.gainDb.coerceAtLeast(0.0) else 0.0
    val compressorMakeupDb = if (compressor.enabled) compressor.makeupDb.coerceAtLeast(0.0) else 0.0
    val multibandMakeupDb = if (multiband.enabled) {
        maxOf(
            if (multiband.low.enabled) multiband.low.makeupDb else 0.0,
            if (multiband.mid.enabled) multiband.mid.makeupDb else 0.0,
            if (multiband.high.enabled) multiband.high.makeupDb else 0.0,
        ).coerceAtLeast(0.0)
    } else {
        0.0
    }
    var dynamicBoostDb = 0.0
    if (dynamicEq.enabled) {
        for (band in dynamicEq.bands) {
            if (band.enabled && band.mode == DspDynamicEqMode.BOOST) {
                dynamicBoostDb += band.maxBoostDb
            }
        }
    }
    val stereoWidthDb = if (advancedMs.enabled) 0.0 else 20.0 * log10(maxOf(1.0, stereoWidth))
    val advancedMsBoostDb = advancedMs.worstCaseBoostDb
    val harmonicBoostDb = harmonic.worstCaseBoostDb.coerceAtLeast(0.0)
    val worstCasePeakDb = gainDb + eqBoostDb + bassBoostDb + compressorMakeupDb +
        multibandMakeupDb + dynamicBoostDb + seatTrimDb + stereoWidthDb +
        advancedMsBoostDb + harmonicBoostDb + autoHeadroomMarginDb
    val reductionDb = worstCasePeakDb.coerceAtLeast(0.0)
    return (gainDb - reductionDb).coerceIn(-700.0, 0.0)
}
