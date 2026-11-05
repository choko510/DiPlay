package com.shilapi.xcertplay.media.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DspBiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    fun writeTo(destination: DoubleArray, offset: Int) {
        destination[offset] = b0
        destination[offset + 1] = b1
        destination[offset + 2] = b2
        destination[offset + 3] = a1
        destination[offset + 4] = a2
    }

    fun magnitudeAt(sampleRate: Int, frequencyHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val cosine2 = cos(2.0 * omega)
        val sine2 = sin(2.0 * omega)
        val numerator = hypot(
            b0 + b1 * cosine + b2 * cosine2,
            -(b1 * sine + b2 * sine2),
        )
        val denominator = hypot(
            1.0 + a1 * cosine + a2 * cosine2,
            -(a1 * sine + a2 * sine2),
        )
        return numerator / denominator
    }
}

internal object DspEqDesigner {
    const val MAX_BANDS = DspRuntimeConfig.MAX_EQ_BANDS
    const val MAX_GRAPH_FILTERS = MAX_BANDS + 1
    const val COEFFICIENTS_PER_BAND = 5
    private const val MIN_FREQUENCY_HZ = 20.0
    private const val MAX_FREQUENCY_HZ = 20_000.0
    private const val MIN_Q = 0.1
    private const val MAX_Q = 20.0
    private const val MIN_GAIN_DB = -18.0
    private const val MAX_GAIN_DB = 18.0

    fun design(sampleRate: Int, band: DspEqBand): DspBiquadCoefficients {
        require(sampleRate in 8_000..192_000)
        val maxFrequency = minOf(MAX_FREQUENCY_HZ, sampleRate * 0.45)
        require(band.frequencyHz in MIN_FREQUENCY_HZ..maxFrequency)
        require(band.gainDb in MIN_GAIN_DB..MAX_GAIN_DB)
        require(band.q in MIN_Q..MAX_Q)

        val omega = 2.0 * PI * band.frequencyHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * band.q)
        val a = 10.0.pow(band.gainDb / 40.0)
        val raw = when (band.type) {
            DspEqType.PEAK -> doubleArrayOf(
                1.0 + alpha * a,
                -2.0 * cosine,
                1.0 - alpha * a,
                1.0 + alpha / a,
                -2.0 * cosine,
                1.0 - alpha / a,
            )
            DspEqType.LOW_SHELF -> shelf(cosine, sine, a, low = true)
            DspEqType.HIGH_SHELF -> shelf(cosine, sine, a, low = false)
            DspEqType.HIGH_PASS -> doubleArrayOf(
                (1.0 + cosine) / 2.0,
                -(1.0 + cosine),
                (1.0 + cosine) / 2.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.LOW_PASS -> doubleArrayOf(
                (1.0 - cosine) / 2.0,
                1.0 - cosine,
                (1.0 - cosine) / 2.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.NOTCH -> doubleArrayOf(
                1.0,
                -2.0 * cosine,
                1.0,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
            DspEqType.ALL_PASS -> doubleArrayOf(
                1.0 - alpha,
                -2.0 * cosine,
                1.0 + alpha,
                1.0 + alpha,
                -2.0 * cosine,
                1.0 - alpha,
            )
        }
        val a0 = raw[3]
        require(a0.isFinite() && a0 != 0.0)
        val normalized = DspBiquadCoefficients(
            b0 = raw[0] / a0,
            b1 = raw[1] / a0,
            b2 = raw[2] / a0,
            a1 = raw[4] / a0,
            a2 = raw[5] / a0,
        )
        require(
            normalized.b0.isFinite() && normalized.b1.isFinite() && normalized.b2.isFinite() &&
                normalized.a1.isFinite() && normalized.a2.isFinite()
        )
        return normalized
    }

    private fun shelf(cosine: Double, sine: Double, a: Double, low: Boolean): DoubleArray {
        val alpha = sine / 2.0 * sqrt(2.0)
        val beta = 2.0 * sqrt(a) * alpha
        return if (low) {
            doubleArrayOf(
                a * ((a + 1.0) - (a - 1.0) * cosine + beta),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cosine),
                a * ((a + 1.0) - (a - 1.0) * cosine - beta),
                (a + 1.0) + (a - 1.0) * cosine + beta,
                -2.0 * ((a - 1.0) + (a + 1.0) * cosine),
                (a + 1.0) + (a - 1.0) * cosine - beta,
            )
        } else {
            doubleArrayOf(
                a * ((a + 1.0) + (a - 1.0) * cosine + beta),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine),
                a * ((a + 1.0) + (a - 1.0) * cosine - beta),
                (a + 1.0) - (a - 1.0) * cosine + beta,
                2.0 * ((a - 1.0) - (a + 1.0) * cosine),
                (a + 1.0) - (a - 1.0) * cosine - beta,
            )
        }
    }
}

