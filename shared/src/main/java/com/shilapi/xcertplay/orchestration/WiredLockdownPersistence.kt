package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * What one wiring attempt will store once CarKit accepts the connection.
 *
 * The record in use and the record that still has to be stored are decided together, because the
 * two can drift apart: a connection that started on a retained copy and then fell back to a new
 * Pair must store the new record, not the retained copy that Lockdown just rejected.
 */
internal class WiredLockdownPersistence {
    private var recordToPromote: LockdownPairRecord? = null
    private var pairedNewRecord: LockdownPairRecord? = null

    /** The attempt starts on the record the lookup chose. */
    fun usingChosenRecord(choice: WiredLockdownRecordChoice) {
        recordToPromote = choice.record.takeIf { choice.promotableOnAcceptance }
        pairedNewRecord = null
    }

    /** The retained copy opened after the stored copy was rejected; it has to be stored on success. */
    fun usingRetainedRecord(record: LockdownPairRecord) {
        recordToPromote = record
        pairedNewRecord = null
    }

    /** A Pair produced a new record, so anything that was waiting to be promoted is void. */
    fun usingPairedRecord(record: LockdownPairRecord) {
        recordToPromote = null
        pairedNewRecord = record
    }

    fun confirm(
        acceptedRecord: LockdownPairRecord,
        promote: (LockdownPairRecord) -> Boolean,
        save: (LockdownPairRecord) -> Unit,
        markUsed: (devicePublicKeyPem: ByteArray) -> Unit,
        onPromotionFailed: () -> Unit = {},
    ): Boolean = WiredLockdownConfirmation.confirm(
        acceptedRecord = acceptedRecord,
        recordToPromote = recordToPromote,
        pairedNewRecord = pairedNewRecord,
        promote = promote,
        save = save,
        markUsed = markUsed,
        onPromotionFailed = onPromotionFailed,
    )
}
