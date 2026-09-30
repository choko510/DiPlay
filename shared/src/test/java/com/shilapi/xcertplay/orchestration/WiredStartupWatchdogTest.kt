package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredStartupWatchdogTest {
    @Test
    fun startSessionArmsOnceAndExpiresOnce() {
        val watchdog = WiredStartupWatchdog()
        val first = watchdog.arm(attempt = 4)!!

        assertNull(watchdog.arm(attempt = 4))
        assertEquals(4, watchdog.expire(first))
        assertNull(watchdog.expire(first))
        assertTrue(watchdog.hasExpired(4))
    }

    @Test
    fun airPlayActivationCancelsAnArmedOrJustExpiredWatchdog() {
        val watchdog = WiredStartupWatchdog()
        val armed = watchdog.arm(attempt = 7)!!
        watchdog.cancel(attempt = 7)

        assertNull(watchdog.expire(armed))
        assertFalse(watchdog.hasExpired(7))

        val expired = watchdog.arm(attempt = 8)!!
        assertNotNull(watchdog.expire(expired))
        watchdog.cancel(attempt = 8)
        assertFalse(watchdog.hasExpired(8))
    }

    @Test
    fun nextAttemptGetsANewDeadlineToken() {
        val watchdog = WiredStartupWatchdog()
        val first = watchdog.arm(attempt = 1)!!
        watchdog.clear(attempt = 1)
        val second = watchdog.arm(attempt = 2)!!

        assertNull(watchdog.expire(first))
        assertEquals(2, watchdog.expire(second))
    }

    @Test
    fun softSnapshotDoesNotExpireTheHardWatchdog() {
        val watchdog = WiredStartupWatchdog()
        val token = watchdog.arm(attempt = 12)!!

        assertEquals(8_000L, WiredStartupWatchdog.SOFT_TIMEOUT_MILLIS)
        assertEquals(15_000L, WiredStartupWatchdog.HARD_TIMEOUT_MILLIS)
        assertTrue(watchdog.markSlow(token))
        assertFalse(watchdog.markSlow(token))
        assertEquals(12, watchdog.expire(token))
    }

    @Test
    fun screenOpenBeforeHardDeadlineCancelsTheDelayedExpiry() {
        val watchdog = WiredStartupWatchdog()

        listOf(7_000L, 10_000L).forEachIndexed { index, elapsedMillis ->
            val attempt = index + 20
            val token = watchdog.arm(attempt)!!
            if (elapsedMillis >= WiredStartupWatchdog.SOFT_TIMEOUT_MILLIS) {
                assertTrue(watchdog.markSlow(token))
            }
            watchdog.cancel(attempt)
            assertNull(watchdog.expire(token))
        }
    }

    @Test
    fun oldSoftAndHardCallbacksCannotAffectTheNextAttempt() {
        val watchdog = WiredStartupWatchdog()
        val old = watchdog.arm(attempt = 31)!!
        watchdog.clear(attempt = 31)
        val current = watchdog.arm(attempt = 32)!!

        assertFalse(watchdog.markSlow(old))
        assertNull(watchdog.expire(old))
        assertTrue(watchdog.markSlow(current))
        assertEquals(32, watchdog.expire(current))
    }

    @Test
    fun startupRecoveryAllowsThreeShortCleanRetriesBeforeDeepRecoveryCandidate() {
        val policy = WiredStartupRetryPolicy()

        assertEquals(WiredRecoveryDecision.FastCleanRetry(400L), policy.onFailure(true, false, false))
        assertEquals(WiredRecoveryDecision.FastCleanRetry(800L), policy.onFailure(true, false, false))
        assertEquals(WiredRecoveryDecision.FastCleanRetry(1_200L), policy.onFailure(true, false, false))
        assertEquals(WiredRecoveryDecision.DeepRecoveryCandidate, policy.onFailure(true, false, false))
        policy.reset()
        assertEquals(WiredRecoveryDecision.FastCleanRetry(400L), policy.onFailure(true, false, false))
    }

    @Test
    fun userActionsAndDeviceDisappearanceDoNotEnterAutomaticRecovery() {
        val policy = WiredStartupRetryPolicy()

        assertEquals(
            WiredRecoveryDecision.FastCleanRetry(400L),
            policy.onFailure(devicePresent = true, closed = false, manualReconnect = false),
        )
        assertEquals(
            WiredRecoveryDecision.UserOrPhysicalDisconnect,
            policy.onFailure(devicePresent = false, closed = false, manualReconnect = false),
        )
        assertEquals(
            WiredRecoveryDecision.FastCleanRetry(400L),
            policy.onFailure(devicePresent = true, closed = false, manualReconnect = false),
        )
        assertEquals(
            WiredRecoveryDecision.Suppressed,
            policy.onFailure(devicePresent = true, closed = true, manualReconnect = false),
        )
        assertEquals(
            WiredRecoveryDecision.Suppressed,
            policy.onFailure(devicePresent = true, closed = false, manualReconnect = true),
        )
        assertEquals(
            WiredRecoveryDecision.FastCleanRetry(400L),
            policy.onFailure(devicePresent = true, closed = false, manualReconnect = false),
        )
    }

    @Test
    fun oldDataPathCallbackCannotPublishIntoANewerGeneration() {
        val generation = WiredDataPathGeneration()
        val oldAttempt = generation.begin()
        val newAttempt = generation.begin()

        assertFalse(generation.isCurrent(oldAttempt))
        assertTrue(generation.isCurrent(newAttempt))
        generation.invalidate()
        assertFalse(generation.isCurrent(newAttempt))
    }
}
