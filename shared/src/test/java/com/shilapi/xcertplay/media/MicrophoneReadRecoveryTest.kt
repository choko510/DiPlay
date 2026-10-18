package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneReadRecoveryTest {
    @Test
    fun repeatedZeroByteReadsStopAfterTheBoundedRetryWindow() {
        val recovery = MicrophoneReadRecovery()

        repeat(MicrophoneReadRecovery.MAX_CONSECUTIVE_ZERO_READS) {
            assertTrue(recovery.shouldRetryAfterZeroRead(isActive = true))
        }

        assertFalse(recovery.shouldRetryAfterZeroRead(isActive = true))
    }

    @Test
    fun aDataReadResetsTheConsecutiveZeroReadWindow() {
        val recovery = MicrophoneReadRecovery()

        repeat(MicrophoneReadRecovery.MAX_CONSECUTIVE_ZERO_READS - 1) {
            assertTrue(recovery.shouldRetryAfterZeroRead(isActive = true))
        }
        recovery.recordDataRead()

        repeat(MicrophoneReadRecovery.MAX_CONSECUTIVE_ZERO_READS) {
            assertTrue(recovery.shouldRetryAfterZeroRead(isActive = true))
        }
        assertFalse(recovery.shouldRetryAfterZeroRead(isActive = true))
    }

    @Test
    fun cancelledOrStaleOwnerDoesNotRetryAZeroRead() {
        val recovery = MicrophoneReadRecovery()

        assertFalse(recovery.shouldRetryAfterZeroRead(isActive = false))
        assertTrue(recovery.shouldRetryAfterZeroRead(isActive = true))
    }
}
