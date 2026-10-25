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
