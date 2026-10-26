package com.shilapi.xcertplay.dsp

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspStudioPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun workspace() = DspStudioWorkspace(
        "flat", DspProfilePresets.find("flat")!!,
        macros = DspSoundMacroSettings(true, 67, 54, 43, 72),
        smartLoudness = DspSmartLoudnessSettings(mode = DspSmartLoudnessMode.MANUAL_ESTIMATE, manualListeningLevel = .3),
        dynamicBass = DspDynamicBassSettings(enabled = true, strength = .7),
        selectedModulePresetIds = mapOf("bass" to "abc123"),
    )

    @Test fun workspaceRoundTripsCompleteProfileAndEnhancementState() {
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root)
        assertEquals(DspWorkspaceSaveResult.SAVED, repo.save(workspace()))
        val loaded = repo.load() as DspWorkspaceReadResult.Loaded
        assertEquals(workspace(), loaded.workspace)
    }

    @Test fun workspaceSourceIdMustMatchSnapshotIdOnLoadAndSave() {
        val target = File(temporaryFolder.root, "dsp/workspace/current.json")
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root)
        assertEquals(DspWorkspaceSaveResult.SAVED, repo.save(workspace()))
        target.writeText(JSONObject(target.readText()).put("baseProfileSourceId", "vocal").toString())

        assertEquals(DspWorkspaceReadResult.Invalid, repo.load())
        assertEquals(DspWorkspaceSaveResult.INVALID, repo.save(workspace().copy(profileSourceId = "vocal")))
    }

    @Test fun futureWorkspaceIsPreservedOnSave() {
        val target = File(temporaryFolder.root, "dsp/workspace/current.json")
        target.parentFile!!.mkdirs()
        val future = "{\"workspaceSchema\":99,\"sentinel\":\"keep\"}"
        target.writeText(future)
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root)
        assertEquals(DspWorkspaceReadResult.FutureSchema(99), repo.load())
        assertEquals(DspWorkspaceSaveResult.FUTURE_SCHEMA_PRESERVED, repo.save(workspace()))
        assertEquals(future, target.readText())
    }

    @Test fun failedAtomicWriteDoesNotReportSuccess() {
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root) { _, _ -> error("disk full") }
        assertEquals(DspWorkspaceSaveResult.WRITE_FAILED, repo.save(workspace()))
    }

    @Test fun maliciousPresetIdIsRejectedBeforeFilesystemCreation() {
        val root = temporaryFolder.newFolder("presets")
        val repo = DspModulePresetRepository(root)
        val result = repo.save(DspModulePreset("../escape", "bass", "Bad", JSONObject().put("enabled", true).put("gainDb", 1).put("frequencyHz", 80.0)))
        assertEquals(DspPresetStoreResult.INVALID, result)
        assertFalse(File(root, "dsp").exists())
        assertNull(repo.load("bass", "../escape"))
    }

    @Test fun futureModulePresetIsPreservedAndFactoryPresetCannotBeOverwritten() {
        val root = temporaryFolder.root
        val file = File(root, "dsp/modules/bass/future.json")
        file.parentFile!!.mkdirs()
        val future = "{\"schemaVersion\":8,\"sentinel\":true}"
        file.writeText(future)
        val repo = DspModulePresetRepository(root)
        val replacement = DspModulePreset("future", "bass", "Replacement", JSONObject().put("enabled", true).put("gainDb", 1.0).put("frequencyHz", 80.0))
        assertEquals(DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED, repo.save(replacement))
        assertEquals(future, file.readText())
        assertEquals(DspPresetStoreResult.INVALID, repo.save(DspFactoryPresets.modulePresets().first()))
    }

    @Test fun userModulePresetCanBeDeleted() {
        val root = temporaryFolder.root
        val repo = DspModulePresetRepository(root)
        val preset = DspModulePreset(
            "user_bass_night",
            "bass",
            "Night",
            JSONObject().put("enabled", true).put("gainDb", 2.0).put("frequencyHz", 80.0),
        )

        assertEquals(DspPresetStoreResult.SAVED, repo.save(preset))
        assertEquals(preset.id, repo.load("bass", preset.id)?.id)
        assertEquals(DspPresetStoreResult.DELETED, repo.deleteUserPreset("bass", preset.id))
        assertNull(repo.load("bass", preset.id))
        assertEquals(DspPresetStoreResult.NOT_FOUND, repo.deleteUserPreset("bass", preset.id))
    }

    @Test fun factoryPresetIdCannotBeDeletedEvenIfAFileExists() {
        val root = temporaryFolder.root
        val factory = DspFactoryPresets.modulePresets().first { it.moduleKey == "bass" }
        val target = File(root, "dsp/modules/${factory.moduleKey}/${factory.id}.json")
        target.parentFile!!.mkdirs()
        target.writeText("{\"schemaVersion\":1,\"id\":\"${factory.id}\",\"moduleKey\":\"bass\",\"name\":\"forged\",\"origin\":\"USER\",\"payloadVersion\":1,\"payload\":{\"enabled\":true,\"gainDb\":2.0,\"frequencyHz\":80.0}}")
        val repo = DspModulePresetRepository(root)

        assertEquals(DspPresetStoreResult.INVALID, repo.deleteUserPreset("bass", factory.id))
        assertTrue(target.exists())
    }

    @Test fun deletingFutureModulePresetPreservesItsBytes() {
        val root = temporaryFolder.root
        val file = File(root, "dsp/modules/bass/future_delete.json")
        file.parentFile!!.mkdirs()
        val future = "{\"schemaVersion\":99,\"sentinel\":\"keep\"}"
        file.writeText(future)
        val repo = DspModulePresetRepository(root)

        assertEquals(DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED, repo.deleteUserPreset("bass", "future_delete"))
        assertEquals(future, file.readText())
    }

    @Test fun applyingBassPresetChangesOnlyBassModule() {
        val profile = DspProfilePresets.find("vocal")!!
        val preset = DspModulePreset("factory-bass", "bass", "Factory", JSONObject().put("enabled", true).put("gainDb", 2.5).put("frequencyHz", 90.0), factory = true)
        val applied = DspModulePresetRepository(temporaryFolder.root).applyTo(profile, preset)
        assertNotNull(applied)
        assertEquals(profile.eqBands, applied!!.eqBands)
        assertEquals(profile.compressor, applied.compressor)
        assertEquals(profile.bass.copy(enabled = true, gainDb = 2.5, frequencyHz = 90.0), applied.bass)
        assertNull(DspModulePresetRepository(temporaryFolder.root).applyTo(profile, preset.copy(moduleKey = "limiter")))
    }

    @Test fun enabledConvolverPresetRequiresAnExistingIrReference() {
        val preset = DspModulePreset("ir-ref", "convolver", "IR", JSONObject().put("enabled", true).put("impulseResponseId", "safe-ir-id").put("wet", .5))
        val rejected = DspModulePresetRepository(temporaryFolder.root, irReferenceExists = { false })
        assertEquals(DspPresetStoreResult.INVALID, rejected.save(preset))
        val accepted = DspModulePresetRepository(temporaryFolder.root, irReferenceExists = { it == "safe-ir-id" })
        assertEquals(DspPresetStoreResult.SAVED, accepted.save(preset))
        assertNotNull(accepted.load("convolver", preset.id))
    }

    @Test fun everyFactoryModulePayloadValidatesAndAppliesToItsScope() {
        val repo = DspModulePresetRepository(temporaryFolder.root)
        val workspace = workspace()
        val profile = workspace.baseProfileSnapshot
        val presets = DspFactoryPresets.modulePresets()
        assertEquals(14, presets.size)
        presets.forEach { factory ->
            val userPreset = factory.copy(id = "user-${factory.moduleKey}", factory = false)
            assertEquals("${factory.moduleKey} store", DspPresetStoreResult.SAVED, repo.save(userPreset))
            val loaded = repo.load(factory.moduleKey, userPreset.id)
            assertNotNull("${factory.moduleKey} load", loaded)
            val loadedPreset = requireNotNull(loaded)
            if (factory.moduleKey in setOf("macros", "smart_loudness", "dynamic_bass")) {
                val applied = repo.applyTo(workspace, loadedPreset)
                assertNotNull("${factory.moduleKey} workspace apply", applied)
                assertEquals(workspace.baseProfileSnapshot, applied!!.baseProfileSnapshot)
                when (factory.moduleKey) {
                    "macros" -> { assertEquals(workspace.smartLoudness, applied.smartLoudness); assertEquals(workspace.dynamicBass, applied.dynamicBass) }
                    "smart_loudness" -> { assertEquals(workspace.macros, applied.macros); assertEquals(workspace.dynamicBass, applied.dynamicBass) }
                    "dynamic_bass" -> { assertEquals(workspace.macros, applied.macros); assertEquals(workspace.smartLoudness, applied.smartLoudness) }
                }
            } else {
                val applied = repo.applyTo(profile, loadedPreset)
                assertNotNull("${factory.moduleKey} profile apply", applied)
                val unchanged = profile.copy(
                    eqBands = if (factory.moduleKey == "peq") applied!!.eqBands else profile.eqBands,
                    bass = if (factory.moduleKey == "bass") applied!!.bass else profile.bass,
                    seatAlignment = if (factory.moduleKey == "seat") applied!!.seatAlignment else profile.seatAlignment,
                    advancedMs = if (factory.moduleKey == "advanced_ms") applied!!.advancedMs else profile.advancedMs,
                    compressor = if (factory.moduleKey == "compressor") applied!!.compressor else profile.compressor,
                    multiband = if (factory.moduleKey == "multiband") applied!!.multiband else profile.multiband,
                    dynamicEq = if (factory.moduleKey == "dynamic_eq") applied!!.dynamicEq else profile.dynamicEq,
                    harmonic = if (factory.moduleKey == "harmonic") applied!!.harmonic else profile.harmonic,
                    limiter = if (factory.moduleKey == "limiter") applied!!.limiter else profile.limiter,
                    convolver = if (factory.moduleKey == "convolver") applied!!.convolver else profile.convolver,
                    cabinCorrection = if (factory.moduleKey == "cabin") applied!!.cabinCorrection else profile.cabinCorrection,
                )
                assertEquals("${factory.moduleKey} locality", applied, unchanged)
                val workspaceApplied = repo.applyTo(workspace, loadedPreset)
                assertNotNull("${factory.moduleKey} workspace apply", workspaceApplied)
                assertEquals(applied, workspaceApplied!!.baseProfileSnapshot)
                assertEquals(workspace.macros, workspaceApplied.macros)
                assertEquals(workspace.smartLoudness, workspaceApplied.smartLoudness)
                assertEquals(workspace.dynamicBass, workspaceApplied.dynamicBass)
                assertEquals(workspace.selectedModulePresetIds, workspaceApplied.selectedModulePresetIds)
            }
        }
    }

    @Test fun previewCancelUndoAndSaveRemainSeparate() {
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root)
        val coordinator = DspStudioSessionCoordinator(workspace(), repo)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        assertTrue(coordinator.isDirty)
        assertEquals(80, coordinator.preview.macros.bassImpact)
        assertEquals(67, coordinator.compareA().macros.bassImpact)
        assertEquals(80, coordinator.compareB().macros.bassImpact)
        coordinator.cancelPreview()
        assertFalse(coordinator.isDirty)
        assertEquals(67, coordinator.preview.macros.bassImpact)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 61)) }
        assertTrue(coordinator.undo())
        assertEquals(67, coordinator.preview.macros.bassImpact)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 62)) }
        assertEquals(DspWorkspaceSaveResult.SAVED, coordinator.save())
        assertFalse(coordinator.isDirty)
        assertEquals(62, (repo.load() as DspWorkspaceReadResult.Loaded).workspace.macros.bassImpact)
    }

    @Test fun oneEditTransactionCreatesOneUndoEntryForManyUpdates() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        val transaction = requireNotNull(coordinator.beginEditTransaction())

        repeat(100) { index ->
            assertNotNull(
                coordinator.edit(transaction) {
                    it.copy(macros = it.macros.copy(bassImpact = index + 1))
                },
            )
        }
        assertFalse(coordinator.canUndo)
        assertEquals(100, coordinator.preview.macros.bassImpact)

        assertTrue(coordinator.commitEditTransaction(transaction))
        assertTrue(coordinator.canUndo)
        assertTrue(coordinator.undo())
        assertEquals(67, coordinator.preview.macros.bassImpact)
        assertFalse(coordinator.canUndo)
    }

    @Test fun noOpEditDoesNotCreateHistoryOrClearRedo() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        assertTrue(coordinator.undo())

        val generation = coordinator.generation()
        coordinator.edit { it }

        assertEquals(generation, coordinator.generation())
        assertFalse(coordinator.canUndo)
        assertTrue(coordinator.canRedo)
        assertTrue(coordinator.redo())
        assertEquals(80, coordinator.preview.macros.bassImpact)
    }

    @Test fun staleEditTransactionCannotChangeTheDraft() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        val stale = requireNotNull(coordinator.beginEditTransaction())
        assertTrue(coordinator.commitEditTransaction(stale))
        val current = requireNotNull(coordinator.beginEditTransaction())

        assertNull(coordinator.edit(stale) { it.copy(macros = it.macros.copy(bassImpact = 20)) })
        assertFalse(coordinator.commitEditTransaction(stale))
        assertEquals(67, coordinator.preview.macros.bassImpact)
        assertTrue(coordinator.commitEditTransaction(current))
    }

    @Test fun moduleComparisonFreezesOnlyThatModuleAgainstTheCurrentDraftBase() {
        val saved = workspace().copy(
            baseProfileSnapshot = workspace().baseProfileSnapshot.copy(
                bass = workspace().baseProfileSnapshot.bass.copy(enabled = true, gainDb = 2.0),
                compressor = workspace().baseProfileSnapshot.compressor.copy(thresholdDb = -24.0),
                preampDb = 1.0,
            ),
            selectedModulePresetIds = mapOf("bass" to "saved-bass", "compressor" to "saved-compressor"),
        )
        val coordinator = DspStudioSessionCoordinator(saved, DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit {
            it.copy(
                baseProfileSnapshot = it.baseProfileSnapshot.copy(
                    bass = it.baseProfileSnapshot.bass.copy(gainDb = 8.0),
                    compressor = it.baseProfileSnapshot.compressor.copy(thresholdDb = -12.0),
                    preampDb = 3.0,
                ),
                macros = it.macros.copy(bassImpact = 90),
                selectedModulePresetIds = mapOf("bass" to "draft-bass", "compressor" to "draft-compressor"),
            )
        }

        val comparison = requireNotNull(coordinator.compareModule("bass"))

        assertEquals("bass", comparison.scopeKey)
        assertEquals(2.0, comparison.a.baseProfileSnapshot.bass.gainDb, 0.0)
        assertEquals(8.0, comparison.b.baseProfileSnapshot.bass.gainDb, 0.0)
        assertEquals(-12.0, comparison.a.baseProfileSnapshot.compressor.thresholdDb, 0.0)
        assertEquals(90, comparison.a.macros.bassImpact)
        assertEquals(3.0, comparison.a.baseProfileSnapshot.preampDb, 0.0)
        assertEquals(3.0, comparison.b.baseProfileSnapshot.preampDb, 0.0)
        assertEquals("saved-bass", comparison.a.selectedModulePresetIds["bass"])
        assertEquals("draft-compressor", comparison.a.selectedModulePresetIds["compressor"])
        assertNull(coordinator.compareModule("unknown"))

        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 40)) }
        assertEquals(90, comparison.b.macros.bassImpact)
    }

    @Test fun selectingModuleComparisonSidesUsesFrozenWorkspacesAndEditingReturnsToDraft() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit {
            it.copy(baseProfileSnapshot = it.baseProfileSnapshot.copy(bass = it.baseProfileSnapshot.bass.copy(gainDb = 6.0)))
        }
        val frozenDraft = coordinator.preview
        val comparison = requireNotNull(coordinator.beginModuleComparison("bass"))

        assertEquals(DspStudioComparisonSide.DRAFT, coordinator.comparisonSide)
        assertEquals(comparison.a, coordinator.selectComparisonSide(DspStudioComparisonSide.A))
        assertEquals(DspStudioComparisonSide.A, coordinator.comparisonSide)
        assertEquals(comparison.b, coordinator.selectComparisonSide(DspStudioComparisonSide.B))
        assertEquals(DspStudioComparisonSide.B, coordinator.comparisonSide)
        assertEquals(frozenDraft, comparison.b)

        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 41)) }

        assertEquals(DspStudioComparisonSide.DRAFT, coordinator.comparisonSide)
        assertNull(coordinator.selectComparisonSide(DspStudioComparisonSide.A))
        assertEquals(41, coordinator.preview.macros.bassImpact)
    }

    @Test fun clearingDeletedPresetReferenceAlsoCleansUndoRedoAndTransactionSnapshots() {
        val presetId = "user_bass_deleted"
        val initial = workspace().copy(selectedModulePresetIds = mapOf("bass" to presetId))
        val coordinator = DspStudioSessionCoordinator(initial, DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        val transaction = requireNotNull(coordinator.beginEditTransaction())
        coordinator.edit(transaction) { it.copy(macros = it.macros.copy(bassImpact = 90)) }

        assertTrue(coordinator.clearSelectedPresetReference("bass", presetId))
        assertNull(coordinator.compareA().selectedModulePresetIds["bass"])
        assertNull(coordinator.preview.selectedModulePresetIds["bass"])
        assertTrue(coordinator.commitEditTransaction(transaction))

        assertTrue(coordinator.undo())
        assertNull(coordinator.preview.selectedModulePresetIds["bass"])
        assertTrue(coordinator.redo())
        assertNull(coordinator.preview.selectedModulePresetIds["bass"])
        assertFalse(coordinator.clearSelectedPresetReference("bass", presetId))
    }

    @Test fun cancelPreviewClearsRedoHistory() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        assertTrue(coordinator.undo())

        coordinator.cancelPreview()

        assertFalse(coordinator.redo())
        assertEquals(67, coordinator.preview.macros.bassImpact)
    }

    @Test fun commitSavedMovesTheBaselineAndClearsUndoHistory() {
        val coordinator = DspStudioSessionCoordinator(workspace(), DspStudioWorkspaceRepository(temporaryFolder.root))
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        val saved = coordinator.preview

        coordinator.commitSaved(saved)

        assertFalse(coordinator.isDirty)
        assertFalse(coordinator.canUndo)
        assertFalse(coordinator.canRedo)
        assertEquals(80, coordinator.compareA().macros.bassImpact)
        assertEquals(saved.macros.vocalPresence, coordinator.compareA().macros.vocalPresence)
    }

    @Test fun savingOlderWorkspaceKeepsUndoForNewerTransaction() {
        val initial = workspace()
        val coordinator = DspStudioSessionCoordinator(initial, DspStudioWorkspaceRepository(temporaryFolder.root))
        val transaction = requireNotNull(coordinator.beginEditTransaction())
        assertNotNull(coordinator.edit(transaction) { it.copy(macros = it.macros.copy(bassImpact = 80)) })

        coordinator.commitSaved(initial)

        assertTrue(coordinator.canUndo)
        assertTrue(coordinator.undo())
        assertEquals(initial, coordinator.preview)
        assertFalse(coordinator.isDirty)
    }

    @Test fun disabledFactoryConvolverDoesNotTurnJsonNullIntoAnIrId() {
        val preset = DspFactoryPresets.modulePresets().single { it.moduleKey == "convolver" }
        val profile = DspProfilePresets.find("flat")!!
        val applied = requireNotNull(DspModulePresetRepository(temporaryFolder.root).applyTo(profile, preset))

        assertNull(applied.convolver.impulseResponseId)
    }

    @Test fun savedUserModulePresetRoundTripsOnlyItsOwnSettings() {
        val repository = DspModulePresetRepository(temporaryFolder.root)
        val initial = workspace()
        val savedProfile = initial.baseProfileSnapshot.copy(
            preampDb = 4.0,
            bass = initial.baseProfileSnapshot.bass.copy(enabled = true, gainDb = 8.0),
        )
        val savedWorkspace = initial.copy(baseProfileSnapshot = savedProfile)

        assertEquals(
            DspPresetStoreResult.SAVED,
            repository.saveCurrent(savedWorkspace, "bass", "user-driver", "Driver bass"),
        )

        val preset = repository.userPresets("bass").single()
        assertEquals("Driver bass", preset.name)
        val targetProfile = savedProfile.copy(
            preampDb = -3.0,
            bass = savedProfile.bass.copy(gainDb = -5.0),
        )
        val applied = requireNotNull(repository.applyTo(initial.copy(baseProfileSnapshot = targetProfile), preset))

        assertEquals(8.0, applied.baseProfileSnapshot.bass.gainDb, 0.0)
        assertEquals(-3.0, applied.baseProfileSnapshot.preampDb, 0.0)
        assertEquals(initial.macros, applied.macros)
    }
}
