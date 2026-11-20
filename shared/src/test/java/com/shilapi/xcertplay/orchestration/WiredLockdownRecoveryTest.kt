package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery for a rejected saved record. The older build's retained copy is offered only for the
 * iPhone that identified the rejected record, and only while the attempt is current; opening it
 * before a new Pair is what keeps a broken stored copy from costing another Trust prompt.
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
    fun aRetainedRecordThatIsRejectedFallsThroughToPairing() {
        val failures = mutableListOf<Throwable>()
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = retainedRecord,
            isCurrentAttempt = { true },
            open = { throw IphoneUsbException.LockdownRemoteError("StartSession", "InvalidHostID") },
            onRetainedRejected = { failures += it },
        )
        assertNull(recovered)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IphoneUsbException.LockdownRemoteError)
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
    fun anAttemptInvalidatedWhileOpeningDoesNotReportSuccess() {
        // The caller re-checks the attempt before it touches the store, so a stale success is never
        // promoted; this asserts the helper itself stays silent about it.
        val current = booleanArrayOf(true)
        val recovered = WiredLockdownRecovery.tryRetainedRecord(
            rejected = exactMatch,
            retained = retainedRecord,
            isCurrentAttempt = { current[0] },
            open = { current[0] = false },
        )
        assertFalse(current[0])
        assertEquals(retainedRecord, recovered?.first)
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
