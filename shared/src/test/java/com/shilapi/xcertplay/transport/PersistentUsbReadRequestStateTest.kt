package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentUsbReadRequestStateTest {
    @Test
    fun timeoutKeepsTheSameRequestQueuedAndDoesNotRequestCancellation() {
        val state = PersistentUsbReadRequestState<Any>()
        val request = Any()

        assertSame(request, state.requestOrCreate { request })
        assertTrue(state.needsQueue())
        state.markQueued()
        assertEquals(PersistentUsbReadRequestState.State.QUEUED, state.state())
        state.onTimeout()

        assertSame(request, state.requestOrCreate { error("request must persist") })
        assertFalse(state.needsQueue())
        assertEquals(PersistentUsbReadRequestState.State.QUEUED, state.state())
        assertTrue(state.beginClose().cancelQueuedRequest)
    }

    @Test
    fun completionAllowsTheSameRequestToBeRequeued() {
        val state = PersistentUsbReadRequestState<Any>()
        val request = Any()
        state.requestOrCreate { request }
        state.markQueued()

        assertTrue(state.onCompletion(request))
        assertEquals(PersistentUsbReadRequestState.State.COMPLETED, state.state())
        assertTrue(state.needsQueue())
        assertSame(request, state.requestOrCreate { error("request must persist") })
        state.markQueued()
        assertSame(request, state.beginClose().request)
    }

    @Test
    fun closeDuringWaitRequestsOneCancelAndOwnsOneFinalClose() {
        val state = PersistentUsbReadRequestState<Any>()
        val request = Any()
        state.requestOrCreate { request }
        state.markQueued()

        val plan = state.beginClose()
        assertSame(request, plan.request)
        assertTrue(plan.cancelQueuedRequest)
        assertEquals(PersistentUsbReadRequestState.State.CLOSING, state.state())
        assertFalse(state.onNullResult())
        assertFalse(state.beginClose().cancelQueuedRequest)
        assertSame(request, state.takeForClose())
        assertEquals(PersistentUsbReadRequestState.State.CLOSED, state.state())
        assertTrue(state.takeForClose() == null)
    }

    @Test
    fun failedQueueCannotBeQueuedAgain() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueueFailure()
        assertEquals(PersistentUsbReadRequestState.State.FAILED, state.state())

        try {
            state.needsQueue()
            throw AssertionError("failed request was reusable")
        } catch (_: IllegalStateException) {
            // Queue failure is terminal for this transport session.
        }
    }

    @Test
    fun nullWaitResultFailsTheTransportInsteadOfKeepingTheRequestQueued() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertTrue(state.onNullResult())
        assertEquals(PersistentUsbReadRequestState.State.FAILED, state.state())
    }

    @Test
    fun nullWaitResultCancellationSurvivesTheFailureHandlerMarkingFailureAgain() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertTrue(state.onNullResult())
        state.markFailure()

        assertTrue(state.beginClose().cancelQueuedRequest)
    }

    @Test
    fun physicalDetachLikeUnexpectedRequestFailsTheTransport() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertFalse(state.onCompletion(Any()))
        assertEquals(PersistentUsbReadRequestState.State.FAILED, state.state())
    }

    @Test
    fun unexpectedCompletionFailsAndLeavesTheQueuedRequestForCloseCancellation() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertFalse(state.onCompletion(Any()))
        assertTrue(state.beginClose().cancelQueuedRequest)
    }

    @Test
    fun unexpectedCompletionCancellationSurvivesTheFailureHandlerMarkingFailureAgain() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertFalse(state.onCompletion(Any()))
        state.markFailure()

        assertTrue(state.beginClose().cancelQueuedRequest)
    }
}
