package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The record choice in front of wired pairing: the per-iPhone record when the iPhone reports its
 * DevicePublicKey, the newest saved record when that read fails, and nothing at all when either the
 * read or the attempt was cancelled. The USB read itself needs a device.
 */
class WiredLockdownRecordLookupTest {
    private val deviceRecord = record("a")
    private val newestRecord = record("b")

    @Test
    fun aReportedDeviceKeySelectsThatIphonesRecord() {
        var requested: ByteArray? = null
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { deviceKey("a") },
            isCancelled = { false },
            loadForDevice = { key ->
                requested = key
                deviceRecord
            },
            loadNewest = { error("the newest record must not be used when the key was read") },
        )
        assertEquals(deviceRecord, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.EXACT_MATCH, choice.source)
        assertTrue(choice.discardableOnRejection)
        assertEquals(String(deviceKey("a")), String(requested!!))
    }

    @Test
    fun anIphoneWithoutARecordFallsThroughToNewPairing() {
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { deviceKey("c") },
            isCancelled = { false },
            loadForDevice = { null },
            loadNewest = { newestRecord },
        )
        assertNull(choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.NONE, choice.source)
        assertFalse(choice.discardableOnRejection)
    }

    @Test
    fun aRetainedCopyIsUsedWhenNoStoredRecordIsLeft() {
        // The stored copy was rejected and cleared, and the recovery attempt then failed on the USB
        // side, so the next connection has to reach the retained copy without a stored record.
        val retained = record("a", hostId = "HOST-a-retained")
        var retainedRequested: ByteArray? = null
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { deviceKey("a") },
            isCancelled = { false },
            loadForDevice = { null },
            loadNewest = { error("the newest record must not be used when the key was read") },
            loadRetained = { key ->
                retainedRequested = key
                retained
            },
        )
        assertEquals(retained, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.RETAINED, choice.source)
        // A retained copy is not a stored record: a rejection must not delete anything.
        assertFalse(choice.discardableOnRejection)
        assertTrue(choice.promotableOnAcceptance)
        assertEquals(String(deviceKey("a")), String(retainedRequested!!))
    }

    @Test
    fun aStoredRecordWinsOverARetainedCopy() {
        val retained = record("a", hostId = "HOST-a-retained")
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { deviceKey("a") },
            isCancelled = { false },
            loadForDevice = { deviceRecord },
            loadNewest = { error("the newest record must not be used when the key was read") },
            loadRetained = { error("the retained copy must not be consulted while a record is stored") },
        )
        assertEquals(deviceRecord, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.EXACT_MATCH, choice.source)
        assertFalse(choice.promotableOnAcceptance)
        assertTrue(retained.hostId.isNotEmpty())
    }

    @Test
    fun aTransportFailureUsesTheNewestSavedRecord() {
        val failures = mutableListOf<Exception>()
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { throw IphoneUsbException.TimedOut("lockdown read timed out") },
            isCancelled = { false },
            loadForDevice = { error("the per-iPhone record must not be used without a key") },
            loadNewest = { newestRecord },
            onLookupFailure = { failures += it },
        )
        assertEquals(newestRecord, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.FALLBACK, choice.source)
        // The fallback record may belong to another iPhone, so a rejection must not delete it.
        assertFalse(choice.discardableOnRejection)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IphoneUsbException.TimedOut)
    }

    @Test
    fun aPairingTimeoutAlsoFallsBack() {
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { throw LockdownPairingException.TimedOut },
            isCancelled = { false },
            loadForDevice = { error("the per-iPhone record must not be used without a key") },
            loadNewest = { newestRecord },
        )
        assertEquals(newestRecord, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.FALLBACK, choice.source)
    }

    @Test
    fun anInvalidResponseFallsBack() {
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { throw LockdownPairingException.InvalidResponse("DevicePublicKey was not data") },
            isCancelled = { false },
            loadForDevice = { error("the per-iPhone record must not be used without a key") },
            loadNewest = { newestRecord },
        )
        assertEquals(newestRecord, choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.FALLBACK, choice.source)
    }

    @Test
    fun aFallbackWithoutAnySavedRecordStillPairsNew() {
        val choice = WiredLockdownRecordLookup.choose(
            readDevicePublicKey = { throw IOException("USBMUX TCP connection failed") },
            isCancelled = { false },
            loadForDevice = { error("the per-iPhone record must not be used without a key") },
            loadNewest = { null },
        )
        assertNull(choice.record)
        assertEquals(WiredLockdownRecordChoice.Source.NONE, choice.source)
    }

    @Test
    fun aCancelledReadIsNotSwallowed() {
        val newestLoaded = AtomicBoolean(false)
        try {
            WiredLockdownRecordLookup.choose(
                readDevicePublicKey = { throw LockdownPairingException.Cancelled },
                isCancelled = { false },
                loadForDevice = { error("a cancelled attempt must not pick a record") },
                loadNewest = {
                    newestLoaded.set(true)
                    newestRecord
                },
            )
            fail("a cancelled read must not fall back")
        } catch (expected: LockdownPairingException.Cancelled) {
            assertFalse(newestLoaded.get())
        }
    }

    @Test
    fun aReadFailureAfterTheAttemptWasSupersededIsReportedAsCancelled() {
        // A USB disconnect is a transport failure, but on a superseded attempt it is not a reason to
        // continue, and callers must be able to tell that apart from a real lookup failure.
        try {
            WiredLockdownRecordLookup.choose(
                readDevicePublicKey = { throw IphoneUsbException.DeviceUnavailable("USB was disconnected") },
                isCancelled = { true },
                loadForDevice = { error("a superseded attempt must not pick a record") },
                loadNewest = { error("a superseded attempt must not fall back") },
            )
            fail("a superseded attempt must not fall back")
        } catch (expected: LockdownPairingException.Cancelled) {
            assertTrue(true)
        }
    }

    @Test
    fun aSuccessfulReadAfterTheAttemptWasSupersededDoesNotPickARecord() {
        val attempt = intArrayOf(1)
        try {
            WiredLockdownRecordLookup.choose(
                readDevicePublicKey = {
                    attempt[0] = 2
                    deviceKey("a")
                },
                isCancelled = { attempt[0] != 1 },
                loadForDevice = { error("a superseded attempt must not read a record") },
                loadNewest = { error("a superseded attempt must not fall back") },
            )
            fail("a superseded attempt must not pick a record")
        } catch (expected: LockdownPairingException.Cancelled) {
            assertTrue(true)
        }
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
