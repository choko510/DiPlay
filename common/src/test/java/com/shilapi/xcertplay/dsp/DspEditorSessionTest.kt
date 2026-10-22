package com.shilapi.xcertplay.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEditorSessionTest {
    private val applied = DspProfilePresets.find("flat")!!

    @Test
    fun editsMadeDuringApplyRemainAsAnUnappliedDraft() {
        val session = session()
        val operation = session.beginOperation(DspEditorOperationKind.APPLY)
        val newerDraft = applied.copy(preampDb = 2.0)
        session.draftProfile = newerDraft

        assertTrue(
            session.finishOperation(
                operation,
                currentAppliedProfile = applied.copy(preampDb = 1.0),
                currentMasterEnabled = false,
                acceptAppliedDraft = true,
            ),
        )
        assertEquals(newerDraft, session.draftProfile)
        assertEquals(applied.copy(preampDb = 1.0), session.appliedProfile)
        assertTrue(session.hasUnappliedChanges)
    }

    @Test
    fun onlyTheNewestAsyncOperationMayUpdateEditorState() {
        val session = session()
        val older = session.beginOperation(DspEditorOperationKind.SAVE)
        val newest = session.beginOperation(DspEditorOperationKind.APPLY)

        assertFalse(
            session.finishOperation(
                older,
                currentAppliedProfile = applied.copy(preampDb = 1.0),
                currentMasterEnabled = true,
                acceptAppliedDraft = true,
            ),
        )
        assertTrue(
            session.finishOperation(
                newest,
                currentAppliedProfile = applied.copy(preampDb = 2.0),
                currentMasterEnabled = true,
                acceptAppliedDraft = true,
            ),
        )
        assertEquals(applied.copy(preampDb = 2.0), session.draftProfile)
        assertEquals(applied.copy(preampDb = 2.0), session.appliedProfile)
        assertFalse(session.hasUnappliedChanges)
    }

    @Test
    fun externalRuntimeChangesDoNotOverwriteADirtyDraft() {
        val session = session()
        val draft = applied.copy(preampDb = -3.0)
        session.draftProfile = draft

        session.refreshFromRuntime(applied.copy(preampDb = 1.0), currentMasterEnabled = false)

        assertEquals(draft, session.draftProfile)
        assertEquals(applied.copy(preampDb = 1.0), session.appliedProfile)
        assertTrue(session.hasUnappliedChanges)
        assertFalse(session.masterEnabled)
    }

    private fun session() = DspEditorSession(
        initialDraft = applied,
        initialAppliedProfile = applied,
        initialMasterEnabled = false,
    )
}
