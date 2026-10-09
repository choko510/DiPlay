package com.shilapi.xcertplay.youtube

import com.shilapi.xcertplay.airplay.ViewAreaCommandWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicViewAreaCoordinatorTest {
    @Test fun commandWriteDoesNotCommitUntilTargetGeometryIsObserved() {
        val coordinator = DynamicViewAreaCoordinator()
        val session = Any()
        coordinator.attachSession(session, 2)
        val token = (coordinator.request(1, nowElapsedMs = 100, timeoutMs = 1_000) as ViewAreaRequestResult.Outbound).token

        assertEquals(DynamicViewAreaMode.SWITCHING, coordinator.snapshot().mode)
        assertEquals(0, coordinator.snapshot().committedIndex)
        assertEquals(ViewAreaWriteDisposition.WRITTEN,
            coordinator.onWriteResult(token, ViewAreaCommandWriteResult.WRITTEN, nowElapsedMs = 150))
        assertEquals(0, coordinator.snapshot().committedIndex)
        assertTrue(coordinator.snapshot().commandWriteSucceeded)
        assertFalse(coordinator.confirmObserved(token, observedIndex = 1, geometryChangedSinceRequest = false))
        assertTrue(coordinator.confirmObserved(token, observedIndex = 1, geometryChangedSinceRequest = true))
        assertEquals(DynamicViewAreaMode.READY, coordinator.snapshot().mode)
        assertEquals(1, coordinator.snapshot().committedIndex)
    }

    @Test fun latestRequestSupersedesOldTokenAndOldTimeoutCannotChangeIt() {
        val coordinator = DynamicViewAreaCoordinator()
        val session = Any()
        coordinator.attachSession(session, 2)
        val first = (coordinator.request(1, 100, 1_000) as ViewAreaRequestResult.Outbound).token
        val latest = (coordinator.request(0, 200, 1_000) as ViewAreaRequestResult.Outbound).token

        assertEquals(ViewAreaWriteDisposition.STALE,
            coordinator.onWriteResult(first, ViewAreaCommandWriteResult.WRITTEN, 250))
        assertFalse(coordinator.timeout(first, 2_000))
        assertTrue(coordinator.isCurrent(latest))
        assertEquals(0, coordinator.snapshot().requestedIndex)
        assertEquals(0, coordinator.snapshot().committedIndex)
    }

    @Test fun notReadyGetsOneBoundedRetryThenSessionFallsBackWithoutReconnect() {
        val coordinator = DynamicViewAreaCoordinator()
        coordinator.attachSession(Any(), 2)
        val token = (coordinator.request(1, 100, 1_000) as ViewAreaRequestResult.Outbound).token

        assertEquals(ViewAreaWriteDisposition.RETRY_ONCE,
            coordinator.onWriteResult(token, ViewAreaCommandWriteResult.EVENT_CHANNEL_NOT_READY, 200))
        assertEquals(1, coordinator.snapshot().retryCount)
        assertEquals(ViewAreaWriteDisposition.FALLBACK,
            coordinator.onWriteResult(token, ViewAreaCommandWriteResult.EVENT_CHANNEL_NOT_READY, 300))
        val failed = coordinator.snapshot()
        assertEquals(DynamicViewAreaMode.LOCAL_FALLBACK, failed.mode)
        assertTrue(failed.failedForSession)
        assertEquals(0, failed.requestedIndex)
        assertEquals("event_channel_not_ready", failed.failureReason)
        assertEquals("dynamic_disabled_for_session",
            (coordinator.request(1, 400, 1_000) as ViewAreaRequestResult.Rejected).reason)
    }

    @Test fun timeoutAndSessionReplacementInvalidatePendingCallbacks() {
        val coordinator = DynamicViewAreaCoordinator()
        val firstSession = Any()
        coordinator.attachSession(firstSession, 2)
        val old = (coordinator.request(1, 100, 100) as ViewAreaRequestResult.Outbound).token
        assertFalse(coordinator.timeout(old, 199))
        assertTrue(coordinator.timeout(old, 200))
        assertEquals("transition_timeout", coordinator.snapshot().failureReason)

        val secondSession = Any()
        coordinator.attachSession(secondSession, 2)
        val current = (coordinator.request(1, 300, 100) as ViewAreaRequestResult.Outbound).token
        coordinator.attachSession(Any(), 2)
        assertFalse(coordinator.confirmObserved(current, 1, geometryChangedSinceRequest = true))
        assertFalse(coordinator.snapshot().failedForSession)
        assertEquals(DynamicViewAreaMode.READY, coordinator.snapshot().mode)
    }

    @Test fun undeclaredAreaAndUnacceptablePhoneRequestsAreRejected() {
        val coordinator = DynamicViewAreaCoordinator()
        val session = Any()
        coordinator.attachSession(session, 1)
        assertEquals("dynamic_areas_not_declared",
            (coordinator.request(1, 0, 1_000) as ViewAreaRequestResult.Rejected).reason)

        coordinator.attachSession(session, 2)
        assertEquals("host_layout_cannot_apply_request",
            (coordinator.requestFromPhone(session, 1, false, 0, 1_000) as ViewAreaRequestResult.Rejected).reason)
        val phoneRequest = coordinator.requestFromPhone(session, 1, true, 10, 1_000)
        assertTrue(phoneRequest is ViewAreaRequestResult.PhoneInitiated)
        val token = (phoneRequest as ViewAreaRequestResult.PhoneInitiated).token
        assertEquals(ViewAreaWriteDisposition.NOT_APPLICABLE,
            coordinator.onWriteResult(token, ViewAreaCommandWriteResult.WRITTEN, 20))
        assertTrue(coordinator.confirmObserved(token, 1, geometryChangedSinceRequest = true))
        assertNull(coordinator.snapshot().pendingToken)
    }
}
