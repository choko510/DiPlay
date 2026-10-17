package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** PR-00: lock in fork diagnostics/retry semantics before integrating upstream USB code. */
class Step4NcmBaselineTest {
    @Test fun preReadyNotReadyRetriesOnlyNeighborDiscovery() {
        val waits = mutableListOf<Long>()
        val timeouts = mutableListOf<Int>()
        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true,
            linkReady = false,
            isActive = { true },
            sendOnce = { timeout ->
                timeouts += timeout
                if (timeouts.size < 3) NcmSendResult.NotReady else NcmSendResult.Sent
            },
            pause = waits::add,
        )
        assertSame(NcmSendResult.Sent, result)
        assertEquals(listOf(100, 100, 100), timeouts)
        assertEquals(listOf(50L, 100L), waits)
    }

    @Test fun preReadyNonNdpHasNoDuplicateRetries() {
        var calls = 0
        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = false, linkReady = false,
            isActive = { true }, sendOnce = { timeout ->
                calls++
                assertEquals(100, timeout)
                NcmSendResult.NotReady
            }, pause = { throw AssertionError("non-NDP backoff") },
        )
        assertSame(NcmSendResult.NotReady, result)
        assertEquals(1, calls)
    }

    @Test fun linkReadyUsesCallerNormalTimeoutAndNoRetries() {
        var calls = 0
        NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true, linkReady = true,
            isActive = { true }, sendOnce = { timeout ->
                calls++
                assertEquals(2_000, timeout)
                NcmSendResult.Sent
            }, pause = { throw AssertionError("ready link must not pause") },
        )
        assertEquals(1, calls)
    }
}
