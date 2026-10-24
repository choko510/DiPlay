package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspConfigSnapshot
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class DspRuntimeConfigStore(initialConfig: DspRuntimeConfig = DspRuntimeConfig.disabled()) : DspConfigProvider {
    private val current = AtomicReference(DspConfigSnapshot(0L, initialConfig))
    private val applyStatus = AtomicReference<DspRendererApplyStatus?>(null)
    private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()

    override fun snapshot(): DspRuntimeConfig = current.get().config

    override fun versionedSnapshot(): DspConfigSnapshot = current.get()

    override fun rendererApplyStatus(): DspRendererApplyStatus? = applyStatus.get()

    override fun reportRendererApplyStatus(status: DspRendererApplyStatus) {
        while (current.get().generation == status.generation) {
            val previous = applyStatus.get()
            if ((previous?.generation ?: Long.MIN_VALUE) > status.generation) return
            if (previous?.generation == status.generation && !canAdvance(previous.phase, status.phase)) return
            if (applyStatus.compareAndSet(previous, status)) {
                if (current.get().generation == status.generation) return
            }
        }
    }

    private fun canAdvance(previous: DspRendererApplyPhase, next: DspRendererApplyPhase): Boolean = when (previous) {
        DspRendererApplyPhase.REQUESTED -> true
        DspRendererApplyPhase.PREPARING -> next == DspRendererApplyPhase.PREPARED ||
            next == DspRendererApplyPhase.DEFERRED || next == DspRendererApplyPhase.ACTIVE ||
            next == DspRendererApplyPhase.FAILED
        DspRendererApplyPhase.PREPARED -> next == DspRendererApplyPhase.DEFERRED ||
            next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
        DspRendererApplyPhase.DEFERRED -> next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
        DspRendererApplyPhase.ACTIVE -> next == DspRendererApplyPhase.ACTIVE || next == DspRendererApplyPhase.FAILED
        DspRendererApplyPhase.FAILED -> next == DspRendererApplyPhase.ACTIVE
    }

    override fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable {
        listeners.add(listener)
        runCatching { listener(current.get()) }
        return Closeable { listeners.remove(listener) }
    }

    fun update(config: DspRuntimeConfig) {
        val next = current.updateAndGet { previous -> DspConfigSnapshot(previous.generation + 1, config) }
        while (true) {
            val previous = applyStatus.get()
            if ((previous?.generation ?: Long.MIN_VALUE) > next.generation) break
            if (previous?.generation == next.generation && previous.phase != DspRendererApplyPhase.REQUESTED) break
            val lastActiveGeneration = previous?.activeGeneration ?: previous?.generation?.takeIf {
                previous.phase == DspRendererApplyPhase.ACTIVE
            }
            val requested = DspRendererApplyStatus(next.generation, DspRendererApplyPhase.REQUESTED, lastActiveGeneration)
            if (applyStatus.compareAndSet(previous, requested)) break
        }
        listeners.forEach { listener -> runCatching { listener(next) } }
    }
}
