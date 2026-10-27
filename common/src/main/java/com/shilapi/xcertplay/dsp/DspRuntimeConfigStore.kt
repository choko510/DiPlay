package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspConfigSnapshot
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class DspRuntimeConfigStore(initialConfig: DspRuntimeConfig = DspRuntimeConfig.disabled()) : DspConfigProvider {
    private val current = AtomicReference(DspConfigSnapshot(0L, initialConfig))
    private val studioHotUpdatesAllowed = AtomicBoolean(true)
    private val studioLocalWarmAllowed = AtomicBoolean(false)
    private val applyStatus = AtomicReference<DspRendererApplyStatus?>(null)
    private val applyStatusOwner = AtomicReference<Long?>(null)
    private val applyStatusLock = Any()
    private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()
    private val playbackSampleRates = ConcurrentHashMap<Any, PlaybackSampleRate>()
    private val nextPlaybackSampleRateRevision = AtomicLong()

    override fun snapshot(): DspRuntimeConfig = current.get().config

    override fun studioHotUpdatesEnabled(): Boolean = studioHotUpdatesAllowed.get()

    override fun studioLocalWarmEnabled(): Boolean = studioLocalWarmAllowed.get()

    fun setStudioHotUpdatesEnabled(enabled: Boolean) {
        studioHotUpdatesAllowed.set(enabled)
    }

    fun setStudioLocalWarmEnabled(enabled: Boolean) {
        studioLocalWarmAllowed.set(enabled)
    }

    override fun versionedSnapshot(): DspConfigSnapshot = current.get()

    override fun activePlaybackSampleRateHz(): Int? = playbackSampleRates.values
        .maxByOrNull(PlaybackSampleRate::revision)
        ?.sampleRateHz

    override fun reportPlaybackSampleRate(owner: Any, sampleRateHz: Int?) {
        if (sampleRateHz == null) {
            playbackSampleRates.remove(owner)
        } else if (sampleRateHz in MIN_PLAYBACK_SAMPLE_RATE_HZ..MAX_PLAYBACK_SAMPLE_RATE_HZ) {
            playbackSampleRates[owner] = PlaybackSampleRate(nextPlaybackSampleRateRevision.incrementAndGet(), sampleRateHz)
        }
    }

    override fun rendererApplyStatus(): DspRendererApplyStatus? = applyStatus.get()

    override fun claimRendererApplyStatusOwner(ownerToken: Long) {
        require(ownerToken > 0L)
        synchronized(applyStatusLock) {
            if (applyStatusOwner.get() == ownerToken) return
            applyStatusOwner.set(ownerToken)
            val snapshot = current.get()
            while (true) {
                val previous = applyStatus.get()
                if ((previous?.generation ?: Long.MIN_VALUE) > snapshot.generation) return
                val activeGeneration = previous?.activeGeneration ?: previous?.generation?.takeIf {
                    previous.phase == DspRendererApplyPhase.ACTIVE
                }
                val requested = DspRendererApplyStatus(
                    generation = snapshot.generation,
                    phase = DspRendererApplyPhase.REQUESTED,
                    activeGeneration = activeGeneration,
                    rendererOwnerToken = ownerToken,
                )
                if (applyStatus.compareAndSet(previous, requested)) return
            }
        }
    }

    override fun reportRendererApplyStatus(status: DspRendererApplyStatus) {
        synchronized(applyStatusLock) {
            if (applyStatusOwner.get() != status.rendererOwnerToken) return
            while (current.get().generation == status.generation) {
                val previous = applyStatus.get()
                if ((previous?.generation ?: Long.MIN_VALUE) > status.generation) return
                if (previous?.generation == status.generation && !canAdvance(previous.phase, status.phase)) return
                if (applyStatus.compareAndSet(previous, status)) {
                    if (current.get().generation == status.generation) return
                }
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
        if (current.get().config.hasSameEffectiveSettings(config)) return
        val next = current.updateAndGet { previous -> DspConfigSnapshot(previous.generation + 1, config) }
        while (true) {
            val previous = applyStatus.get()
            if ((previous?.generation ?: Long.MIN_VALUE) > next.generation) break
            if (previous?.generation == next.generation && previous.phase != DspRendererApplyPhase.REQUESTED) break
            val lastActiveGeneration = previous?.activeGeneration ?: previous?.generation?.takeIf {
                previous.phase == DspRendererApplyPhase.ACTIVE
            }
            val requested = DspRendererApplyStatus(
                generation = next.generation,
                phase = DspRendererApplyPhase.REQUESTED,
                activeGeneration = lastActiveGeneration,
                rendererOwnerToken = applyStatusOwner.get(),
            )
            if (applyStatus.compareAndSet(previous, requested)) break
        }
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    private data class PlaybackSampleRate(val revision: Long, val sampleRateHz: Int)

    private companion object {
        const val MIN_PLAYBACK_SAMPLE_RATE_HZ = 8_000
        const val MAX_PLAYBACK_SAMPLE_RATE_HZ = 192_000
    }
}
