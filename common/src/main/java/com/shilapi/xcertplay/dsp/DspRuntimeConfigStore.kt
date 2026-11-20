package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspConfigSnapshot
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspSpeedCompensationConfig
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
    private val rendererApplyState = AtomicReference(RendererApplyState(null, null))
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

    override fun rendererApplyStatus(): DspRendererApplyStatus? = rendererApplyState.get().status

    override fun claimRendererApplyStatusOwner(ownerToken: Long) {
        require(ownerToken > 0L)
        while (true) {
            val previousState = rendererApplyState.get()
            if (previousState.ownerToken?.let { ownerToken <= it } == true) return
            val snapshot = current.get()
            val previousStatus = previousState.status
            if ((previousStatus?.generation ?: Long.MIN_VALUE) > snapshot.generation) return
            val activeGeneration = previousStatus?.activeGeneration ?: previousStatus?.generation?.takeIf {
                previousStatus.phase == DspRendererApplyPhase.ACTIVE
            }
            val requested = DspRendererApplyStatus(
                generation = snapshot.generation,
                phase = DspRendererApplyPhase.REQUESTED,
                activeGeneration = activeGeneration,
                rendererOwnerToken = ownerToken,
            )
            if (rendererApplyState.compareAndSet(previousState, RendererApplyState(ownerToken, requested))) return
        }
    }

    override fun reportRendererApplyStatus(status: DspRendererApplyStatus) {
        while (current.get().generation == status.generation) {
            val previousState = rendererApplyState.get()
            if (previousState.ownerToken != status.rendererOwnerToken) return
            val previousStatus = previousState.status
            if ((previousStatus?.generation ?: Long.MIN_VALUE) > status.generation) return
            if (previousStatus?.generation == status.generation && !canAdvance(previousStatus.phase, status.phase)) return
            if (rendererApplyState.compareAndSet(previousState, previousState.copy(status = status))) {
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
        // Speed compensation is transient runtime state owned by this store; it is layered on
        // top of whatever the profile/workspace update produced so a profile save can never
        // clear or persist it.
        merge { snapshot -> config.withSpeedCompensationIfDifferent(snapshot.config.speedCompensation) }
    }

    fun updateSpeedCompensation(config: DspSpeedCompensationConfig) {
        merge { snapshot -> snapshot.config.withSpeedCompensationIfDifferent(config) }
    }

    fun speedCompensation(): DspSpeedCompensationConfig = current.get().config.speedCompensation

    private fun DspRuntimeConfig.withSpeedCompensationIfDifferent(
        speedCompensation: DspSpeedCompensationConfig,
    ): DspRuntimeConfig = if (this.speedCompensation == speedCompensation) {
        this
    } else {
        withSpeedCompensation(speedCompensation)
    }

    /**
     * Publishes a merged config from the latest committed snapshot.
     *
     * Both the transient speed layer and the profile layer are merged into one immutable config
     * that is swapped with a CAS loop, so a profile update and a speed update can never publish
     * a stale composition of the two (for example an old profile paired with an old speed layer).
     */
    private fun merge(transform: (DspConfigSnapshot) -> DspRuntimeConfig) {
        var published: DspConfigSnapshot? = null
        while (published == null) {
            val snapshot = current.get()
            val target = transform(snapshot)
            if (target == snapshot.config || snapshot.config.hasSameEffectiveSettings(target)) return
            val next = DspConfigSnapshot(snapshot.generation + 1, target)
            if (current.compareAndSet(snapshot, next)) published = next
        }
        publishSnapshot(published)
    }

    private fun publishSnapshot(next: DspConfigSnapshot) {
        while (true) {
            val previousState = rendererApplyState.get()
            val previousStatus = previousState.status
            if ((previousStatus?.generation ?: Long.MIN_VALUE) > next.generation) break
            if (previousStatus?.generation == next.generation && previousStatus.phase != DspRendererApplyPhase.REQUESTED) break
            val lastActiveGeneration = previousStatus?.activeGeneration ?: previousStatus?.generation?.takeIf {
                previousStatus.phase == DspRendererApplyPhase.ACTIVE
            }
            val requested = DspRendererApplyStatus(
                generation = next.generation,
                phase = DspRendererApplyPhase.REQUESTED,
                activeGeneration = lastActiveGeneration,
                rendererOwnerToken = previousState.ownerToken,
            )
            if (rendererApplyState.compareAndSet(previousState, previousState.copy(status = requested))) break
        }
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    private data class PlaybackSampleRate(val revision: Long, val sampleRateHz: Int)

    private data class RendererApplyState(
        val ownerToken: Long?,
        val status: DspRendererApplyStatus?,
    )

    private companion object {
        const val MIN_PLAYBACK_SAMPLE_RATE_HZ = 8_000
        const val MAX_PLAYBACK_SAMPLE_RATE_HZ = 192_000
    }
}
