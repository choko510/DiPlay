package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal data class DspPreparedUpdate(
    val generation: Long,
    val format: DspAudioFormat,
    val config: DspRuntimeConfig,
    val pipeline: DspPcmPipeline?,
    val latencyFrames: Int,
    val hotUpdate: DspPreparedHotUpdate? = null,
    val localPeqWarmUpdate: DspPreparedLocalPeqWarmUpdate? = null,
    val localWarmFailureReason: String? = null,
)

internal data class DspLocalPeqWarmPreparation(
    val update: DspPreparedLocalPeqWarmUpdate? = null,
    val unavailableReason: String? = null,
)

internal typealias DspPipelinePreparer = (DspAudioFormat, DspRuntimeConfig) -> DspPcmPipeline?
internal typealias DspLocalPeqWarmPreparer = (DspAudioFormat, DspConfigSnapshot) -> DspLocalPeqWarmPreparation?

internal class DspLiveUpdateController(
    private val format: DspAudioFormat,
    private val role: DspStreamRole,
    private val provider: DspConfigProvider,
    initialGeneration: Long,
    private val activeLatencyFrames: () -> Int,
    private val onPrepared: (DspPreparedUpdate) -> Unit,
    private val onPreparing: (DspConfigSnapshot) -> Unit = { },
    private val onFailed: (DspConfigSnapshot, Throwable) -> Unit = { _, _ -> },
    private val onDeferred: (Long, String) -> Unit = { _, _ -> },
    private val canAdoptLatencyChange: () -> Boolean = { false },
    private val pipelinePreparer: DspPipelinePreparer =
        { targetFormat, config -> createNativePipeline(role, targetFormat, config) },
    private val preparedLatencyFrames: (DspPcmPipeline?) -> Int = { it?.algorithmicLatencyFrames ?: 0 },
    private val onPreparedResult: ((DspPreparedUpdate) -> Boolean)? = null,
    private val onRetirementFailed: (DspPcmPipeline, Throwable) -> Unit = { _, _ -> },
    private val hotUpdatePreparer: ((DspAudioFormat, DspConfigSnapshot) -> DspPreparedHotUpdate?)? = null,
    private val localPeqWarmPreparer: DspLocalPeqWarmPreparer? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val workRequested = AtomicBoolean(false)
    private val lastHandledGeneration = AtomicLong(initialGeneration)
    private val desired = AtomicReference<DspConfigSnapshot?>(null)
    private val reprepareRequestedGeneration = AtomicLong(-1L)
    private val generationLock = Any()
    private val retiredPipelineCount = AtomicInteger()
    private val failedRetirementCount = AtomicInteger()
    private val controlThread = Thread(::runControlLoop, "dsp-live-control").apply { isDaemon = true }

    init {
        DspPipelineReaper.start()
        controlThread.start()
    }

    private val subscription = provider.addListener(::onConfigChanged)

    private fun onConfigChanged(snapshot: DspConfigSnapshot) {
        val accepted = synchronized(generationLock) {
            if (closed.get()) return@synchronized false
            val previous = desired.get()
            val newestKnownGeneration = maxOf(lastHandledGeneration.get(), previous?.generation ?: Long.MIN_VALUE)
            if (snapshot.generation <= newestKnownGeneration || !desired.compareAndSet(previous, snapshot)) {
                return@synchronized false
            }
            runCatching { onPreparing(snapshot) }
            true
        }
        if (!accepted) return
        schedulePreparation()
    }

    private fun schedulePreparation() {
        if (closed.get()) return
        workRequested.set(true)
        LockSupport.unpark(controlThread)
    }

    private fun runControlLoop() {
        while (!closed.get()) {
            if (!workRequested.getAndSet(false)) {
                LockSupport.park()
                continue
            }
            prepareLatest()
        }
    }

    private fun prepareLatest() {
        try {
            while (!closed.get()) {
                val snapshot = desired.get() ?: return
                val reprepareRequested = reprepareRequestedGeneration.compareAndSet(snapshot.generation, -1L)
                if (snapshot.generation <= lastHandledGeneration.get() && !reprepareRequested) return
                var candidate: DspPcmPipeline? = null
                try {
                    val hotUpdate = hotUpdatePreparer?.invoke(format, snapshot)
                    val localPeqWarm = if (hotUpdate == null) localPeqWarmPreparer?.invoke(format, snapshot) else null
                    if (hotUpdate == null && localPeqWarm?.update == null) {
                        candidate = pipelinePreparer(format, snapshot.config)
                    }
                    candidate?.configGeneration = snapshot.generation
                    if (candidate != null && candidate.format != format) {
                        retire(candidate)
                        candidate = null
                        lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                        runCatching {
                            onDeferred(
                                snapshot.generation,
                                "prepared DSP pipeline format does not match the active PCM format",
                            )
                        }
                        continue
                    }
                    val latencyFrames = if (hotUpdate != null || localPeqWarm?.update != null) {
                        activeLatencyFrames().coerceAtLeast(0)
                    }
                    else preparedLatencyFrames(candidate).coerceAtLeast(0)
                    val deferLatencyChange = latencyFrames != activeLatencyFrames() && !canAdoptLatencyChange()
                    if (desired.get()?.generation != snapshot.generation) {
                        retire(candidate)
                        candidate = null
                        continue
                    }
                    if (closed.get()) {
                        retire(candidate)
                        candidate = null
                        return
                    }
                    lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                    val update = DspPreparedUpdate(
                        snapshot.generation,
                        format,
                        snapshot.config,
                        candidate,
                        latencyFrames,
                        hotUpdate,
                        localPeqWarm?.update,
                        localPeqWarm?.unavailableReason,
                    )
                    val transferred = onPreparedResult?.invoke(update) ?: run {
                        onPrepared(update)
                        true
                    }
                    if (transferred) {
                        candidate = null
                    } else {
                        retire(candidate)
                        candidate = null
                    }
                    if (transferred && deferLatencyChange) {
                        runCatching {
                            onDeferred(
                                snapshot.generation,
                                "DSP latency change is deferred until the next AudioTrack generation",
                            )
                        }
                    }
                } catch (error: Throwable) {
                    val failedCandidate = candidate
                    candidate = null
                    retire(failedCandidate)
                    lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                    runCatching { onFailed(snapshot, error) }
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                }
            }
        } finally {
            val latestGeneration = desired.get()?.generation ?: 0L
            if (!closed.get() && (latestGeneration > lastHandledGeneration.get() ||
                    reprepareRequestedGeneration.get() == latestGeneration)
            ) {
                workRequested.set(true)
            }
        }
    }

    fun canAdoptUpdate(): Boolean = !closed.get() && retiredPipelineCount.get() == 0 && failedRetirementCount.get() == 0

    fun reprepareLatest(generation: Long) {
        if (closed.get() || desired.get()?.generation != generation) return
        reprepareRequestedGeneration.updateAndGet { previous -> maxOf(previous, generation) }
        schedulePreparation()
    }

    fun retire(pipeline: DspPcmPipeline?) {
        if (pipeline == null || !pipeline.claimRetirement(retiredPipelineCount) { error ->
                failedRetirementCount.incrementAndGet()
                runCatching { onRetirementFailed(pipeline, error) }
            }
        ) return
        retiredPipelineCount.incrementAndGet()
        DspPipelineReaper.enqueue(pipeline)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription.close()
        LockSupport.unpark(controlThread)
    }
}

internal fun createNativePipeline(
    role: DspStreamRole,
    format: DspAudioFormat,
    config: DspRuntimeConfig,
): DspPcmPipeline? {
    if (!DspStreamPolicy.shouldProcess(config.enabled, role)) return null
    val processor = NativeDspProcessor.createOrNull(format, config) ?: return null
    return try {
        DspPcmPipeline(format, processor, forceInitialLatencySilence = false)
    } catch (_: Exception) {
        runCatching { processor.close() }
        null
    }
}
