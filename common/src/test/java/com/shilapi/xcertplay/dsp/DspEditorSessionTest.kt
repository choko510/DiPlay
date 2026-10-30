package com.shilapi.xcertplay.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEditorSessionTest {
    private val applied = DspProfilePresets.find("flat")!!

    @Test
    fun editsMadeDuringApplyRemainAsAnUnappliedDraft() {
        val session = session()
        val operation = requireNotNull(session.beginOperation(DspEditorOperationKind.APPLY))
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
    fun pendingOperationCannotBeReplacedByAnotherOperation() {
        val session = session()
        val import = requireNotNull(session.beginOperation(DspEditorOperationKind.IMPORT))

        assertNull(session.beginOperation(DspEditorOperationKind.MASTER))
        assertEquals(DspEditorOperation(import.id, DspEditorOperationKind.IMPORT), session.pendingOperation)
        assertTrue(
            session.finishOperation(
                import,
                currentAppliedProfile = applied,
                currentMasterEnabled = false,
            ),
        )
        val master = requireNotNull(session.beginOperation(DspEditorOperationKind.MASTER))
        assertTrue(session.isCurrent(master))
    }

    @Test
    fun importIsAdoptedOnlyForItsCurrentUneditedDraftAndVisibleScreen() {
        val session = session()
        val operation = requireNotNull(session.beginOperation(DspEditorOperationKind.IMPORT))

        assertTrue(session.canAdoptImport(operation, "flat", "flat", screenActive = true))
        assertFalse(session.canAdoptImport(operation, "flat", "custom1", screenActive = true))
        assertFalse(session.canAdoptImport(operation, "flat", "flat", screenActive = false))
        session.draftProfile = applied.copy(preampDb = 1.0)
        assertFalse(session.canAdoptImport(operation, "flat", "flat", screenActive = true))
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

    @Test
    fun runtimeRefreshAdoptsAppliedProfileWhenDraftWasClean() {
        val session = session()
        val runtimeProfile = applied.copy(preampDb = 1.5)

        session.refreshFromRuntime(runtimeProfile, currentMasterEnabled = true)

        assertEquals(runtimeProfile, session.appliedProfile)
        assertEquals(runtimeProfile, session.draftProfile)
        assertFalse(session.hasUnappliedChanges)
        assertTrue(session.masterEnabled)
    }

    private fun session() = DspEditorSession(
        initialDraft = applied,
        initialAppliedProfile = applied,
        initialMasterEnabled = false,
    )
}
