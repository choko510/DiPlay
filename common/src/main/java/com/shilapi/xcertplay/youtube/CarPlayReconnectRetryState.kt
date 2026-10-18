package com.shilapi.xcertplay.youtube

internal class CarPlayReconnectRetryState {
    internal data class Ticket(
        val id: Long,
        val owner: Any,
        val controller: Any?,
        val restartGeneration: Int,
        val ownerGeneration: Long,
        val connectionEpoch: Long,
        val lossEpoch: Long,
    )

    private var nextId = 0L
    private var connectionEpoch = 0L
    private var lossEpoch = 0L
    private var activeSession: Any? = null
    private var pending: Ticket? = null

    @Synchronized
    fun sessionActive(session: Any): Ticket? {
        if (activeSession !== session) connectionEpoch++
        activeSession = session
        lossEpoch++
        return pending.also { pending = null }
    }

    @Synchronized
    fun sessionEnded(session: Any): Boolean {
        if (activeSession != null && activeSession !== session) return false
        activeSession = null
        lossEpoch++
        return true
    }

    @Synchronized
    fun schedule(
        owner: Any,
        controller: Any?,
        restartGeneration: Int,
        ownerGeneration: Long,
        controllerManagedRecovery: Boolean,
    ): Ticket? {
        if (controllerManagedRecovery || pending != null || activeSession != null) return null
        activeSession = null
        lossEpoch++
        return Ticket(
            id = ++nextId,
            owner = owner,
            controller = controller,
            restartGeneration = restartGeneration,
            ownerGeneration = ownerGeneration,
            connectionEpoch = connectionEpoch,
            lossEpoch = lossEpoch,
        ).also { pending = it }
    }

    @Synchronized
    fun cancelPending(): Ticket? {
        lossEpoch++
        return pending.also { pending = null }
    }

    @Synchronized
    fun consumeIfCurrent(
        ticket: Ticket,
        owner: Any,
        controller: Any?,
        restartGeneration: Int,
        ownerGeneration: Long,
        ownsBackgroundSession: Boolean,
        hostAvailable: Boolean,
        controllerManagedRecovery: Boolean,
        connectionActive: Boolean,
    ): Boolean {
        if (pending?.id != ticket.id) return false
        pending = null
        return ticket.owner === owner && ticket.controller === controller &&
            ticket.restartGeneration == restartGeneration && ticket.ownerGeneration == ownerGeneration &&
            ticket.connectionEpoch == connectionEpoch && ticket.lossEpoch == lossEpoch &&
            ownsBackgroundSession && hostAvailable && !controllerManagedRecovery &&
            !connectionActive && activeSession == null
    }
}