object DspEqResponse {
    fun magnitudeAt(sampleRate: Int, frequencyHz: Double, band: DspEqBand): Double {
        require(sampleRate in 8_000..192_000)
        val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
        require(frequencyHz.isFinite() && frequencyHz in 20.0..maxFrequency)
        return DspEqDesigner.design(sampleRate, band).magnitudeAt(sampleRate, frequencyHz)
    }
}

internal data class DspHeadroomResult(
    val filterPeakDb: Double,
    val reductionDb: Double,
)

internal object DspAutoHeadroom {
    private const val SCAN_POINTS = 1024

    fun calculate(
        sampleRate: Int,
        preampDb: Double,
        peqBands: List<DspEqBand>,
        compressorMakeupDb: Double = 0.0,
        maxBandMakeupDb: Double = 0.0,
        dynamicBoostDb: Double = 0.0,
        stereoWidth: Double = 1.0,
        marginDb: Double = 1.0,
        staticBassBands: List<DspEqBand> = emptyList(),
        studioToneBands: List<DspEqBand> = emptyList(),
        seatTrimDb: Double = 0.0,
        advancedMsBoostDb: Double = 0.0,
        harmonicBoostDb: Double = 0.0,
        macroWidthFactor: Double = 1.0,
        convolverBoostDb: Double = 0.0,
    ): DspHeadroomResult {
        require(sampleRate in 8_000..192_000)
        require(preampDb.isFinite())
        require(compressorMakeupDb.isFinite())
        require(maxBandMakeupDb.isFinite())
        require(dynamicBoostDb.isFinite())
        require(seatTrimDb.isFinite())
        require(advancedMsBoostDb.isFinite())
        require(harmonicBoostDb.isFinite())
        require(convolverBoostDb.isFinite() && convolverBoostDb >= 0.0)
        require(stereoWidth.isFinite() && stereoWidth in 0.0..2.0)
        require(macroWidthFactor.isFinite() && macroWidthFactor in 0.75..1.25)
        require(marginDb.isFinite() && marginDb >= 0.0)
        require(peqBands.size <= DspEqDesigner.MAX_BANDS)
        require(staticBassBands.size <= DspEqDesigner.MAX_BANDS)

        val bands = (peqBands + staticBassBands + studioToneBands).filter(DspEqBand::enabled)
        val filters = bands.map { DspEqDesigner.design(sampleRate, it) }
        val maxFrequency = minOf(20_000.0, sampleRate * 0.45)
        var peakDb = Double.NEGATIVE_INFINITY
        val logMin = kotlin.math.ln(20.0)
        val logMax = kotlin.math.ln(maxFrequency)
        for (point in 0 until SCAN_POINTS) {
            val fraction = point.toDouble() / (SCAN_POINTS - 1)
            val frequency = kotlin.math.exp(logMin + (logMax - logMin) * fraction)
            var magnitude = 1.0
            for (filter in filters) magnitude *= filter.magnitudeAt(sampleRate, frequency)
            require(magnitude.isFinite() && magnitude >= 0.0)
            if (magnitude > 0.0) peakDb = max(peakDb, 20.0 * log10(magnitude))
        }
        if (filters.isEmpty()) peakDb = 0.0
        val widthWorstCaseDb = 20.0 * log10(max(1.0, stereoWidth) * max(1.0, macroWidthFactor))
        val staticWorstCaseDb = preampDb + peakDb + max(0.0, compressorMakeupDb) +
            max(0.0, maxBandMakeupDb) + max(0.0, dynamicBoostDb) +
            max(0.0, seatTrimDb) + widthWorstCaseDb +
            max(0.0, advancedMsBoostDb) + max(0.0, harmonicBoostDb) + convolverBoostDb
        return DspHeadroomResult(
            filterPeakDb = peakDb,
            reductionDb = max(0.0, staticWorstCaseDb + marginDb),
        )
    }
}

