package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockdownCarKitClientTest {
    @Test
    fun typedSavedPairRecordRejectionsIdentifyOnlyRecoverableHostErrors() {
        assertEquals(
            "InvalidPairRecord",
            rejectedLockdownPairRecordError(
                IphoneUsbException.LockdownRemoteError("StartSession", "InvalidPairRecord"),
            ),
        )
        assertEquals(
            "InvalidHostID",
            rejectedLockdownPairRecordError(
                IphoneUsbException.LockdownRemoteError("StartSession", "InvalidHostID"),
            ),
        )
        assertNull(
            rejectedLockdownPairRecordError(
                IphoneUsbException.LockdownRemoteError("StartSession", "InvalidService"),
            ),
        )
    }

    @Test
    fun legacyProtocolMessagesStillClassifySavedPairRecordRejections() {
        assertEquals(
            "InvalidHostID",
            rejectedLockdownPairRecordError(IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidHostID")),
        )
        assertEquals(
            "InvalidPairRecord",
            rejectedLockdownPairRecordError(
                IOException(
                    "wired connection failed",
                    IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidPairRecord"),
                ),
            ),
        )
        assertNull(rejectedLockdownPairRecordError(IphoneUsbException.Protocol("StartSession failed")))
    }

    @Test
    fun aMentionedErrorNameOutsideALockdownVerdictIsNotARejection() {
        // A transport failure must never be read as "the record was rejected": clearing a working
        // record over a timeout costs the driver a new Trust prompt.
        assertNull(
            rejectedLockdownPairRecordError(
                IphoneUsbException.TimedOut("USBMUX read timed out after InvalidHostID retry"),
            ),
        )
        assertNull(
            rejectedLockdownPairRecordError(
                IOException("TLS handshake failed: InvalidPairRecord in diagnostics"),
            ),
        )
        assertNull(
            rejectedLockdownPairRecordError(
                IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidService"),
            ),
        )
    }
}
