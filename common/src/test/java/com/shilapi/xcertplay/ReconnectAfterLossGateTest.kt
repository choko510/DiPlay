package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectAfterLossGateTest {
    @Test
    fun recoveredSessionCancelsThePendingLossTicket() {
        val gate = ReconnectAfterLossGate()
        val ticket = gate.schedule(
            restartGeneration = 4,
            connectionEpoch = 12,
            ownerEpoch = 2,
            controllerManaged = false,
        )
        assertNotNull(ticket)

        assertEquals(ticket, gate.recovered(restartGeneration = 4, connectionEpoch = 12, ownerEpoch = 2))
        assertFalse(
            gate.shouldRestart(
                ticket = requireNotNull(ticket),
                currentRestartGeneration = 4,
                currentConnectionEpoch = 12,
                currentOwnerEpoch = 2,
                isOwner = true,
                hasActiveSession = false,
                blockers = ReconnectAfterLossBlockers(),
            ),
        )
    }

    @Test
    fun secondLossAfterRecoveryGetsANewTicketAndCanRestartOnce() {
        val gate = ReconnectAfterLossGate()
        val firstLoss = requireNotNull(
            gate.schedule(restartGeneration = 7, connectionEpoch = 20, ownerEpoch = 3, controllerManaged = false),
        )
        assertEquals(firstLoss, gate.recovered(restartGeneration = 7, connectionEpoch = 20, ownerEpoch = 3))
        val secondLoss = requireNotNull(
            gate.schedule(restartGeneration = 7, connectionEpoch = 20, ownerEpoch = 3, controllerManaged = false),
        )

        assertTrue(secondLoss.lossEpoch > firstLoss.lossEpoch)
        assertFalse(
            gate.shouldRestart(firstLoss, 7, 20, 3, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()),
        )
        assertTrue(
            gate.shouldRestart(secondLoss, 7, 20, 3, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()),
        )
        assertFalse(
            gate.shouldRestart(secondLoss, 7, 20, 3, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()),
        )
    }

    @Test
    fun controllerManagedFailureAndDuplicateLossDoNotScheduleHostRetries() {
        val gate = ReconnectAfterLossGate()

        assertNull(gate.schedule(1, 2, 3, controllerManaged = true))
        val first = requireNotNull(gate.schedule(1, 2, 3, controllerManaged = false))
        assertNull(gate.schedule(1, 2, 3, controllerManaged = false))
        assertTrue(gate.cancel(first))
        assertFalse(gate.shouldRestart(first, 1, 2, 3, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()))
    }

    @Test
    fun changedConnectionOrOwnerEpochReplacesAStalePendingTicket() {
        val gate = ReconnectAfterLossGate()
        val stale = requireNotNull(gate.schedule(8, 11, 4, controllerManaged = false))
        val current = requireNotNull(gate.schedule(8, 12, 5, controllerManaged = false))

        assertFalse(gate.shouldRestart(stale, 8, 11, 4, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()))
        assertTrue(gate.shouldRestart(current, 8, 12, 5, isOwner = true, hasActiveSession = false, blockers = ReconnectAfterLossBlockers()))
    }

    @Test
    fun generationOwnerActiveSessionAndLifecycleBlocksFenceTheDelayedRetry() {
        data class Scenario(
            val restartGeneration: Int,
            val connectionEpoch: Long,
            val ownerEpoch: Long,
            val isOwner: Boolean,
            val hasActiveSession: Boolean,
            val blockers: ReconnectAfterLossBlockers,
        )
        val cases = listOf(
            Scenario(8, 11, 4, false, false, ReconnectAfterLossBlockers()),
            Scenario(9, 11, 4, true, false, ReconnectAfterLossBlockers()),
            Scenario(8, 12, 4, true, false, ReconnectAfterLossBlockers()),
            Scenario(8, 11, 5, true, false, ReconnectAfterLossBlockers()),
            Scenario(8, 11, 4, true, true, ReconnectAfterLossBlockers()),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(shuttingDown = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(finishing = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(destroyed = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(menuOpen = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(menuOpen = true, pendingDisplayResize = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(handshakeResetInProgress = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(pendingDisplayResize = true)),
            Scenario(8, 11, 4, true, false, ReconnectAfterLossBlockers(controllerManaged = true)),
        )

        for (scenario in cases) {
            val gate = ReconnectAfterLossGate()
            val ticket = requireNotNull(gate.schedule(8, 11, 4, controllerManaged = false))
            assertFalse(
                gate.shouldRestart(
                    ticket = ticket,
                    currentRestartGeneration = scenario.restartGeneration,
                    currentConnectionEpoch = scenario.connectionEpoch,
                    currentOwnerEpoch = scenario.ownerEpoch,
                    isOwner = scenario.isOwner,
                    hasActiveSession = scenario.hasActiveSession,
                    blockers = scenario.blockers,
                ),
            )
        }

        val blocked = ReconnectAfterLossGate()
        val ticket = requireNotNull(blocked.schedule(8, 11, 4, controllerManaged = false))
        assertFalse(
            blocked.shouldRestart(
                ticket,
                8,
                11,
                4,
                isOwner = true,
                hasActiveSession = false,
                blockers = ReconnectAfterLossBlockers(controllerManaged = true),
            ),
        )
    }
}
