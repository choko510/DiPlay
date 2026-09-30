package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AacStartupFallbackTest {
    @Test
    fun fallbackRequiresTimeAndEnoughUnitsAndCanStartOnlyOnce() {
        val policy = AacDecoderFallbackPolicy(minimumAccessUnits = 8, waitNanos = 2_000)
        policy.reset(100)
        policy.onAccessUnitsSubmitted(8)
        assertFalse(policy.shouldFallback(2_099))
        assertTrue(policy.shouldFallback(2_100))
        assertTrue(policy.beginFallback())
        assertFalse(policy.beginFallback())
    }

    @Test
    fun elapsedTimeAloneDoesNotTriggerFallbackWithoutEnoughUnits() {
        val policy = AacDecoderFallbackPolicy(minimumAccessUnits = 8, waitNanos = 2_000)
        policy.reset(100)
        policy.onAccessUnitsSubmitted(7)
        assertFalse(policy.shouldFallback(10_000))
    }

    @Test
    fun outputObservedDisablesFallbackAndNewRawGenerationResetsState() {
        val policy = AacDecoderFallbackPolicy(minimumAccessUnits = 8, waitNanos = 2_000)
        policy.reset(0)
        policy.onAccessUnitsSubmitted(8)
        policy.onDecoderOutput()
        assertFalse(policy.shouldFallback(3_000))
        assertFalse(policy.beginFallback())

        policy.reset(10_000)
        assertEquals(0, policy.submittedAccessUnits)
        assertFalse(policy.shouldFallback(10_000 + AAC_STARTUP_WAIT_NS))
    }

    @Test
    fun startupCacheReplaysUnitsInPresentationOrderAndClearsAccounting() {
        val cache = AacStartupReplayCache(maxAccessUnits = 3, maxBytes = 32, maxDurationUs = 5_000)
        val units = listOf(
            PendingAacAu(byteArrayOf(3, 4), 2_000, 20),
            PendingAacAu(byteArrayOf(1, 2), 1_000, 10),
            PendingAacAu(byteArrayOf(5, 6), 3_000, 30),
        )
        units.forEach { assertTrue(cache.offer(it)) }
        assertEquals(3, cache.size)
        assertEquals(6, cache.byteCount)
        val replay = cache.drain()
        assertEquals(listOf(1_000L, 2_000L, 3_000L), replay.map { it.presentationTimeUs })
        assertEquals(listOf(10L, 20L, 30L), replay.map { it.sourceSample })
        assertArrayEquals(byteArrayOf(1, 2), replay[0].bytes)
        assertEquals(0, cache.size)
        assertEquals(0, cache.byteCount)
    }

    @Test
    fun startupCacheRejectsAuCountByteAndDurationOverflow() {
        val countCache = AacStartupReplayCache(maxAccessUnits = 1, maxBytes = 8, maxDurationUs = 10_000)
        assertTrue(countCache.offer(PendingAacAu(byteArrayOf(1), 0, 0)))
        assertFalse(countCache.offer(PendingAacAu(byteArrayOf(2), 1, 1)))

        val byteCache = AacStartupReplayCache(maxAccessUnits = 4, maxBytes = 2, maxDurationUs = 10_000)
        assertTrue(byteCache.offer(PendingAacAu(byteArrayOf(1, 2), 0, 0)))
        assertFalse(byteCache.offer(PendingAacAu(byteArrayOf(3), 1, 1)))

        val durationCache = AacStartupReplayCache(maxAccessUnits = 4, maxBytes = 8, maxDurationUs = 100)
        assertTrue(durationCache.offer(PendingAacAu(byteArrayOf(1), 0, 0)))
        assertFalse(durationCache.offer(PendingAacAu(byteArrayOf(2), 101, 1)))
    }

    @Test
    fun replayCapacityReservesRoomForCurrentLiveAu() {
        val cache = AacStartupReplayCache(
            maxAccessUnits = AAC_STARTUP_MAX_AUS,
            maxBytes = AAC_STARTUP_MAX_BYTES - AAC_STARTUP_LIVE_AU_RESERVE_BYTES,
        )
        repeat(AAC_STARTUP_MAX_AUS - 1) { index ->
            assertTrue(cache.offer(PendingAacAu(ByteArray(5_254), index * 20_000L, index.toLong())))
        }
        val current = PendingAacAu(ByteArray(16 * 1024), 1_980_000L, 100)
        assertFalse(cache.offer(current))
        val replay = cache.drainIncluding(current)
        assertTrue(replay.any { it === current })
        assertTrue(replay.size <= AAC_STARTUP_MAX_AUS)
        assertTrue(replay.sumOf { it.bytes.size } <= AAC_STARTUP_MAX_BYTES)
    }

    @Test
    fun replayDurationLimitKeepsTheLiveAuAndDropsOlderCachedUnits() {
        val cache = AacStartupReplayCache(maxAccessUnits = 4, maxBytes = 8, maxDurationUs = 100)
        assertTrue(cache.offer(PendingAacAu(byteArrayOf(1), 0, 0)))
        assertTrue(cache.offer(PendingAacAu(byteArrayOf(2), 50, 1)))
        val current = PendingAacAu(byteArrayOf(3), 1_000, 2)

        val replay = cache.drainIncluding(current)

        assertEquals(listOf(current), replay)
        assertTrue(replay.last().presentationTimeUs - replay.first().presentationTimeUs <= 100)
    }
}
