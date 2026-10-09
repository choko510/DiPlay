package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NcmStartupNdpRetryTest {
    @Test
    fun startupNeighborDiscoveryRetriesWithBoundedBackoffUntilSent() {
        val timeouts = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()
        val outcomes = ArrayDeque(
            listOf(NcmSendResult.NotReady, NcmSendResult.NotReady, NcmSendResult.Sent),
        )

        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true,
            linkReady = false,
            isActive = { true },
            sendOnce = { timeout -> timeouts += timeout; outcomes.removeFirst() },
            pause = pauses::add,
        )

        assertSame(NcmSendResult.Sent, result)
        assertEquals(listOf(100, 100, 100), timeouts)
        assertEquals(listOf(50L, 100L), pauses)
    }

    @Test
    fun startupNotReadyIsBoundedToFiveAttemptsAndAtMostOnePointTwoFiveSeconds() {
        val timeouts = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()

        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true,
            linkReady = false,
            isActive = { true },
            sendOnce = { timeout -> timeouts += timeout; NcmSendResult.NotReady },
            pause = pauses::add,
        )

        assertSame(NcmSendResult.NotReady, result)
        assertEquals(5, timeouts.size)
        assertEquals(List(5) { NcmStartupNdpRetry.NOT_READY_TIMEOUT_MILLIS }, timeouts)
        assertEquals(listOf(50L, 100L, 200L, 400L), pauses)
        assertEquals(1_250L, timeouts.sumOf(Int::toLong) + pauses.sum())
    }

    @Test
    fun ordinaryNetworkFramesBeforeLinkReadyUseOneShortAttempt() {
        val timeouts = mutableListOf<Int>()

        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = false,
            linkReady = false,
            isActive = { true },
            sendOnce = { timeout -> timeouts += timeout; NcmSendResult.NotReady },
            pause = { error("ordinary network frames must not retry") },
        )

        assertSame(NcmSendResult.NotReady, result)
        assertEquals(listOf(NcmStartupNdpRetry.NOT_READY_TIMEOUT_MILLIS), timeouts)
    }

    @Test
    fun selectedPreReadyTimeoutAppliesToOrdinaryAndBoundedResolutionAttempts() {
        val ordinaryTimeouts = mutableListOf<Int>()
        NcmStartupNdpRetry.send(
            startupNeighborDiscovery = false,
            linkReady = false,
            preReadyOutTimeoutMillis = 2_000,
            isActive = { true },
            sendOnce = { timeout -> ordinaryTimeouts += timeout; NcmSendResult.NotReady },
            pause = { error("ordinary packets must not retry") },
        )

        val resolutionTimeouts = mutableListOf<Int>()
        NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true,
            linkReady = false,
            preReadyOutTimeoutMillis = 2_000,
            isActive = { true },
            sendOnce = { timeout -> resolutionTimeouts += timeout; NcmSendResult.NotReady },
            pause = {},
        )

        assertEquals(listOf(2_000), ordinaryTimeouts)
        assertEquals(List(5) { 2_000 }, resolutionTimeouts)
    }

    @Test
    fun linkReadyUsesNormalTimeoutWithoutRetrying() {
        val timeouts = mutableListOf<Int>()

        val result = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = false,
            linkReady = true,
            isActive = { true },
            sendOnce = { timeout -> timeouts += timeout; NcmSendResult.NotReady },
            pause = { error("ready link writes must not retry") },
        )

        assertSame(NcmSendResult.NotReady, result)
        assertEquals(listOf(NcmStartupNdpRetry.NORMAL_TIMEOUT_MILLIS), timeouts)
    }

    @Test
    fun failedWritesAndCloseCancelPendingStartupRetry() {
        var calls = 0
        var active = true
        val failed = NcmSendResult.Failed(IllegalStateException("transport failed"))

        assertSame(
            failed,
            NcmStartupNdpRetry.send(
                startupNeighborDiscovery = true,
                linkReady = false,
                isActive = { true },
                sendOnce = { calls++; failed },
                pause = { error("failed writes must not retry") },
            ),
        )
        assertEquals(1, calls)

        calls = 0
        val cancelled = NcmStartupNdpRetry.send(
            startupNeighborDiscovery = true,
            linkReady = false,
            isActive = { active },
            sendOnce = { calls++; NcmSendResult.NotReady },
            pause = { active = false },
        )
        assertSame(NcmSendResult.NotReady, cancelled)
        assertEquals(1, calls)
    }
}
