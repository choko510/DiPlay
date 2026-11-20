package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record transitions of one wiring attempt, driven without a USB device: which record is used,
 * what a failed attempt leads to, and which record a successful connection stores.
 */
class WiredLockdownPersistenceTest {
    private val retainedRecord = record("a", hostId = "HOST-a-retained")
    private val newRecord = record("a", hostId = "HOST-a-new")
    private val storedRecord = record("a", hostId = "HOST-a")

    @Test
    fun aRejectedRetainedRecordIsNotStoredAfterANewPairSucceeds() {
        // The connection started on the retained copy, Lockdown rejected it, and the new Pair is the
        // record that actually connected, so the rejected copy must not come back.
        val store = FakeStore(deviceKey("a"), hostIds)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(retainedRecord, WiredLockdownRecordChoice.Source.RETAINED),
        )
        // Retained copy rejected, Pair started.
        persistence.usingPairedRecord(newRecord)
        assertTrue(persistence.confirm(newRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("save:HOST-a-new", "used:HOST-a"), store.events)
        assertEquals(newRecord, store.records["HOST-a-new"])

        // The next connection finds the new record, and the rejected copy is never offered again.
        val nextChoice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { deviceKey("a") },
            isCancelled = { false },
            loadForDevice = store::load,
            loadNewest = { error("the key was read, so the newest record is not used") },
            loadRetained = { error("a stored record exists, so no retained copy is consulted") },
        )
        assertEquals(newRecord, nextChoice.record)
        assertEquals(WiredLockdownRecordChoice.Source.EXACT_MATCH, nextChoice.source)
    }

    @Test
    fun anAcceptedRetainedRecordIsPromotedInsteadOfPaired() {
        val store = FakeStore(deviceKey("a"), hostIds)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(retainedRecord, WiredLockdownRecordChoice.Source.RETAINED),
        )
        assertTrue(persistence.confirm(retainedRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("promote:HOST-a-retained", "used:HOST-a"), store.events)
        assertEquals(retainedRecord, store.records["HOST-a-retained"])
        assertNull(store.saved)
    }

    @Test
    fun anAcceptedStoredRecordIsOnlyMarkedUsed() {
        val store = FakeStore(deviceKey("a"), hostIds)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(storedRecord, WiredLockdownRecordChoice.Source.EXACT_MATCH),
        )
        assertTrue(persistence.confirm(storedRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("used:HOST-a"), store.events)
        assertNull(store.saved)
        assertNull(store.promoted)
    }

    @Test
    fun aRetainedRecoveryAfterARejectionIsPromoted() {
        // Stored copy rejected and cleared, the retained copy opened: promotion is the whole point.
        val store = FakeStore(deviceKey("a"), hostIds)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(storedRecord, WiredLockdownRecordChoice.Source.EXACT_MATCH),
        )
        persistence.usingRetainedRecord(retainedRecord)
        assertTrue(persistence.confirm(retainedRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("promote:HOST-a-retained", "used:HOST-a"), store.events)
        assertEquals(retainedRecord, store.records["HOST-a-retained"])
    }

    @Test
    fun anUnusableRetainedCopyDoesNotSurviveItsOwnRejection() {
        // Both copies failed, so a new Pair is stored; the retained copy is gone with the old store.
        val store = FakeStore(deviceKey("a"), hostIds)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(storedRecord, WiredLockdownRecordChoice.Source.EXACT_MATCH),
        )
        persistence.usingRetainedRecord(retainedRecord)
        persistence.usingPairedRecord(newRecord)
        assertTrue(persistence.confirm(newRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("save:HOST-a-new", "used:HOST-a"), store.events)
        assertEquals(newRecord, store.records["HOST-a-new"])
    }

    @Test
    fun aFallbackRecordIsNeitherPromotedNorReplaced() {
        val store = FakeStore(deviceKey("b"), hostIds)
        val fallback = record("b", hostId = "HOST-b")
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(fallback, WiredLockdownRecordChoice.Source.FALLBACK),
        )
        assertTrue(persistence.confirm(fallback, store::promote, store::save, store::markUsed))

        assertEquals(listOf("used:HOST-b"), store.events)
        assertNull(store.promoted)
        assertNull(store.saved)
    }

    @Test
    fun aFailedPromotionKeepsTheStoreUntouched() {
        val store = FakeStore(deviceKey("a"), hostIds, promotionSucceeds = false)
        val persistence = WiredLockdownPersistence()
        persistence.usingChosenRecord(
            WiredLockdownRecordChoice(retainedRecord, WiredLockdownRecordChoice.Source.RETAINED),
        )
        assertFalse(persistence.confirm(retainedRecord, store::promote, store::save, store::markUsed))

        assertEquals(listOf("promote:HOST-a-retained"), store.events)
        assertNull(store.records["HOST-a-retained"])
    }

    private class FakeStore(
        private val devicePublicKeyPem: ByteArray,
        private val hostIdByKey: Map<String, String>,
        private val promotionSucceeds: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val records = mutableMapOf<String, LockdownPairRecord>()
        var promoted: LockdownPairRecord? = null
        var saved: LockdownPairRecord? = null

        fun promote(record: LockdownPairRecord): Boolean {
            events += "promote:${record.hostId}"
            promoted = record
            if (!promotionSucceeds) return false
            records[record.hostId] = record
            return true
        }

        fun save(record: LockdownPairRecord) {
            events += "save:${record.hostId}"
            saved = record
            records[record.hostId] = record
        }

        fun markUsed(devicePublicKeyPem: ByteArray) {
            assertEquals(String(this.devicePublicKeyPem), String(devicePublicKeyPem))
            events += "used:${hostIdByKey[String(devicePublicKeyPem)]}"
        }

        fun load(devicePublicKeyPem: ByteArray): LockdownPairRecord? {
            assertEquals(String(this.devicePublicKeyPem), String(devicePublicKeyPem))
            return records.values.firstOrNull()
        }
    }

    private val hostIds = mapOf(
        String(deviceKey("a")) to "HOST-a",
        String(deviceKey("b")) to "HOST-b",
    )

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
