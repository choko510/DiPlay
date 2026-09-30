package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmLinkReadinessTest {
    @Test
    fun inboundIpv6AloneDoesNotProveTheOutboundLinkIsReady() {
        val readiness = NcmLinkReadiness()
        readiness.markCarPlayStartSent()
        readiness.markOutboundAttempt()

        assertFalse(readiness.recordInboundIpv6())
        assertEquals(NcmLinkPhase.NCM_LINK_PROBING, readiness.phase())
        assertTrue(readiness.hasRxProof())
        assertFalse(readiness.hasTxProof())
    }

    @Test
    fun successfulOutboundIpv6AloneDoesNotProveTheInboundLinkIsReady() {
        val readiness = NcmLinkReadiness()

        assertFalse(readiness.recordOutboundIpv6Success())
        assertEquals(NcmLinkPhase.PRE_CARPLAY_START, readiness.phase())
        assertFalse(readiness.hasRxProof())
        assertTrue(readiness.hasTxProof())
    }

    @Test
    fun bothIpv6DirectionsProveReadinessRegardlessOfOrder() {
        val inboundFirst = NcmLinkReadiness()
        assertFalse(inboundFirst.recordInboundIpv6())
        assertTrue(inboundFirst.recordOutboundIpv6Success())
        assertEquals(NcmLinkPhase.NCM_LINK_READY, inboundFirst.phase())

        val outboundFirst = NcmLinkReadiness()
        assertFalse(outboundFirst.recordOutboundIpv6Success())
        assertTrue(outboundFirst.recordInboundIpv6())
        assertEquals(NcmLinkPhase.NCM_LINK_READY, outboundFirst.phase())
    }

    @Test
    fun acceptedAirPlayControlIsSufficientReadinessEvidence() {
        val readiness = NcmLinkReadiness()
        readiness.markCarPlayStartSent()

        assertTrue(readiness.markLinkReady())
        assertFalse(readiness.markLinkReady())
        assertEquals(NcmLinkPhase.NCM_LINK_READY, readiness.phase())
        assertFalse(readiness.hasRxProof())
        assertFalse(readiness.hasTxProof())
    }
}
