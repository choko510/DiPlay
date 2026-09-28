package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
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
        state.onTimeout()

        assertSame(request, state.requestOrCreate { error("request must persist") })
        assertFalse(state.needsQueue())
        assertTrue(state.beginClose().cancelQueuedRequest)
    }

    @Test
    fun completionAllowsTheSameRequestToBeRequeued() {
        val state = PersistentUsbReadRequestState<Any>()
        val request = Any()
        state.requestOrCreate { request }
        state.markQueued()

        assertTrue(state.onCompletion(request))
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
        assertFalse(state.beginClose().cancelQueuedRequest)
        assertSame(request, state.takeForClose())
        assertTrue(state.takeForClose() == null)
    }

    @Test
    fun failedQueueCannotBeQueuedAgain() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueueFailure()

        try {
            state.needsQueue()
            throw AssertionError("failed request was reusable")
        } catch (_: IllegalStateException) {
            // Queue failure is terminal for this transport session.
        }
    }

    @Test
    fun unexpectedCompletionFailsAndLeavesTheQueuedRequestForCloseCancellation() {
        val state = PersistentUsbReadRequestState<Any>()
        state.requestOrCreate { Any() }
        state.markQueued()

        assertFalse(state.onCompletion(Any()))
        assertTrue(state.beginClose().cancelQueuedRequest)
    }
}
