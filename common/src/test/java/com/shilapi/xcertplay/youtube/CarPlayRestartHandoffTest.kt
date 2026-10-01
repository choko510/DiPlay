package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayRestartHandoffTest {
    @Test
    fun destroyedOwnerCannotClaimRestartAndNewHostMustSettleItsLayout() {
        val handoff = CarPlayRestartHandoff()
        val oldHost = Any()
        val newHost = Any()
        val oldSize = DisplaySize(1400, 720)
        val newSize = DisplaySize(1056, 720)
        val token = requireNotNull(handoff.begin(oldHost, oldSize))

        handoff.releaseOwner(oldHost)
        handoff.completeTeardown(token, oldSize)

        assertNull(handoff.claim(oldHost))
        assertNull(handoff.claim(newHost))
        handoff.observeSize(newHost, newSize)
        assertNull(handoff.claim(newHost))
        handoff.markLayoutReady(newHost, newSize)

        assertEquals(newSize, handoff.claim(newHost))
        assertTrue(handoff.isClaimedBy(newHost))
        handoff.clear()
        assertFalse(handoff.hasPending())
    }

    @Test
    fun resizeDuringTeardownMustSettleBeforeRestartCanBeClaimed() {
        val handoff = CarPlayRestartHandoff()
        val host = Any()
        val initial = DisplaySize(1400, 720)
        val latest = DisplaySize(1056, 720)
        val token = requireNotNull(handoff.begin(host, initial))

        handoff.observeSize(host, latest)
        handoff.completeTeardown(token, latest)
        assertNull(handoff.claim(host))

        handoff.markLayoutReady(host, latest)
        assertEquals(latest, handoff.claim(host))
        handoff.clear()
        assertTrue(handoff.begin(host, latest) != null)
    }

    @Test
    fun staleTeardownCompletionCannotReleaseANewerRestart() {
        val handoff = CarPlayRestartHandoff()
        val firstOwner = Any()
        val secondOwner = Any()
        val size = DisplaySize(1400, 720)
        val staleToken = requireNotNull(handoff.begin(firstOwner, size))

        handoff.completeTeardown(staleToken, size)
        handoff.markLayoutReady(firstOwner, size)
        assertEquals(size, handoff.claim(firstOwner))
        handoff.clear()

        val currentToken = requireNotNull(handoff.begin(secondOwner, size))
        handoff.completeTeardown(staleToken, DisplaySize(1000, 720))
        handoff.markLayoutReady(secondOwner, size)
        assertNull(handoff.claim(secondOwner))

        handoff.completeTeardown(currentToken, size)
        assertEquals(size, handoff.claim(secondOwner))
    }

    @Test
    fun replacementActivityCanReclaimAfterBeingDestroyedBeforeControllerStart() {
        val handoff = CarPlayRestartHandoff()
        val oldHost = Any()
        val replacement = Any()
        val nextHost = Any()
        val size = DisplaySize(1400, 720)
        val token = requireNotNull(handoff.begin(oldHost, size))

        handoff.releaseOwner(oldHost)
        handoff.completeTeardown(token, size)
        handoff.markLayoutReady(replacement, size)
        assertEquals(size, handoff.claim(replacement))
        assertTrue(handoff.isClaimedBy(replacement))

        handoff.releaseOwner(replacement)
        assertFalse(handoff.isClaimedBy(replacement))
        handoff.markLayoutReady(nextHost, size)
        assertEquals(size, handoff.claim(nextHost))
    }
}
