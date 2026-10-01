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
    fun repeatedTrackOperationFailuresUseBackoffIndependentOfTrackCreation() {
        val policy = TrackOperationRecoveryPolicy()
        var nowNs = 0L
        val delaysMs = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L)
        for (delayMs in delaysMs) {
            assertTrue(policy.shouldAttempt(nowNs))
            assertEquals(delayMs, policy.recordFailure(nowNs))
            assertFalse(policy.shouldAttempt(nowNs + delayMs * 1_000_000L - 1))
            nowNs += delayMs * 1_000_000L
            assertTrue(policy.shouldAttempt(nowNs))
        }
    }

    @Test
    fun stablePlaybackResetsOperationFailureBackoff() {
        val policy = TrackOperationRecoveryPolicy()
        assertEquals(250L, policy.recordFailure(0))

        policy.onPlaybackStarted(250_000_000L)
        policy.onPlaybackProgress(5_249_999_999L)
        assertEquals(500L, policy.recordFailure(5_249_999_999L))

        policy.onPlaybackStarted(5_749_999_999L)
        repeat(10) { index ->
            policy.onPlaybackProgress(5_749_999_999L + (index + 1) * 500_000_000L)
        }
        assertEquals(250L, policy.recordFailure(10_749_999_999L))
    }

    @Test
    fun operationFailureBackoffDoesNotResetWithoutRecentPlaybackProgress() {
        val policy = TrackOperationRecoveryPolicy()
        assertEquals(250L, policy.recordFailure(0))
        policy.onPlaybackStarted(250_000_000L)

        assertEquals(500L, policy.recordFailure(5_250_000_000L))
    }

    @Test
    fun pausedTimeDoesNotCountTowardStableOperationRecovery() {
        val policy = TrackOperationRecoveryPolicy()
        assertEquals(250L, policy.recordFailure(0))
        policy.onPlaybackStarted(250_000_000L)
        policy.onPlaybackProgress(750_000_000L)
        policy.onPlaybackPaused()

        policy.onPlaybackStarted(6_000_000_000L)
        policy.onPlaybackProgress(10_999_999_999L)
        assertEquals(500L, policy.recordFailure(10_999_999_999L))
    }

    @Test
    fun zeroWriteWatchdogUsesElapsedTimeAndResetsAfterProgress() {
        val policy = AudioTrackWriteStallPolicy()
        assertFalse(policy.onWriteResult(0, 1_000L))
        assertFalse(policy.onWriteResult(0, 1_000L + TRACK_WRITE_STALL_TIMEOUT_NS - 1))
        assertTrue(policy.onWriteResult(0, 1_000L + TRACK_WRITE_STALL_TIMEOUT_NS))

        assertFalse(policy.onWriteResult(1, 2_000_000_000L))
        assertFalse(policy.onWriteResult(0, 2_000_000_001L))
        assertFalse(policy.onWriteResult(-1, 2_000_000_002L))
        assertFalse(policy.onWriteResult(0, 2_500_000_001L))
    }

    @Test
    fun timestampStartupWaitsThenWarmupUsesFiveHundredMillisecondIntervals() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        assertFalse(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS - 1))
        assertTrue(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS))
        policy.recordQuery(TIMESTAMP_FIRST_QUERY_NS, timestampAvailable = false, framePosition = null)
        assertFalse(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS + TIMESTAMP_WARMUP_INTERVAL_NS - 1))
        assertTrue(policy.shouldQuery(TIMESTAMP_FIRST_QUERY_NS + TIMESTAMP_WARMUP_INTERVAL_NS))
        assertEquals(AudioTimestampPollMode.WARMUP, policy.mode)
        assertEquals(500L, policy.pollingIntervalMs)
    }

    @Test
    fun repeatedFalseQueriesEnterSparseProbeAndRecoverThroughWarmup() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        repeat(TIMESTAMP_UNAVAILABLE_FALSE_QUERIES) { index ->
            val queryNs = TIMESTAMP_FIRST_QUERY_NS + index * TIMESTAMP_WARMUP_INTERVAL_NS
            assertTrue(policy.shouldQuery(queryNs))
            val update = policy.recordQuery(queryNs, timestampAvailable = false, framePosition = null)
            if (index == TIMESTAMP_UNAVAILABLE_FALSE_QUERIES - 1) assertTrue(update.enteredProbe)
        }
        assertEquals(AudioTimestampPollMode.PROBE, policy.mode)
        val lastQueryNs = TIMESTAMP_FIRST_QUERY_NS +
            (TIMESTAMP_UNAVAILABLE_FALSE_QUERIES - 1) * TIMESTAMP_WARMUP_INTERVAL_NS
        assertFalse(policy.shouldQuery(lastQueryNs + TIMESTAMP_PROBE_INTERVAL_NS - 1))
        assertTrue(policy.shouldQuery(lastQueryNs + TIMESTAMP_PROBE_INTERVAL_NS))

        val recoveryNs = lastQueryNs + TIMESTAMP_PROBE_INTERVAL_NS
        val recovery = policy.recordQuery(recoveryNs, timestampAvailable = true, framePosition = 1_000)
        assertTrue(recovery.recoveredFromProbe)
        assertTrue(recovery.acceptAnchor)
        assertEquals(AudioTimestampPollMode.WARMUP, policy.mode)
        assertEquals(500L, policy.pollingIntervalMs)
        assertFalse(policy.shouldQuery(recoveryNs + TIMESTAMP_WARMUP_INTERVAL_NS - 1))
        assertTrue(policy.shouldQuery(recoveryNs + TIMESTAMP_WARMUP_INTERVAL_NS))
        policy.recordQuery(recoveryNs + TIMESTAMP_WARMUP_INTERVAL_NS, true, 2_000)
        val stable = policy.recordQuery(recoveryNs + 2 * TIMESTAMP_WARMUP_INTERVAL_NS, true, 3_000)
        assertTrue(stable.becameStable)
        assertEquals(AudioTimestampPollMode.STABLE, policy.mode)
        assertEquals(10_000L, policy.pollingIntervalMs)
    }

    @Test
    fun unavailableProbeStaysSparseUntilTheFramePositionAdvances() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        var nowNs = TIMESTAMP_FIRST_QUERY_NS
        repeat(TIMESTAMP_MAX_WARMUP_QUERIES) {
            policy.recordQuery(nowNs, timestampAvailable = true, framePosition = 1_000)
            nowNs += TIMESTAMP_WARMUP_INTERVAL_NS
        }
        assertEquals(AudioTimestampPollMode.PROBE, policy.mode)
        assertFalse(policy.shouldQuery(nowNs - TIMESTAMP_WARMUP_INTERVAL_NS + TIMESTAMP_PROBE_INTERVAL_NS - 1))
        val probeNs = nowNs - TIMESTAMP_WARMUP_INTERVAL_NS + TIMESTAMP_PROBE_INTERVAL_NS
        assertTrue(policy.shouldQuery(probeNs))
        val staleProbe = policy.recordQuery(probeNs, timestampAvailable = true, framePosition = 1_000)
        assertFalse(staleProbe.recoveredFromProbe)
        assertFalse(staleProbe.acceptAnchor)
        assertEquals(AudioTimestampPollMode.PROBE, policy.mode)
        assertFalse(policy.shouldQuery(probeNs + TIMESTAMP_PROBE_INTERVAL_NS - 1))
        assertTrue(policy.shouldQuery(probeNs + TIMESTAMP_PROBE_INTERVAL_NS))
        val recovered = policy.recordQuery(
            probeNs + TIMESTAMP_PROBE_INTERVAL_NS,
            timestampAvailable = true,
            framePosition = 1_001,
        )
        assertTrue(recovered.recoveredFromProbe)
        assertTrue(recovered.acceptAnchor)
        assertEquals(AudioTimestampPollMode.WARMUP, policy.mode)
    }

    @Test
    fun onlyAdvancingTimestampFramesReachStableMode() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        policy.recordQuery(TIMESTAMP_FIRST_QUERY_NS, true, 1_000)
        policy.recordQuery(TIMESTAMP_FIRST_QUERY_NS + TIMESTAMP_WARMUP_INTERVAL_NS, true, 2_000)
        val stable = policy.recordQuery(
            TIMESTAMP_FIRST_QUERY_NS + 2 * TIMESTAMP_WARMUP_INTERVAL_NS,
            true,
            3_000,
        )
        assertTrue(stable.becameStable)

        val stale = AudioTimestampPollPolicy()
        stale.reset(0)
        stale.recordQuery(TIMESTAMP_FIRST_QUERY_NS, true, 1_000)
        repeat(3) { index ->
            val update = stale.recordQuery(
                TIMESTAMP_FIRST_QUERY_NS + (index + 1) * TIMESTAMP_WARMUP_INTERVAL_NS,
                true,
                1_000,
            )
            assertFalse(update.becameStable)
        }
        assertEquals(AudioTimestampPollMode.WARMUP, stale.mode)
    }

    @Test
    fun resetAfterPauseRestartsTimestampStartupAndClearsProbeState() {
        val policy = AudioTimestampPollPolicy()
        policy.reset(0)
        repeat(TIMESTAMP_UNAVAILABLE_FALSE_QUERIES) { index ->
            policy.recordQuery(
                TIMESTAMP_FIRST_QUERY_NS + index * TIMESTAMP_WARMUP_INTERVAL_NS,
                timestampAvailable = false,
                framePosition = null,
            )
        }
        assertEquals(AudioTimestampPollMode.PROBE, policy.mode)
        policy.reset(20_000_000_000L)
        assertEquals(AudioTimestampPollMode.STARTUP, policy.mode)
        assertEquals(250L, policy.pollingIntervalMs)
        assertTrue(policy.shouldQuery(20_250_000_000L))
    }
}