internal data class DspPreparedConfig(
    val appliedPreampDb: Double,
    val eqCoefficients: DoubleArray,
    val dynamics: DoubleArray,
    val bassCoefficients: DoubleArray,
    val monoBassCoefficients: DoubleArray,
    val spatial: DoubleArray,
    val convolverConfig: DoubleArray,
    val convolverSamples: FloatArray,
    val multiband: DoubleArray,
    val dynamicEq: DoubleArray,
    val harmonic: DoubleArray,
    val macroTone: DoubleArray,
    val smartLoudness: DoubleArray,
    val dynamicBass: DoubleArray,
    val headroom: DspHeadroomResult,
)

internal data class DspPreparedHotUpdate(
    val generation: Long,
    val editorOwnerEpoch: Long,
    val rendererOwnerToken: Long,
    val pcmFormatEpoch: Long,
    val hotContextToken: Int,
    val mediaRole: Boolean,
    val appliedPreampDb: Double,
    val macroValues: DoubleArray,
    val smartLoudnessValues: DoubleArray,
    val dynamicBassValues: DoubleArray,
    val sampleRateHz: Int = 48_000,
    val hotPayloadVersion: Int = 1,
    val hotFieldMask: Int = HOT_FIELD_STUDIO,
    val dynamicsValues: DoubleArray = DoubleArray(DYNAMICS_VALUES_COUNT),
    val dynamicEqValues: DoubleArray = DoubleArray(DYNAMIC_EQ_VALUES_COUNT),
    val dynamicsCoefficients: DoubleArray = DoubleArray(DYNAMICS_COEFFICIENTS_COUNT),
    val dynamicEqCoefficients: DoubleArray = DoubleArray(DYNAMIC_EQ_COEFFICIENTS_COUNT),
    val appliedPreampLinear: Double = 10.0.pow(appliedPreampDb / 20.0),
) {
    val gainPairIsConsistent: Boolean = run {
        val expectedLinear = 10.0.pow(appliedPreampDb / 20.0)
        abs(appliedPreampLinear - expectedLinear) <= max(1.0e-12, expectedLinear * 1.0e-6)
    }

    val generationToken: Int
        get() = ((generation and GENERATION_TOKEN_MASK).toInt()).let { if (it == 0) 1 else it }

    companion object {
        const val HOT_FIELD_STUDIO = 1
        const val HOT_FIELD_DYNAMICS = 2
        const val HOT_FIELD_DYNAMIC_EQ = 4
        const val DYNAMICS_VALUES_COUNT = 10
        const val DYNAMIC_EQ_VALUES_COUNT = 51
        const val DYNAMICS_COEFFICIENTS_COUNT = 5
        const val DYNAMIC_EQ_COEFFICIENTS_COUNT = 10
        private const val GENERATION_TOKEN_MASK = 0x7fffffffL
    }
}

internal data class DspPreparedPreampHotUpdate(
    val version: Int = 1,
    val generation: Long,
    val editorOwnerEpoch: Long,
    val rendererOwnerToken: Long,
    val pcmFormatEpoch: Long,
    val hotContextToken: Int,
    val mediaRole: Boolean,
    val appliedPreampDb: Double,
    val appliedPreampLinear: Double,
) {
    val gainPairIsConsistent: Boolean = run {
        val expectedLinear = 10.0.pow(appliedPreampDb / 20.0)
        abs(appliedPreampLinear - expectedLinear) <= max(1.0e-12, expectedLinear * 1.0e-6)
    }

    val generationToken: Int
        get() = ((generation and GENERATION_TOKEN_MASK).toInt()).let { if (it == 0) 1 else it }

    init {
        require(version == 1 && generation > 0 && editorOwnerEpoch > 0 && rendererOwnerToken > 0 &&
            pcmFormatEpoch > 0 && hotContextToken > 0 && mediaRole && appliedPreampDb.isFinite() &&
            appliedPreampDb in -120.0..-6.0 && appliedPreampLinear.isFinite() &&
            appliedPreampLinear in 1.0e-6..0.50118724)
    }

    companion object {
        private const val GENERATION_TOKEN_MASK = 0x7fffffffL
    }
}

internal fun DspRuntimeConfig.preparePreampHotUpdate(
    format: DspAudioFormat,
    generation: Long,
    editorOwnerEpoch: Long,
    rendererOwnerToken: Long,
    pcmFormatEpoch: Long,
    hotContextToken: Int,
    mediaRole: Boolean,
): DspPreparedPreampHotUpdate? {
    val appliedPreampDb = prepare(format).appliedPreampDb
    if (!mediaRole || appliedPreampDb > -6.0) return null
    return DspPreparedPreampHotUpdate(
        generation = generation,
        editorOwnerEpoch = editorOwnerEpoch,
        rendererOwnerToken = rendererOwnerToken,
        pcmFormatEpoch = pcmFormatEpoch,
        hotContextToken = hotContextToken,
        mediaRole = mediaRole,
        appliedPreampDb = appliedPreampDb,
        appliedPreampLinear = 10.0.pow(appliedPreampDb / 20.0),
    )
}

