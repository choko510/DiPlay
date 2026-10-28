package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VideoStatsTest {
    @Test
    fun reportsBoundedWorkerAndFrameLatencyMetricsWithoutPayloadData() {
        var nowNs = 0L
        val stats = VideoStats(streamType = 110, decoderOwnerId = 7L, nanoTime = { nowNs }, log = {})
        stats.startRuntimeTracking(
            threadCpuTimeNs = 100L,
            processAllocatedBytes = 1_000L,
            globalGcCount = 5L,
        )
        stats.onQueuePoll(waitNs = 1_000_000L, timed = true, jobFound = false)
        stats.onQueueDepth(4)
        stats.onOutputDequeue(waitNs = 8_000_000L, waited = true)
        stats.onPendingOutputCount(3)
        stats.onPendingOutputTimeout()
        stats.onKeyFrameRequestAttempt()
        stats.onCodecInputQueued(presentationTimeUs = 11L, receivedNs = 1_000_000_000L)
        stats.onSubmittedToSurface(presentationTimeUs = 11L, submittedNs = 1_010_000_000L)
        stats.onRecovery()
        nowNs = 5_000_000_000L

        val line = stats.logIfDue(
            threadCpuTimeNs = 2_000_000_100L,
            processAllocatedBytes = 2_000L,
            globalGcCount = 6L,
            processPssKb = 100L,
        )

        assertNotNull(line)
        assertTrue(line!!.contains("type=110 owner=7"))
        assertTrue(line.contains("queueWait=1.00ms queuePeak=4"))
        assertTrue(line.contains("outputWaitCalls=0.2/s outputWait=8.00ms"))
        assertTrue(line.contains("pendingPeak=3 pendingTimeouts=1"))
        assertTrue(line.contains("receivedToCodecSurfaceReleaseMs=10.00/10.00/10.00 n=1"))
        assertTrue(line.contains("latencyUnmatched=0/1 latencyOverwrites=0/1"))
        assertTrue(line.contains("keyframeAttempts=1"))
        assertTrue(line.contains("threadCpu=400.00ms/s"))
        assertTrue(line.contains("processAllocBytes=200/s processAllocObjects=NOT_AVAILABLE"))
        assertTrue(line.contains("threadAllocBytes=NOT_AVAILABLE"))
        assertTrue(line.contains("globalGc=12.00/min pssKb=100 fdCount=NOT_SAMPLED"))
        assertFalse(line.contains("payload"))
    }

    @Test
    fun reportsUnmatchedAndOverwrittenCorrelationFractions() {
        var nowNs = 0L
        val stats = VideoStats(streamType = 110, decoderOwnerId = 7L, nanoTime = { nowNs }, log = {})
        stats.onReceived(1)
        repeat(129) { index -> stats.onCodecInputQueued(index.toLong(), index.toLong()) }
        stats.onSubmittedToSurface(presentationTimeUs = Long.MAX_VALUE, submittedNs = 5_000_000_000L)
        nowNs = 5_000_000_000L

        val line = stats.logIfDue()

        assertNotNull(line)
        assertTrue(line!!.contains("receivedToCodecSurfaceReleaseMs=-1.00/-1.00/-1.00 n=0"))
        assertTrue(line.contains("latencyUnmatched=1/1 latencyOverwrites=1/129"))
    }

    @Test
    fun slowLoggerDoesNotBlockFrameArrival() {
        val nowNs = AtomicLong()
        val logEntered = CountDownLatch(1)
        val releaseLog = CountDownLatch(1)
        val arrivalCompleted = CountDownLatch(1)
        val stats = VideoStats(
            streamType = 110,
            decoderOwnerId = 7L,
            nanoTime = nowNs::get,
            log = {
                logEntered.countDown()
                check(releaseLog.await(5, TimeUnit.SECONDS))
            },
        )
        stats.startRuntimeTracking(threadCpuTimeNs = 100L, processAllocatedBytes = 1_000L, globalGcCount = 5L)
        stats.onReceived(100)
        nowNs.set(5_000_000_000L)
        val logger = Thread({ stats.logIfDue() }, "video-stats-test-logger").apply { isDaemon = true }
        val producer = Thread({
            stats.onReceived(200)
            arrivalCompleted.countDown()
        }, "video-stats-test-producer").apply { isDaemon = true }

        try {
            logger.start()
            assertTrue(logEntered.await(5, TimeUnit.SECONDS))
            producer.start()

            assertTrue("frame arrival waited for diagnostic logging", arrivalCompleted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseLog.countDown()
            logger.join(1_000)
            producer.join(1_000)
        }
    }
}
