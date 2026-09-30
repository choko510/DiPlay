package com.shilapi.xcertplay.transport

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
        assertNull(rejectedLockdownPairRecordError(IphoneUsbException.Protocol("StartSession failed")))
    }
}
