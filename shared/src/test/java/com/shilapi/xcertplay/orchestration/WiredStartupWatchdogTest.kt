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
    fun startupRecoveryRetriesOnceUntilAirPlayOrManualReconnectResetsThePolicy() {
        val policy = WiredStartupRetryPolicy()

        assertTrue(policy.claimAutomaticRetry())
        assertFalse(policy.claimAutomaticRetry())
        policy.reset()
        assertTrue(policy.claimAutomaticRetry())
        policy.reset()
        assertTrue(policy.claimAutomaticRetry())
    }
}
