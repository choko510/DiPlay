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