internal data class DspPreparedLocalPeqWarmUpdate(
    val generation: Long,
    val editorOwnerEpoch: Long,
    val rendererOwnerToken: Long,
    val pcmFormatEpoch: Long,
    val hotContextToken: Int,
    val mediaRole: Boolean,
    val bandCount: Int,
    val historyEpochSequence: Long,
    val historyEndSequence: Long,
    val preparedValues: DoubleArray,
) {
    val generationToken: Int
        get() = ((generation and GENERATION_TOKEN_MASK).toInt()).let { if (it == 0) 1 else it }

    companion object {
        const val VALUES_PER_BAND = DspEqDesigner.COEFFICIENTS_PER_BAND + 4
        private const val GENERATION_TOKEN_MASK = 0x7fffffffL
    }
}

internal fun DspRuntimeConfig.prepareHotUpdate(
    format: DspAudioFormat,
    generation: Long,
    editorOwnerEpoch: Long,
    rendererOwnerToken: Long,
    pcmFormatEpoch: Long,
    hotContextToken: Int,
    mediaRole: Boolean,
    hotFieldMask: Int = DspPreparedHotUpdate.HOT_FIELD_STUDIO,
): DspPreparedHotUpdate {
    require(generation > 0 && editorOwnerEpoch > 0 && rendererOwnerToken > 0 &&
        pcmFormatEpoch > 0 && hotContextToken > 0)
    val prepared = prepare(format)
    return DspPreparedHotUpdate(
        generation = generation,
        editorOwnerEpoch = editorOwnerEpoch,
        rendererOwnerToken = rendererOwnerToken,
        pcmFormatEpoch = pcmFormatEpoch,
        hotContextToken = hotContextToken,
        mediaRole = mediaRole,
        appliedPreampDb = prepared.appliedPreampDb,
        macroValues = prepared.macroTone,
        smartLoudnessValues = prepared.smartLoudness,
        dynamicBassValues = prepared.dynamicBass,
        hotFieldMask = hotFieldMask,
        sampleRateHz = format.sampleRate,
        hotPayloadVersion = 1,
        dynamicsValues = prepared.dynamics,
        dynamicEqValues = prepared.dynamicEq,
        dynamicsCoefficients = prepared.dynamicsHotCoefficients(format.sampleRate),
        dynamicEqCoefficients = prepared.dynamicEqHotCoefficients(format.sampleRate),
        appliedPreampLinear = 10.0.pow(prepared.appliedPreampDb / 20.0),
    )
}

private fun DspPreparedConfig.dynamicsHotCoefficients(sampleRate: Int): DoubleArray = doubleArrayOf(
    exp(-1.0 / (sampleRate * dynamics[3] * 0.001)),
    exp(-1.0 / (sampleRate * dynamics[4] * 0.001)),
    10.0.pow(dynamics[6] / 20.0),
    exp(-1.0 / (sampleRate * dynamics[9] * 0.001)),
    10.0.pow(dynamics[8] / 20.0),
)

private fun DspPreparedConfig.dynamicEqHotCoefficients(sampleRate: Int): DoubleArray =
    DoubleArray(DspPreparedHotUpdate.DYNAMIC_EQ_COEFFICIENTS_COUNT) { index ->
        val valueOffset = 1 + (index / 2) * 10 + if (index % 2 == 0) 6 else 7
        exp(-1.0 / (sampleRate * dynamicEq[valueOffset] * 0.001))
    }

