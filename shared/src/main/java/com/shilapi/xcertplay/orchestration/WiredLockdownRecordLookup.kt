package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.rejectedLockdownPairRecordError

/**
 * The wired Lockdown record to try for one startup attempt, and how it was chosen.
 *
 * [Source.EXACT_MATCH] means the iPhone reported its own DevicePublicKey and the record stored under
 * that fingerprint was used. [Source.RETAINED] means no stored copy was usable and the record the
 * older build saved for this iPhone is being tried instead. [Source.FALLBACK] means the lookup
 * failed and the newest saved record was used, which says nothing about whose record it is.
 */
internal data class WiredLockdownRecordChoice(
    val record: LockdownPairRecord?,
    val source: Source,
) {
    enum class Source { EXACT_MATCH, FALLBACK, RETAINED, NONE }

    /**
     * A rejected record may be discarded only when this iPhone identified it as its stored copy,
     * because a fallback record can belong to another iPhone whose trust must survive a failed
     * lookup here.
     */
    val discardableOnRejection: Boolean get() = source == Source.EXACT_MATCH

    /** Only a retained copy has to be turned into this iPhone's stored copy once it works. */
    val promotableOnAcceptance: Boolean get() = source == Source.RETAINED
}

/**
 * Chooses the wired Lockdown record for one startup attempt.
 *
 * The record stored for this iPhone is used when the iPhone reports its DevicePublicKey. When no
 * stored copy is usable, the record the older build saved for the same iPhone is tried, so a copy
 * that was rejected earlier does not make the owner trust the accessory again. If the key cannot be
 * read at all, wired continues with the newest saved record, as releases up to 0.2.22 behaved.
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
        loadRetained: (devicePublicKeyPem: ByteArray) -> LockdownPairRecord? = { null },
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
        if (devicePublicKey == null) {
            val record = loadNewest()
            return WiredLockdownRecordChoice(
                record = record,
                source = if (record != null) WiredLockdownRecordChoice.Source.FALLBACK else
                    WiredLockdownRecordChoice.Source.NONE,
            )
        }
        loadForDevice(devicePublicKey)?.let {
            return WiredLockdownRecordChoice(it, WiredLockdownRecordChoice.Source.EXACT_MATCH)
        }
        loadRetained(devicePublicKey)?.let {
            return WiredLockdownRecordChoice(it, WiredLockdownRecordChoice.Source.RETAINED)
        }
        return WiredLockdownRecordChoice(null, WiredLockdownRecordChoice.Source.NONE)
    }
}
