package com.shilapi.xcertplay.media.dsp

internal data class DspRuntimeConfig(
    val enabled: Boolean,
) {
    companion object {
        fun disabled(): DspRuntimeConfig = DspRuntimeConfig(enabled = false)
    }
}
