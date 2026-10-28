package com.shilapi.xcertplay.media

import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun surfaceAndConfigChurnKeepsOnlyBoundedControlJobsAndLatestConfig() {
        val queue = VideoDecodeQueue(maxFrames = 60)
        repeat(10_000) { index ->
            queue.offer(VideoJob.Config(VideoCodec.H264, byteArrayOf(index.toByte())))
            queue.offer(VideoJob.SurfaceChanged(null))
            queue.offer(VideoJob.Frame(byteArrayOf(index.toByte())))
        }

        assertTrue(queue.queuedJobsForTest <= 64)
        val jobs = mutableListOf<VideoJob>()
        while (true) jobs += queue.poll(0) ?: break
        val configs = jobs.filterIsInstance<VideoJob.Config>()
        val surfaces = jobs.filterIsInstance<VideoJob.SurfaceChanged>()

        assertEquals(1, configs.size)
        assertArrayEquals(byteArrayOf(9_999.toByte()), configs.single().codecData)
        assertEquals(1, surfaces.size)
        assertTrue(jobs.filterIsInstance<VideoJob.Frame>().size <= 60)
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }

    @Test fun pendingOutputWaitIsBoundedAndExpiresAfterFiveHundredMilliseconds() {
        val pending = VideoPendingOutputState()
        pending.onInputQueued(0L)

        assertEquals(8_000L, pending.nextDequeueTimeoutUs(0L))
        pending.onInputQueued(250_000_000L)
        assertEquals(8_000L, pending.nextDequeueTimeoutUs(250_000_000L))
        assertEquals(1L, pending.nextDequeueTimeoutUs(749_999_999L))
        assertEquals(0L, pending.nextDequeueTimeoutUs(750_000_000L))
        assertEquals(0, pending.pendingOutputCount)
        assertEquals(1L, pending.waitTimeoutCount)
        assertEquals(0L, pending.nextDequeueTimeoutUs(800_000_000L))
    }

    @Test fun pendingOutputCountIsBoundedAndNeverBecomesNegative() {
        val pending = VideoPendingOutputState()
        repeat(10_000) { pending.onInputQueued(it.toLong()) }

        assertEquals(64, pending.pendingOutputCount)
        repeat(65) { pending.onOutputDequeued(renderableFrame = true) }
        assertEquals(0, pending.pendingOutputCount)
        pending.reset()
        assertEquals(0, pending.pendingOutputCount)
    }

    @Test fun codecConfigAndEmptyOutputsKeepPendingPictureWaitAlive() {
        val pending = VideoPendingOutputState()
        val firstFrame = VideoFirstFrameSubmissionState()
        pending.onInputQueued(0L)

        val codecConfig = videoOutputBufferDisposition(
            size = 32,
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
            surfaceAvailable = true,
        )
        assertFalse(codecConfig.renderableFrame)
        assertFalse(codecConfig.submitToSurface)
        pending.onOutputDequeued(codecConfig.renderableFrame)
        assertEquals(1, pending.pendingOutputCount)
        assertFalse(firstFrame.onOutputSubmittedToSurface(codecConfig.submitToSurface))

        val emptyOutput = videoOutputBufferDisposition(size = 0, flags = 0, surfaceAvailable = true)
        pending.onOutputDequeued(emptyOutput.renderableFrame)
        assertEquals(1, pending.pendingOutputCount)
        assertFalse(firstFrame.onOutputSubmittedToSurface(emptyOutput.submitToSurface))

        val delayedPicture = videoOutputBufferDisposition(size = 128, flags = 0, surfaceAvailable = true)
        assertTrue(delayedPicture.renderableFrame)
        assertTrue(delayedPicture.submitToSurface)
        pending.onOutputDequeued(delayedPicture.renderableFrame)
        assertEquals(0, pending.pendingOutputCount)
        assertTrue(firstFrame.onOutputSubmittedToSurface(delayedPicture.submitToSurface))
        assertFalse(firstFrame.onOutputSubmittedToSurface(delayedPicture.submitToSurface))
    }

    @Test fun invalidSurfaceDoesNotSubmitOrClaimFirstFrameButOutputIsDequeued() {
        val pending = VideoPendingOutputState()
        val firstFrame = VideoFirstFrameSubmissionState()
        pending.onInputQueued(0L)

        val picture = videoOutputBufferDisposition(size = 128, flags = 0, surfaceAvailable = false)
        assertTrue(picture.renderableFrame)
        assertFalse(picture.submitToSurface)
        pending.onOutputDequeued(picture.renderableFrame)
        assertEquals(0, pending.pendingOutputCount)
        assertFalse(firstFrame.onOutputSubmittedToSurface(picture.submitToSurface))

        val eos = videoOutputBufferDisposition(
            size = 0,
            flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            surfaceAvailable = false,
        )
        assertTrue(eos.endOfStream)
        assertFalse(eos.renderableFrame)
        pending.onOutputDequeued(eos.renderableFrame)
        pending.reset()
        assertEquals(0, pending.pendingOutputCount)
        assertFalse(firstFrame.onOutputSubmittedToSurface(eos.submitToSurface))
    }

    @Test fun outputFormatChangeDoesNotDrainPendingPictureWait() {
        val pending = VideoPendingOutputState()
        pending.onInputQueued(0L)
        var formats = 0
        var outputs = 0
        var dequeues = 0

        drainVideoCodecOutput(
            running = { true },
            dequeue = {
                if (dequeues++ == 0) MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                else MediaCodec.INFO_TRY_AGAIN_LATER
            },
            onFormatChanged = { formats++ },
            onOutputBuffer = { outputs++; true },
            firstTimeoutUs = pending.nextDequeueTimeoutUs(0L),
        )

        assertEquals(1, formats)
        assertEquals(0, outputs)
        assertEquals(1, pending.pendingOutputCount)
    }

    @Test fun idleDrainReleasesTheLastQueuedFrameWithoutAnotherInput() {
        val pending = VideoPendingOutputState()
        pending.onInputQueued(0L)
        var elapsedNs = 0L
        var outputAvailable = false
        var releasedOutputs = 0
        val waits = mutableListOf<Long>()
        val firstTimeoutUs = pending.nextDequeueTimeoutUs(elapsedNs)

        drainVideoCodecOutput(
            running = { true },
            dequeue = { timeoutUs ->
                waits += timeoutUs
                elapsedNs += timeoutUs * 1_000L
                if (!outputAvailable && elapsedNs >= 5_000_000L) {
                    outputAvailable = true
                    0
                } else {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                }
            },
            onFormatChanged = { fail("format change is not a pending frame output") },
            onOutputBuffer = {
                pending.onOutputDequeued(renderableFrame = true)
                releasedOutputs++
                true
            },
            firstTimeoutUs = firstTimeoutUs,
        )

        assertEquals(1, releasedOutputs)
        assertEquals(listOf(8_000L, 0L), waits)
        assertEquals(0, pending.pendingOutputCount)
    }

    @Test fun recentInputAndOutputKeepTheZeroCountDrainGraceShort() {
        val pending = VideoPendingOutputState(
            maxDequeueWaitUs = 8_000L,
            outputDrainGraceNs = 50_000_000L,
        )
        pending.onInputQueued(10L)
        pending.onOutputDequeued(renderableFrame = true, nowNs = 20L)

        assertEquals(0, pending.pendingOutputCount)
        assertEquals(8_000L, pending.nextDequeueTimeoutUs(1_000_020L))
        assertEquals(5_000L, pending.nextDequeueTimeoutUs(45_000_020L))
        assertEquals(0L, pending.nextDequeueTimeoutUs(50_000_020L))
    }

    @Test fun idleDrainKeepsCheckingForMultipleDelayedOutputs() {
        val pending = VideoPendingOutputState()
        pending.onInputQueued(0L)
        pending.onInputQueued(0L)
        val outputReadyAtNs = ArrayDeque(listOf(5_000_000L, 13_000_000L))
        var elapsedNs = 0L
        var releasedOutputs = 0

        repeat(2) {
            val firstTimeoutUs = pending.nextDequeueTimeoutUs(elapsedNs)
            drainVideoCodecOutput(
                running = { true },
                dequeue = { timeoutUs ->
                    elapsedNs += timeoutUs * 1_000L
                    if (outputReadyAtNs.firstOrNull()?.let { it <= elapsedNs } == true) {
                        outputReadyAtNs.removeFirst()
                        0
                    } else {
                        MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                },
                onFormatChanged = { fail("format change is not a pending frame output") },
                onOutputBuffer = {
                    pending.onOutputDequeued(renderableFrame = true)
                    releasedOutputs++
                    true
                },
                firstTimeoutUs = firstTimeoutUs,
            )
        }

        assertEquals(2, releasedOutputs)
        assertTrue(outputReadyAtNs.isEmpty())
        assertEquals(0, pending.pendingOutputCount)
    }

    @Test fun surfaceRecoveryRequiresAnEnabledStableSurfaceWithoutSubmittedOutput() {
        val disabled = VideoSurfaceRecoveryPolicy()
        disabled.onSurfaceChanged(surfaceAvailable = true, nowNs = 0L)
        assertFalse(disabled.shouldRequestKeyFrame(300_000_000L, true, true, true, true))

        val enabled = VideoSurfaceRecoveryPolicy(enabled = true)
        enabled.onSurfaceChanged(surfaceAvailable = true, nowNs = 0L)
        assertFalse(enabled.shouldRequestKeyFrame(249_999_999L, true, true, true, true))
        assertFalse(enabled.shouldRequestKeyFrame(250_000_000L, false, true, true, true))
        assertFalse(enabled.shouldRequestKeyFrame(250_000_000L, true, false, true, true))
        assertFalse(enabled.shouldRequestKeyFrame(250_000_000L, true, true, false, true))
        assertFalse(enabled.shouldRequestKeyFrame(250_000_000L, true, true, true, false))
        assertTrue(enabled.shouldRequestKeyFrame(250_000_000L, true, true, true, true))
        enabled.onKeyFrameRequested()
        assertFalse(enabled.shouldRequestKeyFrame(500_000_000L, true, true, true, true))

        enabled.onSurfaceChanged(surfaceAvailable = true, nowNs = 600_000_000L)
        enabled.onOutputSubmitted()
        assertFalse(enabled.shouldRequestKeyFrame(1_000_000_000L, true, true, true, true))
        enabled.onSurfaceChanged(surfaceAvailable = false, nowNs = 2_000_000_000L)
        assertFalse(enabled.shouldRequestKeyFrame(3_000_000_000L, true, true, true, true))
    }
}
