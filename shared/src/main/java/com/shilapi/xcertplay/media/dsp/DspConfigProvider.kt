package com.shilapi.xcertplay.media.dsp

import java.io.Closeable

data class DspConfigSnapshot(val generation: Long, val config: DspRuntimeConfig)

enum class DspRendererApplyPhase {
    REQUESTED,
    PREPARING,
    PREPARED,
    ACTIVE,
    DEFERRED,
    FAILED,
}

enum class DspRendererApplyWarning {
    CONVOLVER_IR_UNAVAILABLE,
    CONVOLVER_SAMPLE_RATE_MISMATCH,
}

data class DspRendererApplyStatus(
    val generation: Long,
    val phase: DspRendererApplyPhase,
    val activeGeneration: Long? = null,
    val warning: DspRendererApplyWarning? = null,
    val retiredGeneration: Long? = null,
    val retiredPipelineId: Int? = null,
    val updatesBlocked: Boolean = false,
    val reason: String? = null,
)

fun interface DspConfigProvider {
    fun snapshot(): DspRuntimeConfig

    fun versionedSnapshot(): DspConfigSnapshot = DspConfigSnapshot(0L, snapshot())

    fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable = Closeable { }

    fun activePlaybackSampleRateHz(): Int? = null

    fun reportPlaybackSampleRate(owner: Any, sampleRateHz: Int?) = Unit

    fun rendererApplyStatus(): DspRendererApplyStatus? = null

    fun reportRendererApplyStatus(status: DspRendererApplyStatus) = Unit

    fun studioHotUpdatesEnabled(): Boolean = true
}
