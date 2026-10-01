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

        val transition = state.tunnelAuthenticated(1)

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
        state.rendered(1, defaultSession, nowNanos = 100)
        assertTrue(
            state.hasOperationalCarPlayControl(1, nowNanos = 100, maxFrameAgeNanos = 10),
        )
        assertFalse(ready.closeBluetoothBootstrap)

        val transition = state.requestHandoff(1, defaultSession)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, transition.kind)
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, transition.mode)
        assertTrue(transition.closeBluetoothBootstrap)
        assertTrue(transition.reportActive)
    }

    @Test
    fun bootstrapEofDuringHandoffWaitsForTunnelButCannotEnterFallback() {
        val state = state()
        val session = Any()
        state.activate(1, session)
        state.requestHandoff(1, session)
        state.rendered(1, session, nowNanos = 100)

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

        val transition = state.tunnelAuthenticated(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, transition.kind)
        assertEquals(WirelessControlMode.TUNNEL_CONTROL, transition.mode)
        assertFalse(transition.closeBluetoothBootstrap)
    }

    @Test
    fun fallbackRequiresRecentVideoAndAnOpenBluetoothControlPath() {
        val session = Any()
        val state = state(session)
        state.requestHandoff(1, session)
        state.rendered(1, session, nowNanos = 100)

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
        state.rendered(1, session, nowNanos = 100)
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
        state.rendered(1, session, nowNanos = 100)

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
        state.rendered(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val ended = state.tunnelEnded(1)

        assertEquals(WirelessControlTransitionKind.TUNNEL_ENDED, ended.kind)
        assertEquals(WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL, state.mode(1))
        assertTrue(state.hasOperationalCarPlayControl(1, nowNanos = 102, maxFrameAgeNanos = 10))
    }

    @Test
    fun activeTunnelLossFailsAndCannotRemainTunnelControl() {
        val state = state()
        state.requestHandoff(1, defaultSession)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, state.tunnelAuthenticated(1).kind)

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

        val handoffWinsFirst = state()
        handoffWinsFirst.tunnelAuthenticated(1)
        assertEquals(
            WirelessControlTransitionKind.TUNNEL_ACTIVE,
            handoffWinsFirst.requestHandoff(1, defaultSession).kind,
        )
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
        staleFrame.rendered(1, session, nowNanos = 100)
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
        state.rendered(1, session, nowNanos = 100)
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
        activeFallback.rendered(1, recent, nowNanos = 100)
        activeFallback.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            activeFallback.fallbackVideoTimedOut(1, nowNanos = 110, maxFrameAgeNanos = 10).kind,
        )

        val stalled = Any()
        val stalledFallback = state(stalled)
        stalledFallback.requestHandoff(1, stalled)
        stalledFallback.rendered(1, stalled, nowNanos = 100)
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
        tunneled.rendered(1, handedOff, nowNanos = 100)
        tunneled.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)
        tunneled.tunnelAuthenticated(1)
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
        state.rendered(1, session, nowNanos = 100)
        state.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10)

        val transition = state.end(1, session)

        assertEquals(WirelessControlTransitionKind.FAILED, transition.kind)
        assertEquals(WirelessControlMode.FAILED, transition.mode)
        assertFalse(state.hasRecentRenderedFrame(1, 102, 10))
    }

    @Test
    fun oldAirPlaySessionEndCannotFailItsReplacementSession() {
        val old = Any()
        val current = Any()
        val state = state(old)
        state.requestHandoff(1, old)
        state.rendered(1, old, nowNanos = 100)
        state.activate(1, current)
        state.rendered(1, current, nowNanos = 101)
        state.handoffTimedOut(1, nowNanos = 102, maxFrameAgeNanos = 10)

        assertEquals(WirelessControlTransitionKind.IGNORED, state.end(1, old).kind)
        assertEquals(
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL,
            state.mode(1),
        )
        assertTrue(state.hasRecentRenderedFrame(1, nowNanos = 103, maxAgeNanos = 10))
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
        tunnelFirst.rendered(1, session, nowNanos = 100)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, tunnelFirst.tunnelAuthenticated(1).kind)
        assertEquals(
            WirelessControlTransitionKind.IGNORED,
            tunnelFirst.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10).kind,
        )

        val watchdogFirst = state(session)
        watchdogFirst.requestHandoff(1, session)
        watchdogFirst.rendered(1, session, nowNanos = 100)
        assertEquals(
            WirelessControlTransitionKind.BOOTSTRAP_CONTROL_ACTIVE,
            watchdogFirst.handoffTimedOut(1, nowNanos = 101, maxFrameAgeNanos = 10).kind,
        )
        val lateTunnel = watchdogFirst.tunnelAuthenticated(1)
        assertEquals(WirelessControlTransitionKind.TUNNEL_ACTIVE, lateTunnel.kind)
        assertTrue(lateTunnel.closeBluetoothBootstrap)
        assertFalse(lateTunnel.reportActive)
    }

    @Test
    fun activeTunnelKeepsHandoffAliveAfterBluetoothBootstrapCloses() {
        val state = state(defaultSession)
        state.requestHandoff(1, defaultSession)
        state.tunnelAuthenticated(1)

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
