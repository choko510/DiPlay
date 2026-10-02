package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ConnectionTraceEventTest {
    @Test
    fun elapsedTimeIsAttemptRelativeAndClampedAtZero() {
        assertEquals(0L, connectionTraceElapsedMs(4_000_000L, 4_000_000L))
        assertEquals(125L, connectionTraceElapsedMs(4_000_000L, 129_000_000L))
        assertEquals(0L, connectionTraceElapsedMs(129_000_000L, 4_000_000L))
    }

    @Test
    fun traceNamesTheOpenedIap2SessionBeforeNcmAttach() {
        assertTrue(ConnectionTraceStage.values().contains(ConnectionTraceStage.USB_IAP2_SESSION_OPENED))
        assertFalse(ConnectionTraceStage.values().any { it.name == "USB_DATA_PATHS_OPENED" })
    }

    @Test
    fun traceIncludesTheNcmNetworkAndAirPlayStartupMilestones() {
        val required = setOf(
            "NCM_BRIDGE_STARTED",
            "NCM_READ_QUEUED",
            "NCM_FIRST_USB_COMPLETION",
            "NCM_FIRST_ETHERNET_RX",
            "NCM_FIRST_IPV6_RX",
            "NCM_PEER_MAC_LEARNED",
            "NCM_LINK_READY",
            "NCM_FIRST_IPV6_TX_ATTEMPT",
            "NCM_FIRST_IPV6_TX_SUCCESS",
            "NCM_TX_NOT_READY",
            "AIRPLAY_CONTROL_ACCEPTED",
            "AIRPLAY_CONTROL_ENCRYPTED",
            "AIRPLAY_EVENT_ACCEPTED",
            "SCREEN_STREAM_OPENED",
            "FIRST_FRAME_SUBMITTED_TO_SURFACE",
        )

        assertTrue(ConnectionTraceStage.values().map { it.name }.containsAll(required))
    }

    @Test
    fun ncmFailureCodesStayDistinctInTrace() {
        assertEquals(
            ConnectionTraceError.NCM_REQUEST_WAIT_ERROR,
            classifyConnectionTraceError(IphoneUsbException.DeviceUnavailable("NCM_REQUEST_WAIT_ERROR")),
        )
        assertEquals(
            ConnectionTraceError.NCM_QUEUE_ERROR,
            classifyConnectionTraceError(IphoneUsbException.DeviceUnavailable("NCM_QUEUE_ERROR")),
        )
        assertEquals(
            ConnectionTraceError.USBMUX_TRANSPORT_ERROR,
            classifyConnectionTraceError(IphoneUsbException.DeviceUnavailable("USBMUX_TRANSPORT_ERROR")),
        )
    }

    @Test
    fun usbProtocolSubtypeTakesPrecedenceOverGenericMessageHeuristics() {
        assertEquals(
            ConnectionTraceError.USB,
            classifyConnectionTraceError(IphoneUsbException.Protocol("invalid frame")),
        )
    }

    @Test
    fun usbTimeoutSubtypeIsClassifiedAsControl() {
        assertEquals(
            ConnectionTraceError.CONTROL,
            classifyConnectionTraceError(IphoneUsbException.TimedOut("MFi authentication timed out")),
        )
    }

    @Test
    fun lockdownAndPlistOperationMarkersTakePrecedenceOverUsbSubtypeFallbacks() {
        assertEquals(
            ConnectionTraceError.PAIRING,
            classifyConnectionTraceError(IphoneUsbException.Protocol("USBMUX Lockdown plist response was invalid")),
        )
        assertEquals(
            ConnectionTraceError.PAIRING,
            classifyConnectionTraceError(IphoneUsbException.TimedOut("Lockdown plist read timed out")),
        )
    }

    @Test
    fun tlsProtocolFailureIsClassifiedAsPairing() {
        assertEquals(
            ConnectionTraceError.PAIRING,
            classifyConnectionTraceError(IphoneUsbException.Protocol("TLS handshake failed")),
        )
    }

    @Test
    fun usbmuxTimeoutIsClassifiedAsUsb() {
        assertEquals(
            ConnectionTraceError.USB,
            classifyConnectionTraceError(IphoneUsbException.TimedOut("USBMUX TCP connection timed out")),
        )
    }

    @Test
    fun iap2TimeoutIsClassifiedAsControl() {
        assertEquals(
            ConnectionTraceError.CONTROL,
            classifyConnectionTraceError(IphoneUsbException.TimedOut("iAP2 identification timed out")),
        )
    }

    @Test
    fun wrappedSocketTimeoutIsClassifiedAsControl() {
        assertEquals(
            ConnectionTraceError.CONTROL,
            classifyConnectionTraceError(IOException("control operation failed", SocketTimeoutException("read timed out"))),
        )
    }
}
