package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a confirmed CarKit connection is allowed to write. A retained record is stored only when
 * promotion reports success, and only a stored record may move in the recency order.
 */
class WiredLockdownConfirmationTest {
    private val accepted = record("a")
    private val retained = record("a", hostId = "HOST-a-retained")
    private val newRecord = record("a", hostId = "HOST-a-new")

    @Test
    fun aPromotedRetainedRecordMovesToTheFrontAfterItIsStored() {
        val events = mutableListOf<String>()
        val confirmed = WiredLockdownConfirmation.confirm(
            acceptedRecord = retained,
            recordToPromote = retained,
            pairedNewRecord = null,
            promote = {
                events += "promote:${it.hostId}"
                true
            },
            save = { events += "save:${it.hostId}" },
            markUsed = { events += "used" },
        )
        assertTrue(confirmed)
        assertEquals(listOf("promote:HOST-a-retained", "used"), events)
    }

    @Test
    fun aFailedPromotionIsNotFollowedByARecencyUpdate() {
        // commit() can fail, and SharedPreferences may already hold the new values in memory, so the
        // only safe assumption is that the store does not have the record.
        val events = mutableListOf<String>()
        val confirmed = WiredLockdownConfirmation.confirm(
            acceptedRecord = retained,
            recordToPromote = retained,
            pairedNewRecord = null,
            promote = { false },
            save = { events += "save:${it.hostId}" },
            markUsed = { events += "used" },
            onPromotionFailed = { events += "failed" },
        )
        assertFalse(confirmed)
        assertEquals(listOf("failed"), events)
    }

    @Test
    fun aNewRecordIsSavedBeforeItIsMarkedUsed() {
        val events = mutableListOf<String>()
        val confirmed = WiredLockdownConfirmation.confirm(
            acceptedRecord = newRecord,
            recordToPromote = null,
            pairedNewRecord = newRecord,
            promote = { events += "promote"; true },
            save = { events += "save:${it.hostId}" },
            markUsed = { events += "used" },
        )
        assertTrue(confirmed)
        assertEquals(listOf("save:HOST-a-new", "used"), events)
    }

    @Test
    fun aStoredRecordIsOnlyMarkedUsed() {
        val events = mutableListOf<String>()
        val confirmed = WiredLockdownConfirmation.confirm(
            acceptedRecord = accepted,
            recordToPromote = null,
            pairedNewRecord = null,
            promote = { events += "promote"; true },
            save = { events += "save" },
            markUsed = { events += "used" },
        )
        assertTrue(confirmed)
        assertEquals(listOf("used"), events)
    }

    private fun deviceKey(phone: String) =
        "-----BEGIN RSA PUBLIC KEY-----\n$phone\n-----END RSA PUBLIC KEY-----\n".toByteArray()

    private fun record(phone: String, hostId: String = "HOST-$phone") = LockdownPairRecord.restore(
        hostId = hostId,
        systemBuid = "BUID-$phone",
        wifiMacAddress = "aa:bb:cc:dd:ee:ff",
        devicePublicKeyPem = deviceKey(phone),
        deviceCertificatePem = "device-cert-$phone".toByteArray(),
        hostPrivateKeyPem = "host-private-$phone".toByteArray(),
        hostCertificatePem = "host-cert-$phone".toByteArray(),
        rootPrivateKeyPem = "root-private-$phone".toByteArray(),
        rootCertificatePem = "root-cert-$phone".toByteArray(),
    )
}
