package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspConfigSnapshot
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class DspRuntimeConfigStore(initialConfig: DspRuntimeConfig = DspRuntimeConfig.disabled()) : DspConfigProvider {
    private val current = AtomicReference(DspConfigSnapshot(0L, initialConfig))
    private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()

    override fun snapshot(): DspRuntimeConfig = current.get().config

    override fun versionedSnapshot(): DspConfigSnapshot = current.get()

    override fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable {
        listeners.add(listener)
        runCatching { listener(current.get()) }
        return Closeable { listeners.remove(listener) }
    }

    fun update(config: DspRuntimeConfig) {
        val next = current.updateAndGet { previous -> DspConfigSnapshot(previous.generation + 1, config) }
        listeners.forEach { listener -> runCatching { listener(next) } }
    }
}
