package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairingException
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.rejectedLockdownPairRecordError

/**
 * Recovery for a saved record that the iPhone rejected.
 *
 * When this iPhone identified the rejected record, the store may still hold the record the older
 * build saved for it (the migration keeps it aside). Opening that record before a new Pair turns
 * "the stored copy went wrong" into a silent recovery instead of another Trust prompt. A fallback
 * record may belong to another iPhone, so its retained copy is never tried.
 */
internal object WiredLockdownRecovery {
    /**
     * Tries [retained] when [rejected] is this iPhone's own stored copy and the attempt is current.
     *
     * Only a Lockdown rejection of the record itself turns into "pair again". A transport failure,
     * a timeout, a cancellation, or a superseded attempt is not a verdict on the record, so it is
     * rethrown untouched and the caller must not start pairing over it. A success that the attempt
     * can no longer use is closed through [onStale] and reported as cancelled.
     *
     * Returns the record together with [open]'s value, or null when the record really was rejected.
     */
    fun <T> tryRetainedRecord(
        rejected: WiredLockdownRecordChoice,
        retained: LockdownPairRecord?,
        isCurrentAttempt: () -> Boolean,
        open: (LockdownPairRecord) -> T,
        onStale: (T) -> Unit = {},
        onRetainedRejected: (String) -> Unit = {},
    ): Pair<LockdownPairRecord, T>? {
        if (!rejected.discardableOnRejection || retained == null || !isCurrentAttempt()) return null
        val opened = try {
            open(retained)
        } catch (error: Throwable) {
            when {
                error is Error -> throw error
                error is LockdownPairingException.Cancelled -> throw error
                !isCurrentAttempt() -> throw LockdownPairingException.Cancelled
                else -> {
                    val rejection = rejectedLockdownPairRecordError(error)
                    if (rejection == null) throw error
                    onRetainedRejected(rejection)
                    return null
                }
            }
        }
        if (!isCurrentAttempt()) {
            onStale(opened)
            throw LockdownPairingException.Cancelled
        }
        return retained to opened
    }
}
