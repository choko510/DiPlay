package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * Recovery for a saved record that the iPhone rejected.
 *
 * When this iPhone identified the rejected record, the store may still hold the record the older
 * build saved for it (the migration keeps it aside when the two disagree). Opening that record
 * before a new Pair turns "the stored copy went wrong" into a silent recovery instead of another
 * Trust prompt. A fallback record may belong to another iPhone, so its retained copy is never tried.
 */
internal object WiredLockdownRecovery {
    /**
     * Tries [retained] when [rejected] is this iPhone's own record and the attempt is still current.
     * Returns the record together with [open]'s value, or null when the caller has to pair anew.
     */
    fun <T> tryRetainedRecord(
        rejected: WiredLockdownRecordChoice,
        retained: LockdownPairRecord?,
        isCurrentAttempt: () -> Boolean,
        open: (LockdownPairRecord) -> T,
        onRetainedRejected: (Throwable) -> Unit = {},
    ): Pair<LockdownPairRecord, T>? {
        if (!rejected.discardableOnRejection || retained == null || !isCurrentAttempt()) return null
        return try {
            retained to open(retained)
        } catch (error: Throwable) {
            onRetainedRejected(error)
            null
        }
    }
}
