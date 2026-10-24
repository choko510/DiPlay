package com.shilapi.xcertplay.dsp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState

internal enum class DspEditorOperationKind {
    APPLY,
    SAVE,
    IMPORT,
    MASTER,
}

internal data class DspEditorOperationToken(
    val id: Long,
    val draftRevision: Long,
)

internal data class DspEditorOperation(
    val id: Long,
    val kind: DspEditorOperationKind,
)

internal class DspEditorSession(
    initialDraft: DspAudioProfile,
    initialAppliedProfile: DspAudioProfile,
    initialMasterEnabled: Boolean,
) {
    private val draft = mutableStateOf(initialDraft)
    private val applied = mutableStateOf(initialAppliedProfile)
    private val masterEnabledState = mutableStateOf(initialMasterEnabled)
    private val pendingState = mutableStateOf<DspEditorOperation?>(null)
    private var nextOperationId = 0L
    private var draftRevision = 0L

    val draftProfileState: MutableState<DspAudioProfile> = object : MutableState<DspAudioProfile> {
        override var value: DspAudioProfile
            get() = draft.value
            set(value) {
                if (draft.value != value) {
                    draft.value = value
                    draftRevision += 1
                }
            }

        override fun component1(): DspAudioProfile = value

        override fun component2(): (DspAudioProfile) -> Unit = { value = it }
    }

    val masterEnabledMutableState: MutableState<Boolean>
        get() = masterEnabledState

    var draftProfile: DspAudioProfile
        get() = draft.value
        set(value) {
            draftProfileState.value = value
        }

    val appliedProfile: DspAudioProfile
        get() = applied.value

    var masterEnabled: Boolean
        get() = masterEnabledState.value
        set(value) {
            masterEnabledState.value = value
        }

    val pendingOperation: DspEditorOperation?
        get() = pendingState.value

    val hasUnappliedChanges: Boolean
        get() = draftProfile != appliedProfile

    fun beginOperation(kind: DspEditorOperationKind): DspEditorOperationToken? {
        if (pendingState.value != null) return null
        nextOperationId += 1
        pendingState.value = DspEditorOperation(nextOperationId, kind)
        return DspEditorOperationToken(nextOperationId, draftRevision)
    }

    fun isCurrent(token: DspEditorOperationToken): Boolean =
        pendingState.value?.id == token.id

    fun finishOperation(
        token: DspEditorOperationToken,
        currentAppliedProfile: DspAudioProfile,
        currentMasterEnabled: Boolean,
        acceptAppliedDraft: Boolean = false,
    ): Boolean {
        if (!isCurrent(token)) return false
        applied.value = currentAppliedProfile
        masterEnabled = currentMasterEnabled
        if (acceptAppliedDraft && draftRevision == token.draftRevision) {
            draftProfile = currentAppliedProfile
        }
        pendingState.value = null
        return true
    }

    fun refreshFromRuntime(
        currentAppliedProfile: DspAudioProfile,
        currentMasterEnabled: Boolean,
    ) {
        val previousApplied = applied.value
        if (previousApplied != currentAppliedProfile) {
            applied.value = currentAppliedProfile
            if (draftProfile == previousApplied) draftProfile = currentAppliedProfile
        }
        masterEnabled = currentMasterEnabled
    }

    fun restoreDraft(profile: DspAudioProfile) {
        draftProfile = profile
    }
}

internal object DspEditorSavedState {
    const val DRAFT_PROFILE = "dsp.editor.draft_profile"
}
