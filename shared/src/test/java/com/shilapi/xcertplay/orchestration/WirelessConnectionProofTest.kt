package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessConnectionProofTest {
    @Test fun authenticationWithoutVideoDoesNotConfirm() {
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, Any())
        proof.authenticated(1)
        assertEquals(0, saves)
    }

    @Test fun tunnelAuthenticationBeforeAirPlaySessionIsRetainedForItsFirstFrame() {
        var saves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.tunnelAuthenticated(1)
        proof.activate(1, session)
        proof.rendered(1, session)

        assertEquals(1, saves)
    }

    @Test fun tunnelEndClearsAuthenticationQueuedForAnAirPlaySessionThatHasNotStarted() {
        var saves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.tunnelAuthenticated(1)
        proof.tunnelEnded(1)
        proof.activate(1, session)
        proof.rendered(1, session, nowNanos = 100)

        assertEquals(0, saves)
    }

    @Test fun bothEventsAreRequiredInEitherOrderAndOnlySaveOnce() {
        for (videoFirst in listOf(true, false)) {
            var saves = 0
            val session = Any()
            val proof = WirelessConnectionProof<Any>()
            proof.begin(1) { saves++ }
            proof.activate(1, session)
            if (videoFirst) proof.rendered(1, session) else proof.authenticated(1)
            assertEquals(0, saves)
            if (videoFirst) proof.authenticated(1) else proof.rendered(1, session)
            proof.rendered(1, session)
            proof.authenticated(1)
            assertEquals(1, saves)
        }
    }

    @Test fun oldGenerationAndOldSessionCannotConfirmANewConnection() {
        var saves = 0
        val old = Any()
        val current = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, old)
        proof.authenticated(1)
        proof.begin(2) { saves++ }
        proof.activate(2, current)
        proof.rendered(1, old)
        proof.authenticated(1)
        proof.rendered(2, old)
        proof.authenticated(2)
        assertEquals(0, saves)
        proof.rendered(2, current)
        assertEquals(1, saves)
    }

    @Test fun renderedFrameMustBeRecentForTheCurrentGenerationAndSession() {
        val first = Any()
        val replacement = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) {}
        proof.activate(1, first)

        assertFalse(proof.hasRecentRenderedFrame(1, nowNanos = 100, maxAgeNanos = 10))
        proof.rendered(1, first, nowNanos = 100)
        assertTrue(proof.hasRecentRenderedFrame(1, nowNanos = 110, maxAgeNanos = 10))
        assertFalse(proof.hasRecentRenderedFrame(1, nowNanos = 111, maxAgeNanos = 10))

        proof.rendered(0, first, nowNanos = 200)
        assertFalse(proof.hasRecentRenderedFrame(2, nowNanos = 200, maxAgeNanos = 10))
        proof.activate(1, replacement)
        assertFalse(proof.hasRecentRenderedFrame(1, nowNanos = 200, maxAgeNanos = 10))
        proof.rendered(1, first, nowNanos = 200)
        assertFalse(proof.hasRecentRenderedFrame(1, nowNanos = 200, maxAgeNanos = 10))
    }

    @Test fun endedOrClearedSessionCannotBeLearnedByLateCallbacks() {
        val session = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.rendered(1, session)
        proof.end(1, session)
        proof.authenticated(1)
        proof.rendered(1, session)
        assertFalse(proof.hasRecentRenderedFrame(1, System.nanoTime(), Long.MAX_VALUE))
        proof.clear()
        proof.activate(1, session)
        proof.authenticated(1)
        proof.rendered(1, session)
        assertFalse(proof.hasRecentRenderedFrame(1, System.nanoTime(), Long.MAX_VALUE))
        assertEquals(0, saves)
    }

    @Test fun replacementSessionNeedsItsOwnAuthenticationAndFrame() {
        val first = Any()
        val second = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, first)
        proof.authenticated(1)
        proof.activate(1, second)
        assertFalse(proof.hasRecentRenderedFrame(1, System.nanoTime(), Long.MAX_VALUE))
        proof.rendered(1, second)
        assertEquals(0, saves)
        proof.authenticated(1)
        assertEquals(1, saves)
    }
}
