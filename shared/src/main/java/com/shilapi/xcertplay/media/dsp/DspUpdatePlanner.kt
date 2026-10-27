package com.shilapi.xcertplay.media.dsp

internal enum class DspUpdatePlan {
    HOT,
    LOCAL_WARM,
    WARM,
    DEFERRED,
}

internal data class DspUpdateDecision(
    val plan: DspUpdatePlan,
    val reason: String? = null,
)

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
        mediaRole: Boolean = true,
        studioHotUpdatesEnabled: Boolean = true,
        studioLocalWarmEnabled: Boolean = false,
    ): DspUpdatePlan = decide(
        current = current,
        target = target,
        hasActivePipeline = hasActivePipeline,
        trackGenerationCommitted = trackGenerationCommitted,
        currentLatencyFrames = currentLatencyFrames,
        targetLatencyFrames = targetLatencyFrames,
        requiresStateContinuity = requiresStateContinuity,
        mediaRole = mediaRole,
        studioHotUpdatesEnabled = studioHotUpdatesEnabled,
        studioLocalWarmEnabled = studioLocalWarmEnabled,
    ).plan

    fun decide(
        current: DspRuntimeConfig,
        target: DspRuntimeConfig,
        hasActivePipeline: Boolean,
        trackGenerationCommitted: Boolean,
        currentLatencyFrames: Int,
        targetLatencyFrames: Int,
        requiresStateContinuity: Boolean,
        mediaRole: Boolean = true,
        studioHotUpdatesEnabled: Boolean = true,
        studioLocalWarmEnabled: Boolean = false,
    ): DspUpdateDecision {
        val changed = diff(current, target)
        if (changed.isEmpty()) return DspUpdateDecision(DspUpdatePlan.HOT)

        if (!mediaRole) {
            return DspUpdateDecision(DspUpdatePlan.DEFERRED, "DSP updates are restricted to MEDIA streams")
        }

        if (trackGenerationCommitted && currentLatencyFrames != targetLatencyFrames) {
            return DspUpdateDecision(DspUpdatePlan.DEFERRED, "algorithmic latency would change")
        }
        if (trackGenerationCommitted && DspRuntimeField.CONVOLVER in changed &&
            target.convolver.enabled && target.convolver.wet > 0.0
        ) {
            return DspUpdateDecision(DspUpdatePlan.DEFERRED, "impulse response topology requires a new track")
        }
        if (trackGenerationCommitted && changed.any { it == DspRuntimeField.BASS || it == DspRuntimeField.CABIN_CORRECTION }) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "Bass Shelf and Cabin Correction local warm-up are not implemented; keeping the active pipeline until the next AudioTrack generation",
            )
        }
        if (trackGenerationCommitted && changed == setOf(DspRuntimeField.PREAMP)) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "preamp sample ramp is unavailable; keeping the active pipeline until the next AudioTrack generation",
            )
        }

        if (changed.all(::isHotField) && !studioHotUpdatesEnabled) {
            return if (trackGenerationCommitted) {
                DspUpdateDecision(DspUpdatePlan.DEFERRED, "Native HOT updates are disabled")
            } else {
                DspUpdateDecision(DspUpdatePlan.WARM, "Native HOT updates are disabled before track commit")
            }
        }

        if (mediaRole && hasActivePipeline && current.enabled && target.enabled &&
            changed.all(::isHotField) &&
            (!requiresStateContinuity || changed.all(::isContinuityIndependentHotField))
        ) {
            return DspUpdateDecision(DspUpdatePlan.HOT)
        }
        if (trackGenerationCommitted && changed.all(::isLocalWarmField) && studioLocalWarmEnabled) {
            return DspUpdateDecision(DspUpdatePlan.LOCAL_WARM)
        }
        if (trackGenerationCommitted && changed.all(::isLocalWarmField)) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "PEQ local warm-up is disabled; keeping the active pipeline until the next AudioTrack generation",
            )
        }
        if (trackGenerationCommitted && requiresStateContinuity) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                if (changed.any(::requiresUnprovenStateTransition)) {
                    "changed stateful stage cannot preserve its required history"
                } else {
                    "active stateful graph requires continuity for changed fields"
                },
            )
        }
        return DspUpdateDecision(DspUpdatePlan.WARM, "pipeline replacement required")
    }

    private fun isHotField(field: DspRuntimeField): Boolean = field in setOf(
        DspRuntimeField.MACRO_TONE,
        DspRuntimeField.SMART_LOUDNESS,
        DspRuntimeField.DYNAMIC_BASS,
    )

    private fun isContinuityIndependentHotField(field: DspRuntimeField): Boolean = field in setOf(
        DspRuntimeField.MACRO_TONE,
        DspRuntimeField.SMART_LOUDNESS,
        DspRuntimeField.DYNAMIC_BASS,
    )

    private fun isLocalWarmField(field: DspRuntimeField): Boolean = field == DspRuntimeField.PEQ

    private fun requiresUnprovenStateTransition(field: DspRuntimeField): Boolean = field in setOf(
        DspRuntimeField.CONVOLVER,
        DspRuntimeField.MULTIBAND,
        DspRuntimeField.DYNAMIC_EQ,
        DspRuntimeField.SEAT_ALIGNMENT,
        DspRuntimeField.ADVANCED_MS,
        DspRuntimeField.HARMONIC,
    )
}
