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
        proof.submittedToSurface(1, session)

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
        proof.submittedToSurface(1, session, nowNanos = 100)

        assertEquals(0, saves)
    }

    @Test fun independentBluetoothAuthenticationConfirmsFallbackSessionExactlyOnce() {
        var saves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.submittedToSurface(1, session, nowNanos = 100)
        proof.requestHandoff(1, session)
        proof.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        assertEquals(0, saves)
        proof.authenticated(1)

        assertEquals(1, saves)
        proof.submittedToSurface(1, session, nowNanos = 102)
        proof.tunnelAuthenticated(1)
        proof.tunnelEnded(1)
        assertEquals(1, saves)
    }

    @Test fun independentBluetoothAuthenticationSurvivesTunnelLossBeforeTheFirstFrame() {
        var saves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.authenticated(1)
        proof.tunnelAuthenticated(1)
        proof.tunnelEnded(1)
        proof.activate(1, session)
        proof.submittedToSurface(1, session, nowNanos = 100)

        assertEquals(1, saves)
    }

    @Test fun bothEventsAreRequiredInEitherOrderAndOnlySaveOnce() {
        for (videoFirst in listOf(true, false)) {
            var saves = 0
            val session = Any()
            val proof = WirelessConnectionProof<Any>()
            proof.begin(1) { saves++ }
            proof.activate(1, session)
            if (videoFirst) proof.submittedToSurface(1, session) else proof.authenticated(1)
            assertEquals(0, saves)
            if (videoFirst) proof.authenticated(1) else proof.submittedToSurface(1, session)
            proof.submittedToSurface(1, session)
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
        proof.submittedToSurface(1, old)
        proof.authenticated(1)
        proof.submittedToSurface(2, old)
        proof.authenticated(2)
        assertEquals(0, saves)
        proof.submittedToSurface(2, current)
        assertEquals(1, saves)
    }

    @Test fun submittedFrameMustBeRecentForTheCurrentGenerationAndSession() {
        val first = Any()
        val replacement = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) {}
        proof.activate(1, first)

        assertFalse(proof.hasRecentSurfaceSubmission(1, nowNanos = 100, maxAgeNanos = 10))
        proof.submittedToSurface(1, first, nowNanos = 100)
        assertTrue(proof.hasRecentSurfaceSubmission(1, nowNanos = 110, maxAgeNanos = 10))
        assertFalse(proof.hasRecentSurfaceSubmission(1, nowNanos = 111, maxAgeNanos = 10))

        proof.submittedToSurface(0, first, nowNanos = 200)
        assertFalse(proof.hasRecentSurfaceSubmission(2, nowNanos = 200, maxAgeNanos = 10))
        proof.activate(1, replacement)
        assertFalse(proof.hasRecentSurfaceSubmission(1, nowNanos = 200, maxAgeNanos = 10))
        proof.submittedToSurface(1, first, nowNanos = 200)
        assertFalse(proof.hasRecentSurfaceSubmission(1, nowNanos = 200, maxAgeNanos = 10))
    }

    @Test fun endedOrClearedSessionCannotBeLearnedByLateCallbacks() {
        val session = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.submittedToSurface(1, session)
        proof.end(1, session)
        proof.authenticated(1)
        proof.submittedToSurface(1, session)
        assertFalse(proof.hasRecentSurfaceSubmission(1, System.nanoTime(), Long.MAX_VALUE))
        proof.clear()
        proof.activate(1, session)
        proof.authenticated(1)
        proof.submittedToSurface(1, session)
        assertFalse(proof.hasRecentSurfaceSubmission(1, System.nanoTime(), Long.MAX_VALUE))
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
        assertFalse(proof.hasRecentSurfaceSubmission(1, System.nanoTime(), Long.MAX_VALUE))
        proof.submittedToSurface(1, second)
        assertEquals(0, saves)
        proof.authenticated(1)
        assertEquals(1, saves)
    }
}
