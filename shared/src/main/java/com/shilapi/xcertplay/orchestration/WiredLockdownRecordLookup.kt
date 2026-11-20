package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * Chooses the wired Lockdown record for one startup attempt.
 *
 * The record stored for this iPhone is used when the iPhone reports its DevicePublicKey. If the
 * read fails for an ordinary transport reason, wired continues with the newest saved record, which
 * is how releases up to 0.2.22 behaved for every iPhone, so only the per-iPhone choice is lost.
 *
 * A cancelled or superseded attempt never falls back: it throws [LockdownPairingException.Cancelled]
 * so that an old attempt cannot pick, use, or clear a record that belongs to the session that
 * replaced it.
 */
internal object WiredLockdownRecordLookup {
    @Throws(LockdownPairingException::class)
    fun choose(
        readDevicePublicKey: () -> ByteArray,
        isCancelled: () -> Boolean,
        loadForDevice: (devicePublicKeyPem: ByteArray) -> LockdownPairRecord?,
        loadNewest: () -> LockdownPairRecord?,
        onLookupFailure: (Exception) -> Unit = {},
    ): LockdownPairRecord? {
        val devicePublicKey = try {
            readDevicePublicKey()
        } catch (error: Exception) {
            if (error is LockdownPairingException.Cancelled || isCancelled()) throw error
            onLookupFailure(error)
            null
        }
        if (isCancelled()) throw LockdownPairingException.Cancelled
        return if (devicePublicKey != null) loadForDevice(devicePublicKey) else loadNewest()
    }
}
