package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DspLiveUpdateControllerTest {
    @Test
    fun peqSnapshotPreparesOffTheAudioThread() {
        val provider = MutableConfigProvider()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val prepareThread = AtomicReference<String?>(null)
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, config ->
                prepareThread.set(Thread.currentThread().name)
                trackingPipeline(format, 0) { }
            },
        )

        provider.update(
            DspRuntimeConfig(
                enabled = true,
                peqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 6.0, enabled = true)),
            ),
        )
        val candidate = prepared.poll(5, TimeUnit.SECONDS)

        assertNotNull(candidate)
        assertEquals(6.0, candidate?.config?.peqBands?.single()?.gainDb ?: 0.0, 0.0)
        assertEquals("dsp-live-control", prepareThread.get())
        candidate?.pipeline?.close()
        controller.close()
    }

    @Test
    fun rapidUpdatesPrepareOnlyTheLatestGraphAndCloseTheStaleCandidate() {
        val provider = MutableConfigProvider()
        val firstPreparationStarted = CountDownLatch(1)
        val releaseFirstPreparation = CountDownLatch(1)
        val stalePipelineClosed = CountDownLatch(1)
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, config ->
                if (config.gainDb == 1.0) {
                    firstPreparationStarted.countDown()
                    check(releaseFirstPreparation.await(5, TimeUnit.SECONDS))
                    trackingPipeline(format, 0) { stalePipelineClosed.countDown() }
                } else {
                    trackingPipeline(format, 0) { }
                }
            },
        )

        provider.update(DspRuntimeConfig(enabled = true, gainDb = 1.0))
        assertTrue(firstPreparationStarted.await(5, TimeUnit.SECONDS))
        provider.update(DspRuntimeConfig(enabled = true, gainDb = 2.0))
        releaseFirstPreparation.countDown()

        val latest = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(latest)
        assertEquals(2L, latest?.generation)
        assertEquals(2.0, latest?.config?.gainDb ?: 0.0, 0.0)
        assertTrue(stalePipelineClosed.await(5, TimeUnit.SECONDS))
        assertNull(prepared.poll(100, TimeUnit.MILLISECONDS))
        latest?.pipeline?.close()
        controller.close()
    }

    @Test
    fun equalLatencyGraphIsPreparedAndLatencyChangingGraphIsDeferred() {
        val initialImpulse = DspImpulseResponse("room_old", 48_000, 1, floatArrayOf(1.0f))
        val replacementImpulse = DspImpulseResponse("room_new", 48_000, 1, floatArrayOf(0.0f, 1.0f))
        val initialConfig = DspRuntimeConfig(
            enabled = true,
            convolver = DspConvolverConfig(
                enabled = true,
                impulseResponseId = initialImpulse.id,
                impulseResponse = initialImpulse,
            ),
        )
        val provider = MutableConfigProvider(initialConfig)
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val deferredPipelineClosed = CountDownLatch(1)
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 128 },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, config ->
                val latency = if (config.convolver.enabled) 128 else 0
                trackingPipeline(format, latency) {
                    if (latency == 0) deferredPipelineClosed.countDown()
                }
            },
        )

        provider.update(
            DspRuntimeConfig(
                enabled = true,
                convolver = DspConvolverConfig(
                    enabled = true,
                    impulseResponseId = replacementImpulse.id,
                    impulseResponse = replacementImpulse,
                ),
            ),
        )
        val sameLatency = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(sameLatency)
        assertEquals(128, sameLatency?.latencyFrames)
        assertEquals(replacementImpulse.id, sameLatency?.config?.convolver?.impulseResponseId)
        sameLatency?.pipeline?.close()

        provider.update(DspRuntimeConfig(enabled = true, gainDb = 2.0))
        assertTrue(deferredPipelineClosed.await(5, TimeUnit.SECONDS))
        assertNull(prepared.poll(100, TimeUnit.MILLISECONDS))
        controller.close()
    }

    @Test
    fun retiredGraphClosesOnTheControlThreadAfterItIsNoLongerActive() {
        val provider = MutableConfigProvider()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val retiredClosed = CountDownLatch(1)
        val closeThread = AtomicReference<String?>(null)
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 1),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, _ ->
                trackingPipeline(format, 0) {
                    closeThread.set(Thread.currentThread().name)
                    retiredClosed.countDown()
                }
            },
        )

        provider.update(DspRuntimeConfig(enabled = true, gainDb = 3.0))
        val update = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(update?.pipeline)
        controller.retire(update?.pipeline)

        assertTrue(retiredClosed.await(5, TimeUnit.SECONDS))
        assertEquals("dsp-live-control", closeThread.get())
        controller.close()
    }

    private fun trackingPipeline(
        format: DspAudioFormat,
        latencyFrames: Int,
        onClose: () -> Unit,
    ): DspPcmPipeline = DspPcmPipeline(
        format = format,
        processor = TrackingProcessor(format, latencyFrames, onClose),
        forceInitialLatencySilence = false,
    )

    private class TrackingProcessor(
        override val format: DspAudioFormat,
        override val latencyFrames: Int,
        private val onClose: () -> Unit,
    ) : DspProcessor {
        private val result = DspProcessResult()

        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult {
            while (input.hasRemaining()) output.putFloat(input.float)
            return result.success(frames, latencyFrames)
        }

        override fun reset() = Unit

        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY

        override fun close() = onClose()
    }

    private class MutableConfigProvider(initial: DspRuntimeConfig = DspRuntimeConfig.disabled()) : DspConfigProvider {
        private val current = AtomicReference(DspConfigSnapshot(0L, initial))
        private val listeners = CopyOnWriteArrayList<(DspConfigSnapshot) -> Unit>()

        override fun snapshot(): DspRuntimeConfig = current.get().config

        override fun versionedSnapshot(): DspConfigSnapshot = current.get()

        override fun addListener(listener: (DspConfigSnapshot) -> Unit): Closeable {
            listeners.add(listener)
            listener(current.get())
            return Closeable { listeners.remove(listener) }
        }

        fun update(config: DspRuntimeConfig) {
            val updated = current.updateAndGet { DspConfigSnapshot(it.generation + 1, config) }
            listeners.forEach { it(updated) }
        }
    }
}
