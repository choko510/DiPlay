package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

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
    private val onDeferred: (Long) -> Unit = { },
    private val canAdoptLatencyChange: () -> Boolean = { false },
    private val pipelinePreparer: DspPipelinePreparer =
        { targetFormat, config -> createNativePipeline(role, targetFormat, config) },
) : Closeable {
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dsp-live-control").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val preparing = AtomicBoolean(false)
    private val lastHandledGeneration = AtomicLong(initialGeneration)
    private val desired = AtomicReference<DspConfigSnapshot?>(null)
    private val retiredPipelines = AtomicReferenceArray<DspPcmPipeline?>(MAX_RETIRED_PIPELINES)
    private val overflowRetiredPipeline = AtomicReference<DspPcmPipeline?>(null)
    @Volatile private var maintenance: ScheduledFuture<*>? = null
    private val subscription = provider.addListener(::onConfigChanged)

    private fun onConfigChanged(snapshot: DspConfigSnapshot) {
        if (closed.get() || snapshot.generation <= lastHandledGeneration.get()) return
        desired.set(snapshot)
        runCatching { onPreparing(snapshot) }
        ensureMaintenance()
        schedulePreparation()
    }

    private fun ensureMaintenance() {
        if (maintenance != null) return
        synchronized(this) {
            if (maintenance == null && !closed.get()) {
                maintenance = controlExecutor.scheduleWithFixedDelay(
                    ::closeRetiredPipeline,
                    MAINTENANCE_INTERVAL_MS,
                    MAINTENANCE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
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
                val pipeline = pipelinePreparer(format, snapshot.config)
                if (pipeline != null && pipeline.format != format) {
                    runCatching { pipeline.close() }
                    lastHandledGeneration.set(snapshot.generation)
                    runCatching { onDeferred(snapshot.generation) }
                    continue
                }
                val latencyFrames = pipeline?.algorithmicLatencyFrames ?: 0
                if (latencyFrames != activeLatencyFrames() && !canAdoptLatencyChange()) {
                    runCatching { pipeline?.close() }
                    lastHandledGeneration.set(snapshot.generation)
                    runCatching { onDeferred(snapshot.generation) }
                    continue
                }
                if (desired.get()?.generation != snapshot.generation) {
                    runCatching { pipeline?.close() }
                    continue
                }
                if (closed.get()) {
                    runCatching { pipeline?.close() }
                    return
                }
                lastHandledGeneration.set(snapshot.generation)
                runCatching { onPrepared(DspPreparedUpdate(snapshot.generation, format, snapshot.config, pipeline, latencyFrames)) }
                    .onFailure { runCatching { pipeline?.close() } }
            }
        } finally {
            preparing.set(false)
            if (!closed.get() && (desired.get()?.generation ?: 0L) > lastHandledGeneration.get()) {
                schedulePreparation()
            }
        }
    }

    fun canAdoptUpdate(): Boolean = !closed.get() && overflowRetiredPipeline.get() == null &&
        (0 until MAX_RETIRED_PIPELINES).all { retiredPipelines.get(it) == null }

    fun retire(pipeline: DspPcmPipeline?) {
        if (pipeline == null) return
        for (slot in 0 until MAX_RETIRED_PIPELINES) {
            if (retiredPipelines.compareAndSet(slot, null, pipeline)) return
        }
        overflowRetiredPipeline.compareAndSet(null, pipeline)
    }

    private fun closeRetiredPipeline() {
        for (slot in 0 until MAX_RETIRED_PIPELINES) {
            runCatching { retiredPipelines.getAndSet(slot, null)?.close() }
        }
        runCatching { overflowRetiredPipeline.getAndSet(null)?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription.close()
        maintenance?.cancel(false)
        closeRetiredPipeline()
        controlExecutor.shutdown()
    }
}

private const val MAINTENANCE_INTERVAL_MS = 10L
private const val MAX_RETIRED_PIPELINES = 8

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
