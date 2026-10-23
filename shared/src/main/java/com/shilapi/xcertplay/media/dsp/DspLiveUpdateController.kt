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
    private val retiredPipelineCount = AtomicInteger()

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
                val pipeline = pipelinePreparer(format, snapshot.config)
                registerPipeline(pipeline)
                if (pipeline != null && pipeline.format != format) {
                    runCatching { pipeline.close() }
                    lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                    runCatching { onDeferred(snapshot.generation) }
                    continue
                }
                val latencyFrames = preparedLatencyFrames(pipeline).coerceAtLeast(0)
                val deferLatencyChange = latencyFrames != activeLatencyFrames() && !canAdoptLatencyChange()
                if (desired.get()?.generation != snapshot.generation) {
                    runCatching { pipeline?.close() }
                    continue
                }
                if (closed.get()) {
                    runCatching { pipeline?.close() }
                    return
                }
                lastHandledGeneration.updateAndGet { maxOf(it, snapshot.generation) }
                val published = runCatching {
                    onPrepared(DspPreparedUpdate(snapshot.generation, format, snapshot.config, pipeline, latencyFrames))
                }.onFailure { runCatching { pipeline?.close() } }
                if (published.isSuccess && deferLatencyChange) {
                    runCatching { onDeferred(snapshot.generation) }
                }
            }
        } finally {
            preparing.set(false)
            if (!closed.get() && (desired.get()?.generation ?: 0L) > lastHandledGeneration.get()) {
                schedulePreparation()
            }
        }
    }

    fun canAdoptUpdate(): Boolean = !closed.get() && retiredPipelineCount.get() == 0

    fun registerPipeline(pipeline: DspPcmPipeline?) {
        pipeline?.registerRetirementCounter(retiredPipelineCount)
    }

    fun retire(pipeline: DspPcmPipeline?) {
        if (pipeline == null || !pipeline.claimRetirement()) return
        registerPipeline(pipeline)
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
