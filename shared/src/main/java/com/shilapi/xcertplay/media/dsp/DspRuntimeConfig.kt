package com.shilapi.xcertplay.media.dsp

import java.util.Collections

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
) {
    val peqBands: List<DspEqBand> = Collections.unmodifiableList(ArrayList(peqBands))
    val preampDb: Double
        get() = gainDb

    init {
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(this.peqBands.size <= DspEqDesigner.MAX_BANDS)
        require(autoHeadroomMarginDb.isFinite() && autoHeadroomMarginDb in 0.0..MAX_HEADROOM_MARGIN_DB)
        require(stereoWidth.isFinite() && stereoWidth in 0.0..MAX_STEREO_WIDTH)
    }

    companion object {
        fun disabled(): DspRuntimeConfig = DspRuntimeConfig(enabled = false)

        private const val MIN_GAIN_DB = -120.0
        private const val MAX_GAIN_DB = 24.0
        private const val MAX_HEADROOM_MARGIN_DB = 12.0
        private const val MAX_STEREO_WIDTH = 2.0
    }
}
