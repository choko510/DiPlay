package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig

data class DspAudioProfile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val autoHeadroomEnabled: Boolean = true,
    val autoHeadroomMarginDb: Double = 1.0,
    val eqBands: List<DspEqBand> = DspProfilePresets.flatBands(),
    val bass: DspBassConfig = DspBassConfig(),
    val compressor: DspCompressorConfig = DspCompressorConfig(),
    val stereoWidth: Double = 1.0,
    val monoBass: DspMonoBassConfig = DspMonoBassConfig(),
    val convolver: DspConvolverConfig = DspConvolverConfig(),
    val limiter: DspSafetyLimiterConfig = DspSafetyLimiterConfig(),
) {
    init {
        require(ID_PATTERN.matches(id))
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(preampDb.isFinite() && preampDb in MIN_PREAMP_DB..MAX_PREAMP_DB)
        require(autoHeadroomMarginDb.isFinite() && autoHeadroomMarginDb in 0.0..MAX_HEADROOM_MARGIN_DB)
        require(eqBands.size <= MAX_EQ_BANDS)
        require(stereoWidth.isFinite() && stereoWidth in 0.0..MAX_STEREO_WIDTH)
    }

    fun toRuntimeConfig(masterEnabled: Boolean): DspRuntimeConfig = DspRuntimeConfig(
        enabled = masterEnabled && enabled,
        gainDb = preampDb,
        peqBands = eqBands,
        autoHeadroomEnabled = autoHeadroomEnabled,
        autoHeadroomMarginDb = autoHeadroomMarginDb,
        bass = bass,
        compressor = compressor,
        safetyLimiter = limiter,
        stereoWidth = stereoWidth,
        monoBass = monoBass,
        convolver = convolver,
    )

    companion object {
        const val MAX_EQ_BANDS = 15
        private const val MAX_NAME_LENGTH = 48
        private const val MIN_PREAMP_DB = -120.0
        private const val MAX_PREAMP_DB = 24.0
        private const val MAX_HEADROOM_MARGIN_DB = 12.0
        private const val MAX_STEREO_WIDTH = 2.0
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

object DspProfilePresets {
    val presetIds = listOf("flat", "daily", "vocal", "bass", "highway", "night", "custom1", "custom2")

    fun all(): List<DspAudioProfile> = listOf(
        DspAudioProfile(id = "flat", name = "Flat"),
        DspAudioProfile(
            id = "daily",
            name = "Daily",
            eqBands = flatBands().withGain(125.0, 1.5).withGain(4_000.0, 1.0),
        ),
        DspAudioProfile(
            id = "vocal",
            name = "Vocal",
            eqBands = flatBands().withGain(1_000.0, 2.0).withGain(3_000.0, 2.0),
        ),
        DspAudioProfile(
            id = "bass",
            name = "Bass",
            bass = DspBassConfig(enabled = true, gainDb = 4.0, frequencyHz = 80.0),
        ),
        DspAudioProfile(
            id = "highway",
            name = "Highway",
            eqBands = flatBands().withGain(63.0, 1.5).withGain(4_000.0, 1.5),
        ),
        DspAudioProfile(
            id = "night",
            name = "Night",
            compressor = DspCompressorConfig(
                enabled = true,
                thresholdDb = -24.0,
                ratio = 3.0,
                attackMs = 15.0,
                releaseMs = 180.0,
                kneeDb = 6.0,
                makeupDb = 2.0,
            ),
        ),
        customProfile("custom1"),
        customProfile("custom2"),
    )

    fun find(id: String): DspAudioProfile? = all().firstOrNull { it.id == id }

    fun flatBands(): List<DspEqBand> = BAND_CENTERS_HZ.map { frequency ->
        DspEqBand(
            type = DspEqType.PEAK,
            frequencyHz = frequency.toDouble(),
            gainDb = 0.0,
            q = 1.0,
            enabled = false,
        )
    }

    fun customProfile(id: String): DspAudioProfile = DspAudioProfile(id = id, name = id.replaceFirstChar { it.uppercase() })

    private fun List<DspEqBand>.withGain(frequencyHz: Double, gainDb: Double): List<DspEqBand> = map { band ->
        if (band.frequencyHz == frequencyHz) band.copy(enabled = true, gainDb = gainDb) else band
    }

    private val BAND_CENTERS_HZ = listOf(31, 63, 125, 250, 500, 1_000, 2_000, 3_000, 4_000, 6_000, 8_000, 10_000, 12_000, 14_000, 16_000)
}
