package com.shilapi.xcertplay.media.dsp

internal fun interface DspConfigProvider {
    fun snapshot(): DspRuntimeConfig
}
