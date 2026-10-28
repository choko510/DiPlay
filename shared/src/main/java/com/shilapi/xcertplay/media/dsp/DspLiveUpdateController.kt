package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DspPreparedUpdate(
    val generation: Long,
    val format: DspAudioFormat,
    val config: DspRuntimeConfig,
    val pipeline: DspPcmPipeline?,
    val latencyFrames: Int,
)

internal typealias DspPipelinePreparer = (DspAudioFormat, DspRuntimeConfig) -> DspPcmPipeline?

internal class DspLiveUpdateController(
    private val format: DspAudioFormat,
    private val role: DspStreamRole,
    private val provider: DspConfigProvider,
    initialGeneration: Long,
    private val activeLatencyFrames: () -> Int,
    private val onPrepared: (DspPreparedUpdate) -> Unit,
    private val onPreparing: (DspConfigSnapshot) -> Unit = { },
    private val onFailed: (DspConfigSnapshot, Throwable) -> Unit = { _, _ -> },
    private val onDeferred: (Long) -> Unit = { },
    private val canAdoptLatencyChange: () -> Boolean = { false },
    private val pipelinePreparer: DspPipelinePreparer =
        { targetFormat, config -> createNativePipeline(role, targetFormat, config) },
    private val preparedLatencyFrames: (DspPcmPipeline?) -> Int = { it?.algorithmicLatencyFrames ?: 0 },
    private val onPreparedResult: ((DspPreparedUpdate) -> Boolean)? = null,
    private val onRetirementFailed: (DspPcmPipeline, Throwable) -> Unit = { _, _ -> },
    private val onRetirementCompleted: (DspPcmPipeline, Long) -> Unit = { _, _ -> },
) : Closeable {
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dsp-live-control").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val preparing = AtomicBoolean(false)
    private val lastHandledGeneration = AtomicLong(initialGeneration)
    private val desired = AtomicReference<DspConfigSnapshot?>(null)
    private val generationLock = Any()
    private val retiredPipelineCount = AtomicInteger()
    private val failedRetirementCount = AtomicInteger()

    init {
        DspPipelineReaper.start()
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
        if (!preparing.compareAndSet(false, true)) return
        runCatching { controlExecutor.execute(::prepareLatest) }.onFailure { preparing.set(false) }
    }

    private fun prepareLatest() {
        try {
            while (!closed.get()) {
                val snapshot = desired.get() ?: return
                if (snapshot.generation <= lastHandledGeneration.get()) return
                var candidate: DspPcmPipeline? = null
                try {
                    candidate = pipelinePreparer(format, snapshot.config)
                    candidate?.configGeneration = snapshot.generation
                    if (candidate != null && candidate.format != format) {
                        retire(candidate)
                        candidate = null
                        lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                        runCatching { onDeferred(snapshot.generation) }
                        continue
                    }
                    val latencyFrames = preparedLatencyFrames(candidate).coerceAtLeast(0)
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
                    val update = DspPreparedUpdate(snapshot.generation, format, snapshot.config, candidate, latencyFrames)
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
                    if (transferred && deferLatencyChange) runCatching { onDeferred(snapshot.generation) }
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
            preparing.set(false)
            if (!closed.get() && (desired.get()?.generation ?: 0L) > lastHandledGeneration.get()) {
                schedulePreparation()
            }
        }
    }

    fun canAdoptUpdate(): Boolean = !closed.get() && retiredPipelineCount.get() == 0 && failedRetirementCount.get() == 0

    fun retire(pipeline: DspPcmPipeline?) {
        if (pipeline == null) return
        val claimed = pipeline.claimRetirement(
            counter = retiredPipelineCount,
            onCloseFailure = { error ->
                failedRetirementCount.incrementAndGet()
                runCatching { onRetirementFailed(pipeline, error) }
            },
            onCloseComplete = { runCatching { onRetirementCompleted(pipeline, pipeline.closeDurationNs) } },
        )
        if (!claimed) return
        retiredPipelineCount.incrementAndGet()
        DspPipelineReaper.enqueue(pipeline)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription.close()
        controlExecutor.shutdown()
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
