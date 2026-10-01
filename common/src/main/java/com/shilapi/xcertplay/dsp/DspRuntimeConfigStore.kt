package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.util.concurrent.atomic.AtomicReference

internal class DspRuntimeConfigStore(initialConfig: DspRuntimeConfig = DspRuntimeConfig.disabled()) : DspConfigProvider {
    private val current = AtomicReference(initialConfig)

    override fun snapshot(): DspRuntimeConfig = current.get()

    fun update(config: DspRuntimeConfig) {
        current.set(config)
    }
}
