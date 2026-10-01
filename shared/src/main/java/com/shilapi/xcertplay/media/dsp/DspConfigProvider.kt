package com.shilapi.xcertplay.media.dsp

fun interface DspConfigProvider {
    fun snapshot(): DspRuntimeConfig
}
