package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val preparedLatencyFrames: (DspPcmPipeline?) -> Int = { it?.algorithmicLatencyFrames ?: 0 },
) : Closeable {
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dsp-live-control").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val preparing = AtomicBoolean(false)
    private val lastHandledGeneration = AtomicLong(initialGeneration)
    private val desired = AtomicReference<DspConfigSnapshot?>(null)
    private val generationLock = Any()
    private val maintenanceLock = Any()
    private val retiredPipelines = AtomicReferenceArray<DspPcmPipeline?>(MAX_RETIRED_PIPELINES)
    private val overflowRetiredPipeline = AtomicReference<DspPcmPipeline?>(null)
    private val additionalRetiredPipelines = ConcurrentLinkedQueue<DspPcmPipeline>()
    @Volatile private var maintenance: ScheduledFuture<*>? = null
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

    private fun ensureMaintenanceLocked() {
        if (closed.get() || !hasRetiredPipelines()) return
        if (maintenance == null || maintenance?.isDone == true) {
            maintenance = controlExecutor.scheduleWithFixedDelay(
                ::closeRetiredPipeline,
                MAINTENANCE_INTERVAL_MS,
                MAINTENANCE_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun hasRetiredPipelines(): Boolean =
        overflowRetiredPipeline.get() != null || additionalRetiredPipelines.isNotEmpty() ||
            (0 until MAX_RETIRED_PIPELINES).any { retiredPipelines.get(it) != null }

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
                    lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                    runCatching { onDeferred(snapshot.generation) }
                    continue
                }
                val latencyFrames = preparedLatencyFrames(pipeline).coerceAtLeast(0)
                if (latencyFrames != activeLatencyFrames() && !canAdoptLatencyChange()) {
                    runCatching { pipeline?.close() }
                    lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
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
                lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
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
        additionalRetiredPipelines.isEmpty() && (0 until MAX_RETIRED_PIPELINES).all { retiredPipelines.get(it) == null }

    fun retire(pipeline: DspPcmPipeline?) {
        if (pipeline == null) return
        var closeOnReaper = false
        synchronized(maintenanceLock) {
            if (closed.get()) {
                closeOnReaper = true
            } else {
                var stored = false
                for (slot in 0 until MAX_RETIRED_PIPELINES) {
                    if (retiredPipelines.compareAndSet(slot, null, pipeline)) {
                        stored = true
                        break
                    }
                }
                if (!stored) stored = overflowRetiredPipeline.compareAndSet(null, pipeline)
                if (!stored) additionalRetiredPipelines.add(pipeline)
                ensureMaintenanceLocked()
            }
        }
        if (closeOnReaper) {
            runCatching {
                Thread({ runCatching { pipeline.close() } }, "dsp-retired-close").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun closeRetiredPipeline() {
        for (slot in 0 until MAX_RETIRED_PIPELINES) {
            runCatching { retiredPipelines.getAndSet(slot, null)?.close() }
        }
        runCatching { overflowRetiredPipeline.getAndSet(null)?.close() }
        while (true) {
            val pipeline = additionalRetiredPipelines.poll() ?: break
            runCatching { pipeline.close() }
        }
        synchronized(maintenanceLock) {
            if (!hasRetiredPipelines()) {
                maintenance?.cancel(false)
                maintenance = null
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription.close()
        synchronized(maintenanceLock) {
            maintenance?.cancel(false)
            maintenance = null
        }
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
