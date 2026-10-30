package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

internal class DspCustomProfileWorkspaceSave(
    filesDirectory: File,
    private val profileRepository: DspProfileRepository,
    private val workspaceRepository: DspStudioWorkspaceRepository,
) {
    private val filesRoot = filesDirectory.canonicalFile
    internal var afterWorkspaceSavedForTesting: (() -> Unit)? = null

    fun save(
        profile: DspAudioProfile,
        workspace: DspStudioWorkspace,
        beforeCommit: () -> Boolean = { true },
    ): DspProfileSaveResult {
        if (!recoverPending()) {
            return if (workspaceRepository.load() is DspWorkspaceReadResult.FutureSchema) {
                DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
            } else {
                DspProfileSaveResult.WRITE_FAILED
            }
        }
        if (profile.id !in CUSTOM_PROFILE_IDS) return DspProfileSaveResult.INVALID_PROFILE_ID
        if (!profile.hasValidEqCapacity()) return DspProfileSaveResult.INVALID_EQ_CAPACITY
        if (workspace.profileSourceId != profile.id || workspace.baseProfileSnapshot != profile) {
            return DspProfileSaveResult.WRITE_FAILED
        }
        when (workspaceRepository.load()) {
            is DspWorkspaceReadResult.FutureSchema -> return DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
            DspWorkspaceReadResult.Invalid -> return DspProfileSaveResult.WRITE_FAILED
            is DspWorkspaceReadResult.Loaded, DspWorkspaceReadResult.NotFound -> Unit
        }
        when (profileRepository.load(profile.id)) {
            is DspProfileReadResult.FutureSchema -> return DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
            DspProfileReadResult.Invalid -> return DspProfileSaveResult.WRITE_FAILED
            is DspProfileReadResult.Loaded, DspProfileReadResult.NotFound -> Unit
        }
        val previousWorkspace = runCatching { workspaceRepository.snapshotBytesForTransaction() }
            .getOrElse { return DspProfileSaveResult.WRITE_FAILED }
        val record = Record(
            state = STATE_COMMIT,
            profile = profile,
            workspace = workspace,
            previousWorkspace = previousWorkspace,
        )
        if (!writeRecord(record)) return DspProfileSaveResult.WRITE_FAILED
        if (!beforeCommit()) {
            return if (rollback(record)) DspProfileSaveResult.STALE else DspProfileSaveResult.WRITE_FAILED
        }
        val workspaceResult = workspaceRepository.save(workspace, beforeCommit = beforeCommit)
        if (workspaceResult != DspWorkspaceSaveResult.SAVED) {
            return if (rollback(record)) workspaceResult.toProfileSaveResult() else DspProfileSaveResult.WRITE_FAILED
        }
        afterWorkspaceSavedForTesting?.invoke()
        val profileResult = profileRepository.save(profile)
        if (profileResult != DspProfileSaveResult.SAVED) {
            return if (rollback(record)) profileResult else DspProfileSaveResult.WRITE_FAILED
        }
        deleteRecord()
        return DspProfileSaveResult.SAVED
    }

    fun recoverPending(): Boolean {
        if (!safeJournalStorage()) return false
        val file = journalFile() ?: return false
        val backup = File(file.path + ".bak")
        if (!DspStoragePaths.entryExists(file) && !DspStoragePaths.entryExists(backup)) return true
        val record = readRecord() ?: return false
        if (record.state == STATE_ROLLBACK) return restorePreviousWorkspace(record)
        if (workspaceRepository.load() is DspWorkspaceReadResult.FutureSchema) return false
        val workspaceResult = workspaceRepository.save(record.workspace)
        if (workspaceResult != DspWorkspaceSaveResult.SAVED) {
            return if (workspaceResult == DspWorkspaceSaveResult.FUTURE_SCHEMA_PRESERVED) {
                false
            } else {
                rollback(record)
            }
        }
        val profileResult = profileRepository.save(record.profile)
        return if (profileResult == DspProfileSaveResult.SAVED) {
            deleteRecord()
        } else {
            rollback(record)
        }
    }

    private fun rollback(record: Record): Boolean {
        if (!writeRecord(record.copy(state = STATE_ROLLBACK))) return false
        return restorePreviousWorkspace(record)
    }

    private fun restorePreviousWorkspace(record: Record): Boolean {
        if (workspaceRepository.load() !is DspWorkspaceReadResult.FutureSchema &&
            !workspaceRepository.restoreBytesForTransaction(record.previousWorkspace)
        ) {
            return false
        }
        return deleteRecord()
    }

    private fun writeRecord(record: Record): Boolean {
        if (!safeJournalStorage()) return false
        val file = journalFile() ?: return false
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val json = JSONObject()
            .put(KEY_SCHEMA, JOURNAL_SCHEMA)
            .put(KEY_STATE, record.state)
            .put(KEY_PROFILE, profileRepository.encodeDraftForSavedState(record.profile))
            .put(KEY_WORKSPACE, workspaceRepository.encodeForTransaction(record.workspace))
            .put(KEY_PREVIOUS_WORKSPACE, record.previousWorkspace?.let(Base64.getEncoder()::encodeToString) ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_JOURNAL_BYTES) return false
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(atomic::failWrite)
            false
        }
    }

    private fun readRecord(): Record? = try {
        val file = journalFile() ?: return null
        val backup = File(file.path + ".bak")
        if (!DspStoragePaths.entryExists(file) && !DspStoragePaths.entryExists(backup)) return null
        val bytes = AtomicFile(file).openRead().use { input -> input.readBytes() }
        if (bytes.size > MAX_JOURNAL_BYTES) return null
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        if (json.getInt(KEY_SCHEMA) != JOURNAL_SCHEMA) return null
        val profile = profileRepository.decodeDraftFromSavedState(json.getString(KEY_PROFILE)) ?: return null
        val workspace = workspaceRepository.decodeForTransaction(json.getString(KEY_WORKSPACE)) ?: return null
        if (workspace.profileSourceId != profile.id || workspace.baseProfileSnapshot != profile) return null
        val previousWorkspace = if (json.isNull(KEY_PREVIOUS_WORKSPACE)) {
            null
        } else {
            Base64.getDecoder().decode(json.getString(KEY_PREVIOUS_WORKSPACE))
        }
        val state = json.getString(KEY_STATE)
        if (state != STATE_COMMIT && state != STATE_ROLLBACK) return null
        Record(state, profile, workspace, previousWorkspace)
    } catch (_: Exception) {
        null
    }

    private fun deleteRecord(): Boolean = runCatching {
        val file = journalFile() ?: return false
        AtomicFile(file).delete()
        !DspStoragePaths.entryExists(file) && !DspStoragePaths.entryExists(File(file.path + ".bak"))
    }.getOrDefault(false)

    private fun safeJournalStorage(): Boolean = runCatching {
        val directory = DspStoragePaths.directoryUnder(filesRoot, "dsp", "workspace") ?: return false
        val file = DspStoragePaths.fileIn(directory, JOURNAL_NAME) ?: return false
        val backup = DspStoragePaths.fileIn(directory, "$JOURNAL_NAME.bak") ?: return false
        listOf(file, backup).all { candidate ->
            !DspStoragePaths.entryExists(candidate) ||
                candidate.isFile && candidate.canonicalFile.parentFile == directory.canonicalFile
        }
    }.getOrDefault(false)

    private fun journalFile(): File? = DspStoragePaths.directoryUnder(filesRoot, "dsp", "workspace")
        ?.let { DspStoragePaths.fileIn(it, JOURNAL_NAME) }

    private data class Record(
        val state: String,
        val profile: DspAudioProfile,
        val workspace: DspStudioWorkspace,
        val previousWorkspace: ByteArray?,
    )

    private fun DspWorkspaceSaveResult.toProfileSaveResult(): DspProfileSaveResult = when (this) {
        DspWorkspaceSaveResult.STALE -> DspProfileSaveResult.STALE
        DspWorkspaceSaveResult.FUTURE_SCHEMA_PRESERVED -> DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
        DspWorkspaceSaveResult.INVALID,
        DspWorkspaceSaveResult.WRITE_FAILED -> DspProfileSaveResult.WRITE_FAILED
        DspWorkspaceSaveResult.SAVED -> DspProfileSaveResult.SAVED
    }

    private companion object {
        const val JOURNAL_SCHEMA = 1
        const val JOURNAL_NAME = "custom-profile-save.json"
        const val MAX_JOURNAL_BYTES = 3 * 1024 * 1024
        const val KEY_SCHEMA = "transactionSchema"
        const val KEY_STATE = "state"
        const val KEY_PROFILE = "profile"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_PREVIOUS_WORKSPACE = "previousWorkspace"
        const val STATE_COMMIT = "COMMIT"
        const val STATE_ROLLBACK = "ROLLBACK"
        val CUSTOM_PROFILE_IDS = setOf("custom1", "custom2")
    }
}
