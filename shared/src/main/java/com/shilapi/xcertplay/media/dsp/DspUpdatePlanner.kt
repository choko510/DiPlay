package com.shilapi.xcertplay.media.dsp

internal enum class DspUpdatePlan {
    HOT,
    WARM,
    DEFERRED,
}

internal enum class DspRuntimeField {
    MASTER_ENABLED,
    PREAMP,
    PEQ,
    AUTO_HEADROOM,
    BASS,
    COMPRESSOR,
    LIMITER,
    WIDTH,
    MONO_BASS,
    CONVOLVER,
    MULTIBAND,
    DYNAMIC_EQ,
    SEAT_ALIGNMENT,
    ADVANCED_MS,
    CABIN_CORRECTION,
    HARMONIC,
    MACRO_TONE,
    SMART_LOUDNESS,
    DYNAMIC_BASS,
}

internal object DspUpdatePlanner {
    fun diff(current: DspRuntimeConfig, target: DspRuntimeConfig): Set<DspRuntimeField> = buildSet {
        if (current.enabled != target.enabled) add(DspRuntimeField.MASTER_ENABLED)
        if (current.gainDb != target.gainDb) add(DspRuntimeField.PREAMP)
        if (current.peqBands != target.peqBands) add(DspRuntimeField.PEQ)
        if (current.autoHeadroomEnabled != target.autoHeadroomEnabled ||
            current.autoHeadroomMarginDb != target.autoHeadroomMarginDb
        ) add(DspRuntimeField.AUTO_HEADROOM)
        if (current.bass != target.bass) add(DspRuntimeField.BASS)
        if (current.compressor != target.compressor) add(DspRuntimeField.COMPRESSOR)
        if (current.safetyLimiter != target.safetyLimiter) add(DspRuntimeField.LIMITER)
        if (current.stereoWidth != target.stereoWidth) add(DspRuntimeField.WIDTH)
        if (current.monoBass != target.monoBass) add(DspRuntimeField.MONO_BASS)
        if (current.convolver != target.convolver) add(DspRuntimeField.CONVOLVER)
        if (current.multiband != target.multiband) add(DspRuntimeField.MULTIBAND)
        if (current.dynamicEq != target.dynamicEq) add(DspRuntimeField.DYNAMIC_EQ)
        if (current.seatAlignment != target.seatAlignment) add(DspRuntimeField.SEAT_ALIGNMENT)
        if (current.advancedMs != target.advancedMs) add(DspRuntimeField.ADVANCED_MS)
        if (current.cabinCorrection != target.cabinCorrection) add(DspRuntimeField.CABIN_CORRECTION)
        if (current.harmonic != target.harmonic) add(DspRuntimeField.HARMONIC)
        if (current.macroTone != target.macroTone) add(DspRuntimeField.MACRO_TONE)
        if (current.smartLoudness != target.smartLoudness) add(DspRuntimeField.SMART_LOUDNESS)
        if (current.dynamicBass != target.dynamicBass) add(DspRuntimeField.DYNAMIC_BASS)
    }

    fun classify(
        current: DspRuntimeConfig,
        target: DspRuntimeConfig,
        hasActivePipeline: Boolean,
        trackGenerationCommitted: Boolean,
        currentLatencyFrames: Int,
        targetLatencyFrames: Int,
        requiresStateContinuity: Boolean,
    ): DspUpdatePlan {
        if (hasActivePipeline && current.canApplyStudioHotUpdateTo(target)) {
            return DspUpdatePlan.HOT
        }
        if (trackGenerationCommitted &&
            (currentLatencyFrames != targetLatencyFrames || requiresStateContinuity)
        ) {
            return DspUpdatePlan.DEFERRED
        }
        return DspUpdatePlan.WARM
    }
}