internal fun DspRuntimeConfig.prepare(format: DspAudioFormat): DspPreparedConfig {
    advancedMs.validateForSampleRate(format.sampleRate)
    harmonic.validateForSampleRate(format.sampleRate)
    val cabinBands = cabinCorrection.approvedEqBands()
    val enabledBands = (peqBands + cabinBands).filter(DspEqBand::enabled)
    require(enabledBands.size <= DspEqDesigner.MAX_BANDS) {
        "PEQ_CAPACITY_EXCEEDED: ${enabledBands.size} active bands exceeds ${DspEqDesigner.MAX_BANDS}"
    }
    val bassBand = if (bass.enabled) {
        DspEqBand(DspEqType.LOW_SHELF, bass.frequencyHz, gainDb = bass.gainDb)
    } else {
        null
    }
    val graphBands = enabledBands + listOfNotNull(bassBand)
    val coefficients = DoubleArray(graphBands.size * DspEqDesigner.COEFFICIENTS_PER_BAND)
    graphBands.forEachIndexed { index, band ->
        DspEqDesigner.design(format.sampleRate, band)
            .writeTo(coefficients, index * DspEqDesigner.COEFFICIENTS_PER_BAND)
    }
    val monoBassCoefficients = if (monoBass.enabled) {
        DoubleArray(DspEqDesigner.COEFFICIENTS_PER_BAND).also { destination ->
            DspEqDesigner.design(
                format.sampleRate,
                DspEqBand(
                    DspEqType.HIGH_PASS,
                    monoBass.cutoffHz.toDouble(),
                    q = BUTTERWORTH_Q,
                ),
            ).writeTo(destination, 0)
        }
    } else {
        DoubleArray(0)
    }
    val impulseResponse = convolver.impulseResponse
    val activeImpulseResponse = impulseResponse?.takeIf { convolver.enabled && convolver.wet > 0.0 }
    val sampleRateMatchedImpulseResponse = activeImpulseResponse?.takeIf { it.sampleRate == format.sampleRate }
    val convolverEnabled = activeImpulseResponse != null
    val convolverSamples = activeImpulseResponse?.copySamples() ?: FloatArray(0)
    if (multiband.enabled) {
        require(multiband.lowMidCrossoverHz < format.sampleRate * 0.45)
        require(multiband.midHighCrossoverHz < format.sampleRate * 0.45)
    }
    val multibandNativeValues = multiband.toNativeValues()
    val rateSafeDynamicEq = DspDynamicEqConfig(
        enabled = dynamicEq.enabled,
        bands = dynamicEq.bands.map { band ->
            if (band.enabled && band.frequencyHz >= format.sampleRate * 0.45) band.copy(enabled = false) else band
        },
    )
    val dynamicEqNativeValues = rateSafeDynamicEq.toNativeValues()
    val studioToneBoostDb = macroTone.conservativeBoostDb() + smartLoudness.conservativeBoostDb()
    val headroom = if (autoHeadroomEnabled) {
        DspAutoHeadroom.calculate(
            sampleRate = format.sampleRate,
            preampDb = gainDb,
            peqBands = enabledBands,
            compressorMakeupDb = if (compressor.enabled) compressor.makeupDb else 0.0,
            maxBandMakeupDb = if (multiband.enabled) {
                maxOf(
                    if (multiband.low.enabled) multiband.low.makeupDb else 0.0,
                    if (multiband.mid.enabled) multiband.mid.makeupDb else 0.0,
                    if (multiband.high.enabled) multiband.high.makeupDb else 0.0,
                )
            } else {
                0.0
            },
            dynamicBoostDb = studioToneBoostDb + (if (rateSafeDynamicEq.enabled) {
                rateSafeDynamicEq.bands.filter { it.enabled && it.mode == DspDynamicEqMode.BOOST }
                    .sumOf(DspDynamicEqBandConfig::maxBoostDb)
            } else {
                0.0
            }) + if (dynamicBass.enabled) dynamicBass.maxBoostDb * dynamicBass.strength else 0.0,
            seatTrimDb = if (format.channels == 2) seatAlignment.positiveTrimForHeadroomDb else 0.0,
            stereoWidth = if (advancedMs.enabled) 1.0 else stereoWidth,
            macroWidthFactor = if (macroTone.enabled) macroTone.relativeWidth else 1.0,
            advancedMsBoostDb = if (format.channels == 2) advancedMs.worstCaseBoostDb else 0.0,
            harmonicBoostDb = harmonic.worstCaseBoostDb,
            marginDb = autoHeadroomMarginDb,
            staticBassBands = if (bass.enabled) {
                listOf(
                    DspEqBand(
                        type = DspEqType.LOW_SHELF,
                        frequencyHz = bass.frequencyHz,
                        gainDb = bass.gainDb,
                    ),
                )
            } else {
                emptyList()
            },
            convolverBoostDb = sampleRateMatchedImpulseResponse?.conservativePeakGainDb(convolver.wet) ?: 0.0,
        )
    } else {
        DspHeadroomResult(filterPeakDb = 0.0, reductionDb = 0.0)
    }
    return DspPreparedConfig(
        appliedPreampDb = (gainDb - headroom.reductionDb).coerceAtLeast(MIN_APPLIED_PREAMP_DB),
        eqCoefficients = coefficients,
        dynamics = doubleArrayOf(
            if (compressor.enabled) 1.0 else 0.0,
            compressor.thresholdDb,
            compressor.ratio,
            compressor.attackMs,
            compressor.releaseMs,
            compressor.kneeDb,
            compressor.makeupDb,
            if (safetyLimiter.enabled) 1.0 else 0.0,
            safetyLimiter.thresholdDb,
            safetyLimiter.releaseMs,
        ),
        bassCoefficients = DoubleArray(0),
        monoBassCoefficients = monoBassCoefficients,
        spatial = doubleArrayOf(
            stereoWidth,
            if (monoBass.enabled) 1.0 else 0.0,
            monoBass.cutoffHz.toDouble(),
            if (seatAlignment.enabled && format.channels == 2) 1.0 else 0.0,
            seatAlignment.nearSpeaker.nativeValue.toDouble(),
            seatAlignment.distanceDifferenceCm,
            seatAlignment.leftTrimDb,
            seatAlignment.rightTrimDb,
            if (seatAlignment.invertLeft) 1.0 else 0.0,
            if (seatAlignment.invertRight) 1.0 else 0.0,
            if (advancedMs.enabled && format.channels == 2) 1.0 else 0.0,
            advancedMs.crossoverHz,
            advancedMs.centerFocusDb,
            advancedMs.midLowGainDb,
            advancedMs.midHighGainDb,
            advancedMs.sideLowGainDb,
            advancedMs.sideHighGainDb,
            advancedMs.lowBandWidth,
            advancedMs.highBandWidth,
            if (advancedMs.sideCompressor.enabled && format.channels == 2) 1.0 else 0.0,
            advancedMs.sideCompressor.thresholdDb,
            advancedMs.sideCompressor.ratio,
            advancedMs.sideCompressor.attackMs,
            advancedMs.sideCompressor.releaseMs,
            advancedMs.sideCompressor.kneeDb,
        ),
        convolverConfig = doubleArrayOf(
            if (convolverEnabled) 1.0 else 0.0,
            activeImpulseResponse?.sampleRate?.toDouble() ?: 0.0,
            activeImpulseResponse?.channels?.toDouble() ?: 0.0,
            activeImpulseResponse?.frameCount?.toDouble() ?: 0.0,
            convolver.wet,
        ),
        convolverSamples = convolverSamples,
        multiband = multibandNativeValues,
        dynamicEq = dynamicEqNativeValues,
        harmonic = doubleArrayOf(
            if (harmonic.isActive) 1.0 else 0.0,
            harmonic.mode.nativeValue.toDouble(),
            harmonic.quality.taps.toDouble(),
            if (harmonic.isActive && harmonic.exciterEnabled) 1.0 else 0.0,
            harmonic.exciterAmount,
            harmonic.exciterCutoffHz,
            if (harmonic.isActive && harmonic.virtualBassEnabled) 1.0 else 0.0,
            harmonic.virtualBassAmount,
            harmonic.virtualBassCutoffHz,
            if (harmonic.levelMatchEnabled) 1.0 else 0.0,
        ),
        macroTone = doubleArrayOf(
            if (macroTone.enabled) 1.0 else 0.0,
            macroTone.bassGainDb,
            macroTone.vocalGainDb,
            macroTone.airGainDb,
            macroTone.relativeWidth,
        ),
        smartLoudness = doubleArrayOf(
            if (smartLoudness.enabled) 1.0 else 0.0,
            smartLoudness.lowGainDb,
            smartLoudness.highGainDb,
        ),
        dynamicBass = doubleArrayOf(
            if (dynamicBass.enabled) 1.0 else 0.0,
            dynamicBass.strength,
            dynamicBass.maxBoostDb,
            dynamicBass.detectorCutoffHz,
            dynamicBass.silenceGateDbfs,
            dynamicBass.lowThresholdDbfs,
            dynamicBass.highThresholdDbfs,
            dynamicBass.attackMs,
            dynamicBass.releaseMs,
        ),
        headroom = headroom,
    )
}

private const val MIN_APPLIED_PREAMP_DB = -700.0
private const val BUTTERWORTH_Q = 0.7071067811865476
