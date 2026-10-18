package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayReconnectRetryStateTest {
    @Test
    fun recoveryBeforeTimeoutInvalidatesThePendingRetry() {
        val state = CarPlayReconnectRetryState()
        val owner = Any()
        val controller = Any()
        val failure = state.schedule(owner, controller, 3, 9, controllerManagedRecovery = false)
        assertNotNull(failure)

        assertNotNull(state.sessionActive(Any()))
        assertFalse(state.consume(failure!!, owner, controller, 3, 9))
    }

    @Test
    fun recoveryThenSecondLossCreatesExactlyOneNewRetry() {
        val state = CarPlayReconnectRetryState()
        val owner = Any()
        val controller = Any()
        val firstSession = Any()
        val secondSession = Any()
        val firstLoss = state.schedule(owner, controller, 3, 9, controllerManagedRecovery = false)!!
        assertNull(state.schedule(owner, controller, 3, 9, controllerManagedRecovery = false))

        state.sessionActive(firstSession)
        assertFalse(state.consume(firstLoss, owner, controller, 3, 9))
        assertTrue(state.sessionEnded(firstSession))
        val secondLoss = state.schedule(owner, controller, 3, 9, controllerManagedRecovery = false)!!

        assertFalse(state.consume(firstLoss, owner, controller, 3, 9))
        assertTrue(state.consume(secondLoss, owner, controller, 3, 9))
        assertFalse(state.consume(secondLoss, owner, controller, 3, 9))
        state.sessionActive(secondSession)
        assertFalse(state.consume(secondLoss, owner, controller, 3, 9))
    }

    @Test
    fun controllerManagedRecoveryNeverSchedulesAHostRetry() {
        val state = CarPlayReconnectRetryState()

        assertNull(state.schedule(Any(), Any(), 1, 1, controllerManagedRecovery = true))
    }

    @Test
    fun manualCancellationAndOldOwnerGenerationMakeCallbackANoop() {
        val state = CarPlayReconnectRetryState()
        val oldOwner = Any()
        val newOwner = Any()
        val controller = Any()
        val cancelled = state.schedule(oldOwner, controller, 4, 11, controllerManagedRecovery = false)!!
        state.cancelPending()
        assertFalse(state.consume(cancelled, oldOwner, controller, 4, 11))

        val transferred = state.schedule(oldOwner, controller, 4, 12, controllerManagedRecovery = false)!!
        assertFalse(state.consume(transferred, newOwner, controller, 4, 12))
        assertFalse(state.consume(transferred, oldOwner, controller, 4, 13))

        val ownerEpochChanged = state.schedule(oldOwner, controller, 4, 14, controllerManagedRecovery = false)!!
        assertFalse(state.consume(ownerEpochChanged, oldOwner, controller, 4, 15))
    }

    @Test
    fun staleSessionEndCannotScheduleOverANewerActiveSession() {
        val state = CarPlayReconnectRetryState()
        val newSession = Any()
        state.sessionActive(newSession)

        assertFalse(state.sessionEnded(Any()))
        assertNull(state.schedule(Any(), Any(), 2, 7, controllerManagedRecovery = false))
    }

    @Test
    fun liveConnectionAndControllerChangesSuppressAnExpiredTimer() {
        val state = CarPlayReconnectRetryState()
        val owner = Any()
        val controller = Any()
        val ticket = state.schedule(owner, controller, 5, 20, controllerManagedRecovery = false)!!

        assertFalse(state.consume(ticket, owner, controller, 5, 20, connectionActive = true))

        val replacement = state.schedule(owner, controller, 5, 20, controllerManagedRecovery = false)!!
        assertFalse(state.consume(replacement, owner, Any(), 5, 20))

        val managed = state.schedule(owner, controller, 5, 20, controllerManagedRecovery = false)!!
        assertFalse(state.consume(managed, owner, controller, 5, 20, controllerManagedRecovery = true))

        val menuPending = state.schedule(owner, controller, 5, 20, controllerManagedRecovery = false)!!
        assertFalse(state.consume(menuPending, owner, controller, 5, 20, hostAvailable = false))
    }

    private fun CarPlayReconnectRetryState.consume(
        ticket: CarPlayReconnectRetryState.Ticket,
        owner: Any,
        controller: Any?,
        generation: Int,
        ownerGeneration: Long,
        connectionActive: Boolean = false,
        hostAvailable: Boolean = true,
        controllerManagedRecovery: Boolean = false,
    ): Boolean = consumeIfCurrent(
        ticket = ticket,
        owner = owner,
        controller = controller,
        restartGeneration = generation,
        ownerGeneration = ownerGeneration,
        ownsBackgroundSession = true,
        hostAvailable = hostAvailable,
        controllerManagedRecovery = controllerManagedRecovery,
        connectionActive = connectionActive,
    )
}
