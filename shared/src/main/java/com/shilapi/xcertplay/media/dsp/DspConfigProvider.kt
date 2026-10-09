package com.shilapi.xcertplay.media.dsp

import java.io.Closeable

data class DspConfigSnapshot(val generation: Long, val config: DspRuntimeConfig)

fun interface DspConfigProvider {
    fun snapshot(): DspRuntimeConfig

    fun versionedSnapshot(): DspConfigSnapshot = DspConfigSnapshot(0L, snapshot())

    fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable = Closeable { }
}
