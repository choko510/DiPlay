package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHandoffTest {
    private val defaultSession = Any()

    @Test
    fun ordinaryBootstrapStartsInBootstrapControlMode() {
        val state = state()

        assertEquals(WirelessControlMode.BOOTSTRAP, state.mode(1))
    }

    @Test
    fun handoffRequestWaitsForAnAuthenticatedTunnel() {
        val state = state()

        val transition = state.requestHandoff(1, defaultSession)

        assertEquals(WirelessControlTransitionKind.HANDOFF_REQUESTED, transition.kind)
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, transition.mode)
        assertFalse(transition.closeBluetoothBootstrap)
        assertFalse(transition.reportActive)
    }

    @Test
    fun tunnelReadyAfterHandoffClosesBootstrapAndReportsActiveOnce() {
        val state = state()
        state.requestHandoff(1, defaultSession)

        val takeover = state.tunnelAuthenticated(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, takeover.kind)
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, state.mode(1))
        val transition = state.commitTunnelHandoff(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, transition.kind)
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, transition.mode)
        assertTrue(transition.closeBluetoothBootstrap)
        assertTrue(transition.reportActive)
        assertEquals(
            WirelessControlTransitionKind.BOOTSTRAP_ENDED,
            state.bootstrapEnded(1).kind,
        )
    }

    @Test
    fun tunnelReadyBeforeHandoffWaitsAndThenCompletesWithoutLosingControl() {
        val state = state()
        val ready = state.tunnelAuthenticated(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_READY, ready.kind)
        assertFalse(
            state.hasOperationalCarPlayControl(1, nowNanos = 100, maxFrameAgeNanos = 10),
        )
        state.submittedToSurface(1, defaultSession, nowNanos = 100)
        assertTrue(
            state.hasOperationalCarPlayControl(1, nowNanos = 100, maxFrameAgeNanos = 10),
        )
        assertFalse(ready.closeBluetoothBootstrap)

        val transition = state.requestHandoff(1, defaultSession)

        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, transition.kind)
        assertEquals(WirelessControlMode.TUNNEL_READY, state.mode(1))
        assertFalse(transition.closeBluetoothBootstrap)
        val committed = state.commitTunnelHandoff(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, committed.kind)
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, committed.mode)
        assertTrue(committed.closeBluetoothBootstrap)
        assertTrue(committed.reportActive)
    }

    @Test
    fun bootstrapEofDuringHandoffWaitsForTunnelButCannotEnterFallback() {
        val state = state()
        val session = Any()
        state.activate(1, session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)

        assertEquals(
            WirelessControlTransitionKind.WAITING_FOR_TUNNEL,
            state.bootstrapEnded(1).kind,
        )
        val timedOut = state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        assertEquals(WirelessControlTransitionKind.FAILED, timedOut.kind)
        assertEquals(WirelessControlMode.FAILED, timedOut.mode)
    }

    @Test
    fun bootstrapEofDuringHandoffCanStillCompleteWhenTunnelArrivesInTime() {
        val state = state()
        state.requestHandoff(1, defaultSession)
        state.bootstrapEnded(1)

        val takeover = state.tunnelAuthenticated(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, takeover.kind)
        val transition = state.commitTunnelHandoff(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, transition.kind)
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, transition.mode)
        assertFalse(transition.closeBluetoothBootstrap)
    }

    @Test
    fun fallbackRequiresRecentVideoAndAnOpenBluetoothControlPath() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)

        val transition = state.handoffTimedOut(1, nowNanos = 110, maxFrameAgeNanos = 10)

        assertEquals(WirelessControlTransitionKind.BOOTSTRAP_CONTROL_ACTIVE, transition.kind)
        assertEquals(
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL,
            transition.mode,
        )
        assertFalse(transition.closeBluetoothBootstrap)
        assertTrue(transition.reportActive)
    }

    @Test
    fun recentAirPlayFrameAndLiveBootstrapEstablishTheOperationalControlProof() {
        val session = Any()
        val state = state(session)
        assertFalse(
            state.hasOperationalCarPlayControl(1, nowNanos = 100, maxFrameAgeNanos = 10),
        )
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        assertTrue(
            state.hasOperationalCarPlayControl(1, nowNanos = 101, maxFrameAgeNanos = 10),
        )
        assertFalse(
            state.hasOperationalCarPlayControl(1, nowNanos = 111, maxFrameAgeNanos = 10),
        )
    }

    @Test
    fun airPlayFrameWithoutAnyLiveControlPathDoesNotEstablishOperationalProof() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.bootstrapEnded(1)
        state.submittedToSurface(1, session, nowNanos = 100)

        assertFalse(
            state.hasOperationalCarPlayControl(1, nowNanos = 101, maxFrameAgeNanos = 10),
        )
    }

    @Test
    fun tunnelReadyLossReturnsToBootstrapBeforeLaterHandoff() {
        val state = state()
        assertEquals(WirelessControlTransitionKind.TUNNEL_READY, state.tunnelAuthenticated(1).kind)

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ENDED, ended.kind)
        assertEquals(WirelessControlMode.BOOTSTRAP, state.mode(1))
        val handoff = state.requestHandoff(1, defaultSession)
        assertEquals(WirelessControlTransitionKind.HANDOFF_REQUESTED, handoff.kind)
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, state.mode(1))
        assertFalse(handoff.closeBluetoothBootstrap)
    }

    @Test
    fun tunnelLossAfterBootstrapEofFailsInsteadOfLeavingZombieReadyState() {
        val state = state()
        state.tunnelAuthenticated(1)
        assertEquals(
            WirelessControlTransitionKind.WAITING_FOR_TUNNEL,
            state.bootstrapEnded(1).kind,
        )

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.FAILED, ended.kind)
        assertEquals(WirelessControlMode.FAILED, state.mode(1))
    }

    @Test
    fun optionalTunnelLossPreservesLiveFallbackControl() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ENDED, ended.kind)
        assertEquals(WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL, state.mode(1))
        assertTrue(state.hasOperationalCarPlayControl(1, nowNanos = 102, maxFrameAgeNanos = 10))
    }

    @Test
    fun tunnelLossBeforeTakeoverCommitKeepsBluetoothOwnedFallback() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val candidate = state.tunnelAuthenticated(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, candidate.kind)
        assertEquals(WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL, state.mode(1))

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ENDED, ended.kind)
        assertEquals(WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL, state.mode(1))
        assertEquals(WirelessControlTransitionKind.IGNORED, state.commitTunnelHandoff(1).kind)
        assertTrue(state.hasOperationalCarPlayControl(1, nowNanos = 102, maxFrameAgeNanos = 10))
    }

    @Test
    fun tunnelLossBeforePendingHandoffCommitLeavesBluetoothAvailable() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)

        val candidate = state.tunnelAuthenticated(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, candidate.kind)
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, state.mode(1))
        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ENDED, ended.kind)
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, state.mode(1))
        assertFalse(ended.closeBluetoothBootstrap)
        assertEquals(WirelessControlTransitionKind.IGNORED, state.commitTunnelHandoff(1).kind)
        assertEquals(
            WirelessControlTransitionKind.BOOTSTRAP_CONTROL_ACTIVE,
            state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10).kind,
        )
    }

    @Test
    fun activeTunnelLossFailsAndCannotRemainTunnelControl() {
        val state = state()
        state.requestHandoff(1, defaultSession)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED,
            state.tunnelAuthenticated(1).kind,
        )
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, state.commitTunnelHandoff(1).kind)

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.FAILED, ended.kind)
        assertEquals(WirelessControlMode.FAILED, state.mode(1))
        assertFalse(state.hasOperationalCarPlayControl(1, nowNanos = 100, maxFrameAgeNanos = 10))
    }

    @Test
    fun tunnelEndAndHandoffRaceNeverHandsOffToADeadTunnel() {
        val tunnelEndsFirst = state()
        tunnelEndsFirst.tunnelAuthenticated(1)
        tunnelEndsFirst.tunnelEnded(1)
        assertEquals(
            WirelessControlTransitionKind.HANDOFF_REQUESTED,
            tunnelEndsFirst.requestHandoff(1, defaultSession).kind,
        )
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, tunnelEndsFirst.mode(1))

        val handoffCandidateFirst = state()
        handoffCandidateFirst.tunnelAuthenticated(1)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED,
            handoffCandidateFirst.requestHandoff(1, defaultSession).kind,
        )
        assertEquals(WirelessControlMode.TUNNEL_READY, handoffCandidateFirst.mode(1))
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_ENDED,
            handoffCandidateFirst.tunnelEnded(1).kind,
        )
        assertEquals(WirelessControlMode.BOOTSTRAP, handoffCandidateFirst.mode(1))
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            handoffCandidateFirst.commitTunnelHandoff(1).kind,
        )
        assertEquals(
            WirelessControlTransitionKind.HANDOFF_REQUESTED,
            handoffCandidateFirst.requestHandoff(1, defaultSession).kind,
        )
        assertEquals(WirelessControlMode.HANDOFF_REQUESTED, handoffCandidateFirst.mode(1))

        val handoffWinsFirst = state()
        handoffWinsFirst.tunnelAuthenticated(1)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED,
            handoffWinsFirst.requestHandoff(1, defaultSession).kind,
        )
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, handoffWinsFirst.commitTunnelHandoff(1).kind)
        assertEquals(WirelessControlTransitionKind.FAILED, handoffWinsFirst.tunnelEnded(1).kind)
        assertEquals(WirelessControlMode.FAILED, handoffWinsFirst.mode(1))
    }

    @Test
    fun oldGenerationTunnelEndCannotChangeNewLiveTunnel() {
        val state = state()
        state.begin(2) {}
        state.activate(2, defaultSession)
        state.tunnelAuthenticated(2)

        assertEquals(WirelessControlTransitionKind.IGNORED, state.tunnelEnded(1).kind)
        assertEquals(WirelessControlMode.TUNNEL_READY, state.mode(2))
    }

    @Test
    fun watchdogDoesNotPreserveAConnectionWithoutRecentVideo() {
        val session = Any()
        val noFrame = state(session)
        noFrame.requestHandoff(1, session)
        assertEquals(
            WirelessControlTransitionKind.FAILED,
            noFrame.handoffTimedOut(1, nowNanos = 100, maxFrameAgeNanos = 10).kind,
        )

        val staleFrame = state(session)
        staleFrame.requestHandoff(1, session)
        staleFrame.submittedToSurface(1, session, nowNanos = 100)
        assertEquals(
            WirelessControlTransitionKind.FAILED,
            staleFrame.handoffTimedOut(1, nowNanos = 111, maxFrameAgeNanos = 10).kind,
        )
    }

    @Test
    fun fallbackBluetoothEofIsAConnectionFailure() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val transition = state.bootstrapEnded(1)

        assertEquals(WirelessControlTransitionKind.FAILED, transition.kind)
        assertEquals(WirelessControlMode.FAILED, transition.mode)
        assertTrue(transition.failureReason.orEmpty().contains("Bluetooth control closed"))
    }

    @Test
    fun fallbackVideoStallFailsButARecentFrameAndTunnelTakeoverDoNot() {
        val recent = Any()
        val activeFallback = state(recent)
        activeFallback.requestHandoff(1, recent)
        activeFallback.submittedToSurface(1, recent, nowNanos = 100)
        activeFallback.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            activeFallback.fallbackVideoTimedOut(1, nowNanos = 110, maxFrameAgeNanos = 10).kind,
        )

        val stalled = Any()
        val stalledFallback = state(stalled)
        stalledFallback.requestHandoff(1, stalled)
        stalledFallback.submittedToSurface(1, stalled, nowNanos = 100)
        stalledFallback.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        val failure = stalledFallback.fallbackVideoTimedOut(
            1,
            nowNanos = 111,
            maxFrameAgeNanos = 10,
        )
        assertEquals(WirelessControlTransitionKind.FAILED, failure.kind)
        assertTrue(failure.failureReason.orEmpty().contains("video stopped rendering"))

        val handedOff = Any()
        val tunneled = state(handedOff)
        tunneled.requestHandoff(1, handedOff)
        tunneled.submittedToSurface(1, handedOff, nowNanos = 100)
        tunneled.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED,
            tunneled.tunnelAuthenticated(1).kind,
        )
        tunneled.commitTunnelHandoff(1)
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            tunneled.fallbackVideoTimedOut(1, nowNanos = 111, maxFrameAgeNanos = 10).kind,
        )
    }

    @Test
    fun airPlaySessionEndInFallbackFailsAndClearsItsFrameProof() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.submittedToSurface(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val transition = state.end(1, session)

        assertEquals(WirelessControlTransitionKind.FAILED, transition.kind)
        assertEquals(WirelessControlMode.FAILED, transition.mode)
        assertFalse(state.hasRecentSurfaceSubmission(1, 102, 10))
    }

    @Test
    fun oldAirPlaySessionEndCannotFailItsReplacementSession() {
        val old = Any()
        val current = Any()
        val state = state(old)
        state.requestHandoff(1, old)
        state.submittedToSurface(1, old, nowNanos = 100)
        state.activate(1, current)
        state.submittedToSurface(1, current, nowNanos = 101)
        state.handoffTimedOut(1, nowNanos = 102, maxFrameAgeNanos = 10)

        assertEquals(WirelessControlTransitionKind.IGNORED, state.end(1, old).kind)
        assertEquals(
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL,
            state.mode(1),
        )
        assertTrue(state.hasRecentSurfaceSubmission(1, nowNanos = 103, maxAgeNanos = 10))
    }

    @Test
    fun staleGenerationCallbacksCannotChangeTheCurrentMode() {
        val state = state()
        state.begin(2) {}
        val before = state.mode(2)

        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            state.tunnelAuthenticated(1).kind,
        )
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            state.handoffTimedOut(1, nowNanos = 100, maxFrameAgeNanos = 10).kind,
        )
        assertEquals(before, state.mode(2))
    }

    @Test
    fun handoffCommandFromAnOldAirPlaySessionCannotChangeCurrentControlState() {
        val old = Any()
        val current = Any()
        val state = state(old)
        state.activate(1, current)

        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            state.requestHandoff(1, old).kind,
        )
        assertEquals(WirelessControlMode.BOOTSTRAP, state.mode(1))
    }

    @Test
    fun tunnelReadyAndWatchdogAreSerializedIntoOnlyOneValidTransition() {
        val session = Any()
        val tunnelFirst = state(session)
        tunnelFirst.requestHandoff(1, session)
        tunnelFirst.submittedToSurface(1, session, nowNanos = 100)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED,
            tunnelFirst.tunnelAuthenticated(1).kind,
        )
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, tunnelFirst.commitTunnelHandoff(1).kind)
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            tunnelFirst.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10).kind,
        )

        val watchdogFirst = state(session)
        watchdogFirst.requestHandoff(1, session)
        watchdogFirst.submittedToSurface(1, session, nowNanos = 100)
        assertEquals(
            WirelessControlTransitionKind.BOOTSTRAP_CONTROL_ACTIVE,
            watchdogFirst.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10).kind,
        )
        val lateTunnel = watchdogFirst.tunnelAuthenticated(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_TAKEOVER_REQUESTED, lateTunnel.kind)
        val takeover = watchdogFirst.commitTunnelHandoff(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, takeover.kind)
        assertTrue(takeover.closeBluetoothBootstrap)
        assertFalse(takeover.reportActive)
    }

    @Test
    fun activeTunnelKeepsHandoffAliveAfterBluetoothBootstrapCloses() {
        val state = state(defaultSession)
        state.requestHandoff(1, defaultSession)
        state.tunnelAuthenticated(1)
        state.commitTunnelHandoff(1)

        assertEquals(
            WirelessControlTransitionKind.BOOTSTRAP_ENDED,
            state.bootstrapEnded(1).kind,
        )
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, state.mode(1))
    }

    @Test
    fun ordinaryBootstrapLossStillFailsWithoutHandoffState() {
        val state = state()

        val transition = state.bootstrapEnded(1)

        assertEquals(WirelessControlTransitionKind.FAILED, transition.kind)
        assertEquals(WirelessControlMode.FAILED, transition.mode)
    }

    private fun state(session: Any = defaultSession): WirelessConnectionProof<Any> =
        WirelessConnectionProof<Any>().apply {
            begin(1) {}
            activate(1, session)
        }
}
