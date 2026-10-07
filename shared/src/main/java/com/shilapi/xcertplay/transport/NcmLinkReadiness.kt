package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class NcmLinkPhase {
    PRE_CARPLAY_START,
    CARPLAY_START_SENT,
    NCM_LINK_PROBING,
    NCM_LINK_READY,
}

internal enum class NcmLinkOutcome {
    NO_LINK_PROOF,
    RX_ONLY,
    TX_ONLY,
    BIDIRECTIONAL_LINK,
}

internal fun ncmLinkOutcome(rxProven: Boolean, txProven: Boolean): NcmLinkOutcome = when {
    rxProven && txProven -> NcmLinkOutcome.BIDIRECTIONAL_LINK
    rxProven -> NcmLinkOutcome.RX_ONLY
    txProven -> NcmLinkOutcome.TX_ONLY
    else -> NcmLinkOutcome.NO_LINK_PROOF
}

internal class NcmLinkReadiness {
    private val phase = AtomicReference(NcmLinkPhase.PRE_CARPLAY_START)
    private val rxProven = AtomicBoolean(false)
    private val txProven = AtomicBoolean(false)

    fun phase(): NcmLinkPhase = phase.get()

    fun hasRxProof(): Boolean = rxProven.get()

    fun hasTxProof(): Boolean = txProven.get()

    fun outcome(): NcmLinkOutcome = ncmLinkOutcome(hasRxProof(), hasTxProof())

    fun markCarPlayStartSent() {
        phase.compareAndSet(NcmLinkPhase.PRE_CARPLAY_START, NcmLinkPhase.CARPLAY_START_SENT)
    }

    fun markOutboundAttempt() {
        phase.compareAndSet(NcmLinkPhase.CARPLAY_START_SENT, NcmLinkPhase.NCM_LINK_PROBING)
    }

    fun recordInboundIpv6(): Boolean {
        rxProven.set(true)
        return markReadyIfBidirectional()
    }

    fun recordOutboundIpv6Success(): Boolean {
        txProven.set(true)
        return markReadyIfBidirectional()
    }

    fun markLinkReady(): Boolean = phase.getAndSet(NcmLinkPhase.NCM_LINK_READY) != NcmLinkPhase.NCM_LINK_READY

    private fun markReadyIfBidirectional(): Boolean =
        rxProven.get() && txProven.get() && markLinkReady()
}
