package com.shilapi.xcertplay

internal data class ReconnectAfterLossTicket(
    val restartGeneration: Int,
    val connectionEpoch: Long,
    val ownerEpoch: Long,
    val lossEpoch: Long,
)

internal data class ReconnectAfterLossBlockers(
    val shuttingDown: Boolean = false,
    val finishing: Boolean = false,
    val destroyed: Boolean = false,
    val menuOpen: Boolean = false,
    val handshakeResetInProgress: Boolean = false,
    val pendingDisplayResize: Boolean = false,
    val controllerManaged: Boolean = false,
) {
    val any: Boolean
        get() = shuttingDown || finishing || destroyed || menuOpen || handshakeResetInProgress ||
            pendingDisplayResize || controllerManaged
}

internal class ReconnectAfterLossGate {
    private var nextLossEpoch = 0L
    private var pending: ReconnectAfterLossTicket? = null

    @Synchronized
    fun schedule(
        restartGeneration: Int,
        connectionEpoch: Long,
        ownerEpoch: Long,
        controllerManaged: Boolean,
    ): ReconnectAfterLossTicket? {
        if (controllerManaged) return null
        val current = pending
        if (current != null) {
            if (current.restartGeneration == restartGeneration && current.connectionEpoch == connectionEpoch &&
                current.ownerEpoch == ownerEpoch
            ) {
                return null
            }
            pending = null
        }
        check(nextLossEpoch < Long.MAX_VALUE) { "reconnect loss epoch exhausted" }
        return ReconnectAfterLossTicket(restartGeneration, connectionEpoch, ownerEpoch, ++nextLossEpoch)
            .also { pending = it }
    }

    @Synchronized
    fun recovered(
        restartGeneration: Int,
        connectionEpoch: Long,
        ownerEpoch: Long,
    ): ReconnectAfterLossTicket? {
        val ticket = pending ?: return null
        if (ticket.restartGeneration != restartGeneration || ticket.connectionEpoch != connectionEpoch ||
            ticket.ownerEpoch != ownerEpoch
        ) {
            return null
        }
        pending = null
        return ticket
    }

    @Synchronized
    fun cancel(ticket: ReconnectAfterLossTicket? = null): Boolean {
        val current = pending ?: return false
        if (ticket != null && ticket != current) return false
        pending = null
        return true
    }

    @Synchronized
    fun shouldRestart(
        ticket: ReconnectAfterLossTicket,
        currentRestartGeneration: Int,
        currentConnectionEpoch: Long,
        currentOwnerEpoch: Long,
        isOwner: Boolean,
        hasActiveSession: Boolean,
        blockers: ReconnectAfterLossBlockers,
    ): Boolean {
        if (pending != ticket) return false
        pending = null
        return ticket.restartGeneration == currentRestartGeneration &&
            ticket.connectionEpoch == currentConnectionEpoch && ticket.ownerEpoch == currentOwnerEpoch && isOwner &&
            !hasActiveSession && !blockers.any
    }
}
