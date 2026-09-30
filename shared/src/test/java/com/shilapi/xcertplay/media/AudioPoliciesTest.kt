package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPoliciesTest {
    @Test
    fun trackCreationRetriesWithBoundedBackoffAndNewFormatsStartImmediately() {
        val policy = TrackCreationRetryPolicy<String>()
        assertEquals(TrackCreationAttempt(true, 1, 0), policy.beginAttempt("stereo", 0))
        assertEquals(250L, policy.recordFailure("stereo", 0))
        assertEquals(TrackCreationAttempt(false, 2, 150), policy.beginAttempt("stereo", 100_000_000L))
        assertEquals(TrackCreationAttempt(true, 2, 0), policy.beginAttempt("stereo", 250_000_000L))
        assertEquals(500L, policy.recordFailure("stereo", 250_000_000L))
        assertEquals(TrackCreationAttempt(false, 3, 450), policy.beginAttempt("stereo", 300_000_000L))
        assertEquals(TrackCreationAttempt(true, 3, 0), policy.beginAttempt("stereo", 750_000_000L))
        assertEquals(3, policy.recordSuccess("stereo"))
        assertEquals(TrackCreationAttempt(true, 1, 0), policy.beginAttempt("stereo", 800_000_000L))
        assertEquals(TrackCreationAttempt(true, 1, 0), policy.beginAttempt("mono", 800_000_000L))
    }

    @Test
    fun activeSameFormatTrackIsReusedAndNewFormatRecreatesImmediately() {
        assertFalse(shouldCreateTrack(hasTrack = true, activeFormat = "stereo", requestedFormat = "stereo"))
        assertTrue(shouldCreateTrack(hasTrack = false, activeFormat = "stereo", requestedFormat = "stereo"))
        assertTrue(shouldCreateTrack(hasTrack = true, activeFormat = "stereo", requestedFormat = "mono"))
    }

    @Test
    fun repeatedTrackFailuresCapAtFiveSeconds() {
        val policy = TrackCreationRetryPolicy<String>()
        var nowNs = 0L
        var delayMs = 0L
        repeat(8) {
            val attempt = policy.beginAttempt("output", nowNs)
            assertTrue(attempt.allowed)
            delayMs = policy.recordFailure("output", nowNs)
            nowNs += delayMs * 1_000_000L
        }
        assertEquals(TRACK_RETRY_MAX_MS, delayMs)
        assertTrue(delayMs <= TRACK_RETRY_MAX_MS)
    }

    @Test
    fun timestampQueriesWarmUpThenBackOffToTenSeconds() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        assertFalse(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS - 1))
        assertTrue(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS))
        policy.recordQuery(TIMESTAMP_FIRST_QUERY_NS, timestampAvailable = false)
        assertFalse(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS + TIMESTAMP_WARMUP_INTERVAL_NS - 1))
        assertTrue(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS + TIMESTAMP_WARMUP_INTERVAL_NS))
        policy.recordQuery(750_000_000L, timestampAvailable = true)
        policy.recordQuery(1_250_000_000L, timestampAvailable = true)
        policy.recordQuery(1_750_000_000L, timestampAvailable = true)
        assertEquals(10_000L, policy.pollingIntervalMs)
        assertFalse(policy.shouldQuery(11_749_999_999L))
        assertTrue(policy.shouldQuery(11_750_000_000L))
        policy.reset(20_000_000_000L)
        assertEquals(500L, policy.pollingIntervalMs)
        assertTrue(policy.shouldQuery(20_250_000_000L))
    }
}
