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
        if (trackGenerationCommitted && current.enabled && target.enabled && DspRuntimeField.WIDTH in changed &&
            (current.advancedMs.enabled || target.advancedMs.enabled)
        ) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "Legacy Width is inactive while Advanced M/S is enabled; keeping the active pipeline",
            )
        }
        if (trackGenerationCommitted && current.enabled && target.enabled &&
            DspRuntimeField.SEAT_ALIGNMENT in changed
        ) {
            val currentSeat = current.seatAlignment
            val targetSeat = target.seatAlignment
            val onlyTrimsChanged = currentSeat.copy(
                leftTrimDb = targetSeat.leftTrimDb,
                rightTrimDb = targetSeat.rightTrimDb,
            ) == targetSeat
            if (!onlyTrimsChanged) {
                return DspUpdateDecision(
                    DspUpdatePlan.DEFERRED,
                    "Seat delay, polarity, near-speaker, or enable changes require a new track",
                )
            }
            if (targetSeat.enabled && targetSeat.leftTrimDb != targetSeat.rightTrimDb) {
                return DspUpdateDecision(
                    DspUpdatePlan.DEFERRED,
                    "Asymmetric Seat trim may shift the center image; keeping the active pipeline",
                )
            }
        }
        if (trackGenerationCommitted && DspRuntimeField.CONVOLVER in changed &&
            target.convolver.enabled && target.convolver.wet > 0.0
        ) {
            return DspUpdateDecision(DspUpdatePlan.DEFERRED, "impulse response topology requires a new track")
        }
        if (trackGenerationCommitted && DspRuntimeField.CABIN_CORRECTION in changed) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "Cabin Correction local warm-up is not implemented; keeping the active pipeline until the next AudioTrack generation",
            )
        }
        if (trackGenerationCommitted && changed == setOf(DspRuntimeField.PREAMP) &&
            (!target.autoHeadroomEnabled || target.gainDb > -6.0)
        ) {
            return DspUpdateDecision(
                DspUpdatePlan.DEFERRED,
                "preamp HOT requires Auto Headroom and at least 6 dB of reserve",
            )
        }
        if (trackGenerationCommitted && hasActivePipeline && current.enabled && target.enabled) {
            if (DspRuntimeField.COMPRESSOR in changed &&
                current.compressor.enabled != target.compressor.enabled) {
                return DspUpdateDecision(DspUpdatePlan.DEFERRED,
                    "COMPRESSOR enabled topology change is unsafe for HOT; keeping the active pipeline")
            }
            if (DspRuntimeField.LIMITER in changed && (
                    current.safetyLimiter.enabled != target.safetyLimiter.enabled ||
                        target.safetyLimiter.thresholdDb > current.safetyLimiter.thresholdDb
                    )) {
                return DspUpdateDecision(DspUpdatePlan.DEFERRED,
                    "LIMITER enable transition or threshold increase is unsafe for HOT; keeping the active pipeline")
            }
            if (DspRuntimeField.DYNAMIC_EQ in changed && !canHotDynamicEq(current, target)) {
                return DspUpdateDecision(DspUpdatePlan.DEFERRED,
                    "DYNAMIC_EQ enabled/mode/frequency/Q topology change is unsafe for HOT; keeping the active pipeline")
            }
        }
        if (trackGenerationCommitted && hasActivePipeline && current.enabled && target.enabled &&
            changed == setOf(DspRuntimeField.PREAMP)
        ) {
            return if (studioHotUpdatesEnabled) DspUpdateDecision(DspUpdatePlan.HOT)
            else DspUpdateDecision(DspUpdatePlan.DEFERRED, "Native HOT updates are disabled")
        }

        if (changed.all(::isHotField) && !studioHotUpdatesEnabled) {
            return if (trackGenerationCommitted) {
                DspUpdateDecision(DspUpdatePlan.DEFERRED, "Native HOT updates are disabled")
            } else {
                DspUpdateDecision(DspUpdatePlan.WARM, "Native HOT updates are disabled before track commit")
            }
        }

        if (trackGenerationCommitted && hasActivePipeline && current.enabled && target.enabled &&
            changed.all { isHotField(it) || it == DspRuntimeField.COMPRESSOR ||
                it == DspRuntimeField.LIMITER || it == DspRuntimeField.DYNAMIC_EQ } &&
            (!requiresStateContinuity || changed.all(::isContinuityIndependentHotField)) &&
            canHotDynamics(current, target) && canHotDynamicEq(current, target)
        ) {
            return if (studioHotUpdatesEnabled) DspUpdateDecision(DspUpdatePlan.HOT)
            else DspUpdateDecision(DspUpdatePlan.DEFERRED, "Native HOT updates are disabled")
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
                "PEQ and Bass Shelf local warm-up are disabled; keeping the active pipeline until the next AudioTrack generation",
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
        DspRuntimeField.PREAMP,
        DspRuntimeField.COMPRESSOR,
        DspRuntimeField.LIMITER,
        DspRuntimeField.DYNAMIC_EQ,
        DspRuntimeField.MACRO_TONE,
        DspRuntimeField.SMART_LOUDNESS,
        DspRuntimeField.DYNAMIC_BASS,
    )

    internal fun canHotDynamics(current: DspRuntimeConfig, target: DspRuntimeConfig): Boolean {
        if (current.compressor.enabled != target.compressor.enabled ||
            current.safetyLimiter.enabled != target.safetyLimiter.enabled) return false
        return target.safetyLimiter.thresholdDb <= current.safetyLimiter.thresholdDb
    }

    internal fun canHotDynamicEq(current: DspRuntimeConfig, target: DspRuntimeConfig): Boolean {
        val old = current.dynamicEq
        val next = target.dynamicEq
        if (old.enabled != next.enabled || old.bands.size != next.bands.size) return false
        return old.bands.indices.all { index ->
            val before = old.bands[index]
            val after = next.bands[index]
            before.enabled == after.enabled && before.mode == after.mode &&
                before.frequencyHz == after.frequencyHz && before.q == after.q
        }
    }

    private fun isLocalWarmField(field: DspRuntimeField): Boolean = field in setOf(
        DspRuntimeField.PEQ,
        DspRuntimeField.BASS,
    )

    private fun requiresUnprovenStateTransition(field: DspRuntimeField): Boolean = field in setOf(
        DspRuntimeField.CONVOLVER,
        DspRuntimeField.MULTIBAND,
        DspRuntimeField.DYNAMIC_EQ,
        DspRuntimeField.SEAT_ALIGNMENT,
        DspRuntimeField.ADVANCED_MS,
        DspRuntimeField.HARMONIC,
    )
}
