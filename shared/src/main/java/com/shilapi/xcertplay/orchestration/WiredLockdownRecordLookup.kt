package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * The wired Lockdown record to try for one startup attempt, and how it was chosen.
 *
 * [Source.EXACT_MATCH] means the iPhone reported its own DevicePublicKey and the record stored under
 * that fingerprint was used. [Source.FALLBACK] means the lookup failed and the newest saved record
 * was used instead, which says nothing about whose record it is.
 */
internal data class WiredLockdownRecordChoice(
    val record: LockdownPairRecord?,
    val source: Source,
) {
    enum class Source { EXACT_MATCH, FALLBACK, NONE }

    /**
     * A rejected record may be discarded only when this iPhone identified it, because a fallback
     * record can belong to another iPhone whose trust must survive a failed lookup here.
     */
    val discardableOnRejection: Boolean get() = source == Source.EXACT_MATCH
}

/**
 * Chooses the wired Lockdown record for one startup attempt.
 *
 * The record stored for this iPhone is used when the iPhone reports its DevicePublicKey. If the
 * read fails for an ordinary transport reason, wired continues with the newest saved record, which
 * is how releases up to 0.2.22 behaved for every iPhone, so only the per-iPhone choice is lost.
 *
 * A cancelled or superseded attempt never falls back. It throws [LockdownPairingException.Cancelled]
 * whether the read succeeded or failed, so an old attempt cannot pick, use, or discard a record that
 * belongs to the session that replaced it.
 */
internal object WiredLockdownRecordLookup {
    @Throws(LockdownPairingException::class)
    fun choose(
        readDevicePublicKey: () -> ByteArray,
        isCancelled: () -> Boolean,
        loadForDevice: (devicePublicKeyPem: ByteArray) -> LockdownPairRecord?,
        loadNewest: () -> LockdownPairRecord?,
        onLookupFailure: (Exception) -> Unit = {},
    ): WiredLockdownRecordChoice {
        val devicePublicKey = try {
            readDevicePublicKey()
        } catch (error: Exception) {
            if (error is LockdownPairingException.Cancelled) throw error
            if (isCancelled()) throw LockdownPairingException.Cancelled
            onLookupFailure(error)
            null
        }
        if (isCancelled()) throw LockdownPairingException.Cancelled
        if (devicePublicKey != null) {
            val record = loadForDevice(devicePublicKey)
            return WiredLockdownRecordChoice(
                record = record,
                source = if (record != null) WiredLockdownRecordChoice.Source.EXACT_MATCH else
                    WiredLockdownRecordChoice.Source.NONE,
            )
        }
        val record = loadNewest()
        return WiredLockdownRecordChoice(
            record = record,
            source = if (record != null) WiredLockdownRecordChoice.Source.FALLBACK else
                WiredLockdownRecordChoice.Source.NONE,
        )
    }
}
