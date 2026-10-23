package com.shilapi.xcertplay.media.dsp

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DspLiveUpdateControllerTest {
    @Test
    fun initialLatencyCandidateIsAllowedBeforeAnyTrackContentIsCommitted() {
        val provider = MutableConfigProvider()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            canAdoptLatencyChange = { true },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, _ -> trackingPipeline(format, 128) { } },
        )

        provider.update(DspRuntimeConfig(enabled = true))
        val initial = prepared.poll(5, TimeUnit.SECONDS)

        assertNotNull(initial)
        assertEquals(128, initial?.latencyFrames)
        assertEquals(DspAudioFormat(48_000, 2), initial?.format)
        initial?.pipeline?.close()
        controller.close()
    }

    @Test
    fun initialLatencyCandidateCompletingAfterFirstCommitIsPreparedForNextGeneration() {
        val provider = MutableConfigProvider()
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deferred = LinkedBlockingQueue<Long>()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val committed = AtomicBoolean(false)
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            canAdoptLatencyChange = { !committed.get() },
            onPrepared = { prepared.offer(it) },
            onDeferred = { deferred.offer(it) },
            pipelinePreparer = { format, _ ->
                preparing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                trackingPipeline(format, 128) { }
            },
        )

        provider.update(DspRuntimeConfig(enabled = true))
        assertTrue(preparing.await(5, TimeUnit.SECONDS))
        committed.set(true)
        release.countDown()

        val update = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(update)
        assertEquals(128, update?.latencyFrames)
        assertEquals(1L, deferred.poll(5, TimeUnit.SECONDS))
        update?.pipeline?.close()
        controller.close()
    }

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
    fun preparationExceptionMarksOnlyThatGenerationFailedWithoutRetryLoop() {
        val provider = MutableConfigProvider()
        val prepareCalls = AtomicInteger()
        val failed = LinkedBlockingQueue<Pair<Long, String>>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = { fail("a throwing preparation cannot publish a candidate") },
            onFailed = { snapshot, error -> failed.offer(snapshot.generation to error.javaClass.simpleName) },
            pipelinePreparer = { _, _ ->
                prepareCalls.incrementAndGet()
                throw IllegalStateException("fixture failure")
            },
        )

        try {
            provider.update(DspRuntimeConfig(enabled = true))
            assertEquals(1L to "IllegalStateException", failed.poll(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(1, prepareCalls.get())
            assertNull(failed.poll(50, TimeUnit.MILLISECONDS))

            provider.update(DspRuntimeConfig(enabled = true, gainDb = 1.0))
            assertEquals(2L to "IllegalStateException", failed.poll(5, TimeUnit.SECONDS))
            assertEquals(2, prepareCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun preparedGraphRemainsAdoptableWithoutRetiredPipelines() {
        val provider = MutableConfigProvider()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, _ -> trackingPipeline(format, 0) { } },
        )

        try {
            provider.update(DspRuntimeConfig(enabled = true))
            val update = prepared.poll(5, TimeUnit.SECONDS)

            assertNotNull(update)
            assertTrue(controller.canAdoptUpdate())
            update?.pipeline?.close()
        } finally {
            controller.close()
        }
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
    fun lateOlderNotificationCannotRegressPreparingOrPreparedGeneration() {
        val provider = MutableConfigProvider()
        val generationTwoStarted = CountDownLatch(1)
        val releaseGenerationTwo = CountDownLatch(1)
        val preparing = LinkedBlockingQueue<Long>()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPreparing = { preparing.offer(it.generation) },
            onPrepared = { prepared.offer(it) },
            pipelinePreparer = { format, config ->
                if (config.gainDb == 2.0) {
                    generationTwoStarted.countDown()
                    check(releaseGenerationTwo.await(5, TimeUnit.SECONDS))
                }
                trackingPipeline(format, 0) { }
            },
        )

        try {
            provider.notifySnapshot(DspConfigSnapshot(2L, DspRuntimeConfig(enabled = true, gainDb = 2.0)))
            assertTrue(generationTwoStarted.await(5, TimeUnit.SECONDS))
            provider.notifySnapshot(DspConfigSnapshot(1L, DspRuntimeConfig(enabled = true, gainDb = 1.0)))
            releaseGenerationTwo.countDown()

            val latest = prepared.poll(5, TimeUnit.SECONDS)
            assertNotNull(latest)
            assertEquals(2L, latest?.generation)
            assertEquals(2.0, latest?.config?.gainDb ?: 0.0, 0.0)
            assertEquals(2L, preparing.poll(5, TimeUnit.SECONDS))
            assertNull(preparing.poll(100, TimeUnit.MILLISECONDS))
            assertNull(prepared.poll(100, TimeUnit.MILLISECONDS))
            latest?.pipeline?.close()
        } finally {
            releaseGenerationTwo.countDown()
            controller.close()
        }
    }

    @Test
    fun equalAndLatencyChangingGraphsArePreparedForRendererSafeAdoption() {
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
        val deferred = LinkedBlockingQueue<Long>()
        val controller = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 2),
            role = DspStreamRole.MEDIA,
            provider = provider,
            initialGeneration = 0L,
            activeLatencyFrames = { 128 },
            onPrepared = { prepared.offer(it) },
            onDeferred = { deferred.offer(it) },
            pipelinePreparer = { format, config ->
                val latency = if (config.convolver.enabled) 128 else 0
                trackingPipeline(format, latency) { }
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
        val latencyChange = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(latencyChange)
        assertEquals(0, latencyChange?.latencyFrames)
        assertEquals(2L, deferred.poll(5, TimeUnit.SECONDS))
        latencyChange?.pipeline?.close()
        controller.close()
    }

    @Test
    fun retiredGraphClosesOnDedicatedReaperWithoutBlockingTheCaller() {
        val provider = MutableConfigProvider()
        val prepared = LinkedBlockingQueue<DspPreparedUpdate>()
        val retiredCloseStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val retiredClosed = CountDownLatch(1)
        val retireReturned = CountDownLatch(1)
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
                    retiredCloseStarted.countDown()
                    check(allowClose.await(5, TimeUnit.SECONDS))
                    retiredClosed.countDown()
                }
            },
        )

        provider.update(DspRuntimeConfig(enabled = true, gainDb = 3.0))
        val update = prepared.poll(5, TimeUnit.SECONDS)
        assertNotNull(update?.pipeline)
        val audioThread = Thread({
            controller.retire(update?.pipeline)
            retireReturned.countDown()
        }, "audio-render-test")
        audioThread.start()

        try {
            assertTrue(retireReturned.await(1, TimeUnit.SECONDS))
            assertTrue(retiredCloseStarted.await(5, TimeUnit.SECONDS))
            assertTrue(closeThread.get().orEmpty().startsWith("dsp-pipeline-reaper"))
            assertTrue("retired storage prevents another adoption", !controller.canAdoptUpdate())
            allowClose.countDown()
            assertTrue(retiredClosed.await(5, TimeUnit.SECONDS))
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!controller.canAdoptUpdate() && System.nanoTime() < deadlineNs) Thread.yield()
            assertTrue(controller.canAdoptUpdate())
        } finally {
            allowClose.countDown()
            audioThread.join(1_000)
            controller.close()
        }
    }

    @Test
    fun aBlockedReaperWorkerDoesNotHoldAnotherControllersRetirement() {
        val firstCloseStarted = CountDownLatch(1)
        val releaseFirstClose = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        val firstController = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 1),
            role = DspStreamRole.MEDIA,
            provider = MutableConfigProvider(),
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = {},
        )
        val secondController = DspLiveUpdateController(
            format = DspAudioFormat(48_000, 1),
            role = DspStreamRole.MEDIA,
            provider = MutableConfigProvider(),
            initialGeneration = 0L,
            activeLatencyFrames = { 0 },
            onPrepared = {},
        )
        val firstPipeline = trackingPipeline(DspAudioFormat(48_000, 1), 0) {
            firstCloseStarted.countDown()
            check(releaseFirstClose.await(5, TimeUnit.SECONDS))
            firstClosed.countDown()
        }
        val secondPipeline = trackingPipeline(DspAudioFormat(48_000, 1), 0) {
            secondClosed.countDown()
        }
        val firstRetirement = Thread({ firstController.retire(firstPipeline) }, "first-controller-retire")

        try {
            firstRetirement.start()
            assertTrue(firstCloseStarted.await(5, TimeUnit.SECONDS))
            secondController.retire(secondPipeline)

            assertTrue(secondClosed.await(5, TimeUnit.SECONDS))
            assertFalse(firstController.canAdoptUpdate())
            assertTrue(secondController.canAdoptUpdate())

            releaseFirstClose.countDown()
            assertTrue(firstClosed.await(5, TimeUnit.SECONDS))
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!firstController.canAdoptUpdate() && System.nanoTime() < deadlineNs) Thread.yield()
            assertTrue(firstController.canAdoptUpdate())
        } finally {
            releaseFirstClose.countDown()
            firstRetirement.join(1_000)
            firstController.close()
            secondController.close()
        }
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

        fun notifySnapshot(snapshot: DspConfigSnapshot) {
            listeners.forEach { it(snapshot) }
        }
    }
}
