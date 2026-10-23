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

data class DspRendererApplyStatus(
    val generation: Long,
    val phase: DspRendererApplyPhase,
    val activeGeneration: Long? = null,
)

fun interface DspConfigProvider {
    fun snapshot(): DspRuntimeConfig

    fun versionedSnapshot(): DspConfigSnapshot = DspConfigSnapshot(0L, snapshot())

    fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable = Closeable { }

    fun rendererApplyStatus(): DspRendererApplyStatus? = null

    fun reportRendererApplyStatus(status: DspRendererApplyStatus) = Unit
}
