package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Recovery for a rejected saved record. The older build's retained copy is offered only for the
 * iPhone that identified the rejected record, only while the attempt is current, and only a real
 * Lockdown rejection of the record is treated as "pair again".
 */
class WiredLockdownRecoveryTest {
    private val rejectedRecord = record("a")
    private val retainedRecord = record("a", hostId = "HOST-a-retained")

    private val exactMatch = WiredLockdownRecordChoice(
        record = rejectedRecord,
        source = WiredLockdownRecordChoice.Source.EXACT_MATCH,
    )
    private val fallback = WiredLockdownRecordChoice(
        record = record("b"),
        source = WiredLockdownRecordChoice.Source.FALLBACK,
    )

    @Test
    fun aRetainedRecordThatOpensIsUsedInsteadOfPairingAgain() {
        var opened: LockdownPairRecord? = null
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = retainedRecord,
            isCurrentAttempt = { true },
            open = { record ->
                opened = record
                "opened"
            },
        )
        assertEquals(retainedRecord, opened)
        assertEquals(retainedRecord, recovered?.first)
        assertEquals("opened", recovered?.second)
    }

    @Test
    fun aLockdownRejectionOfTheRetainedRecordFallsThroughToPairing() {
        val rejections = mutableListOf<String>()
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = retainedRecord,
            isCurrentAttempt = { true },
            open = { throw IphoneUsbException.LockdownRemoteError("StartSession", "InvalidHostID") },
            onRetainedRejected = { rejections += it },
        )
        assertNull(recovered)
        assertEquals(listOf("InvalidHostID"), rejections)
    }

    @Test
    fun aUsbFailureWhileTryingTheRetainedRecordIsNotARejection() {
        // A timeout says nothing about the record, so it must not start a new pairing.
        try {
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = exactMatch,
                retained = retainedRecord,
                isCurrentAttempt = { true },
                open = { throw IphoneUsbException.TimedOut("Lockdown StartSession timed out") },
                onRetainedRejected = { fail("a transport failure is not a rejection: $it") },
            )
            fail("a transport failure must propagate")
        } catch (expected: IphoneUsbException.TimedOut) {
            assertTrue(true)
        }
    }

    @Test
    fun aTransportFailureWhileTryingTheRetainedRecordIsNotARejection() {
        try {
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = exactMatch,
                retained = retainedRecord,
                isCurrentAttempt = { true },
                open = { throw IOException("USBMUX TCP connection failed") },
            )
            fail("a transport failure must propagate")
        } catch (expected: IOException) {
            assertEquals("USBMUX TCP connection failed", expected.message)
        }
    }

    @Test
    fun aCancelledAttemptIsNotARejection() {
        try {
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = exactMatch,
                retained = retainedRecord,
                isCurrentAttempt = { true },
                open = { throw LockdownPairingException.Cancelled },
            )
            fail("a cancellation must propagate")
        } catch (expected: LockdownPairingException.Cancelled) {
            assertTrue(true)
        }
    }

    @Test
    fun anAttemptLostWhileOpeningIsReportedAsCancelled() {
        val stream = StringBuilder("stream")
        val closed = mutableListOf<String>()
        val current = booleanArrayOf(true)
        try {
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = exactMatch,
                retained = retainedRecord,
                isCurrentAttempt = { current[0] },
                open = {
                    current[0] = false
                    stream
                },
                onStale = { closed += it.toString() },
            )
            fail("a success nobody can use must not be reported")
        } catch (expected: LockdownPairingException.Cancelled) {
            assertEquals(listOf("stream"), closed)
        }
    }

    @Test
    fun aFatalErrorIsNotSwallowed() {
        try {
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = exactMatch,
                retained = retainedRecord,
                isCurrentAttempt = { true },
                open = { throw OutOfMemoryError("no room") },
            )
            fail("an Error must propagate")
        } catch (expected: OutOfMemoryError) {
            assertTrue(true)
        }
    }

    @Test
    fun aRetainedRecordIsNotTriedWithoutOne() {
        val attempts = AtomicInteger()
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = null,
            isCurrentAttempt = { true },
            open = { attempts.incrementAndGet() },
        )
        assertNull(recovered)
        assertEquals(0, attempts.get())
    }

    @Test
    fun aFallbackRecordNeverTriesARetainedCopy() {
        // A fallback record may belong to another iPhone, so a copy retained under its key is still
        // not known to be this iPhone's record.
        val attempts = AtomicInteger()
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = fallback,
            retained = record("b"),
            isCurrentAttempt = { true },
            open = { attempts.incrementAndGet() },
        )
        assertNull(recovered)
        assertEquals(0, attempts.get())
    }

    @Test
    fun aRetainedRecordIsNotTriedOnceTheAttemptIsGone() {
        val attempts = AtomicInteger()
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = retainedRecord,
            isCurrentAttempt = { false },
            open = { attempts.incrementAndGet() },
        )
        assertNull(recovered)
        assertEquals(0, attempts.get())
    }

    @Test
    fun aRetainedOnlyChoiceIsAlreadyTheRecordInUse() {
        // A retained copy chosen at connection start needs no recovery: it is the stored candidate.
        val retainedChoice = WiredLockdownRecordChoice(
            record = retainedRecord,
            source = WiredLockdownRecordChoice.Source.RETAINED,
        )
        assertFalse(retainedChoice.discardableOnRejection)
        assertTrue(retainedChoice.promotableOnAcceptance)
        assertNull(
            WiredLockdownRecovery.tryRetainedRecord(
                rejected = retainedChoice,
                retained = retainedRecord,
                isCurrentAttempt = { true },
                open = { error("no second attempt is needed") },
            ),
        )
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
