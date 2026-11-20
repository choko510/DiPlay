package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * Persists what a confirmed CarKit connection proved, and nothing else.
 *
 * A retained record is stored only when promotion reports success: a promotion that cannot be
 * confirmed leaves the record aside, and the recency update that would otherwise follow is skipped
 * rather than applied to a copy the store does not hold.
 *
 * A retained record and a newly paired record are mutually exclusive. Only one of them can be the
 * record CarKit accepted, so a caller holding both has lost track of which connection succeeded.
 */
internal object WiredLockdownConfirmation {
    /**
     * @return true when the accepted record is now in the store, false when a promoted record could
     * not be confirmed.
     */
    fun confirm(
        acceptedRecord: LockdownPairRecord,
        recordToPromote: LockdownPairRecord?,
        pairedNewRecord: LockdownPairRecord?,
        promote: (LockdownPairRecord) -> Boolean,
        save: (LockdownPairRecord) -> Unit,
        markUsed: (devicePublicKeyPem: ByteArray) -> Unit,
        onPromotionFailed: () -> Unit = {},
    ): Boolean {
        require(recordToPromote == null || pairedNewRecord == null) {
            "a retained record and a newly paired record cannot both be pending"
        }
        if (recordToPromote != null) {
            if (!promote(recordToPromote)) {
                onPromotionFailed()
                return false
            }
        } else {
            pairedNewRecord?.let(save)
        }
        markUsed(acceptedRecord.devicePublicKeyPem)
        return true
    }
}
