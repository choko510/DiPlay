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

    @Test fun previewCancelUndoAndSaveRemainSeparate() {
        val repo = DspStudioWorkspaceRepository(temporaryFolder.root)
        val coordinator = DspStudioSessionCoordinator(workspace(), repo)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 80)) }
        assertTrue(coordinator.isDirty)
        assertEquals(80, coordinator.preview.macros.bassImpact)
        assertEquals(50, coordinator.compareA().macros.bassImpact)
        assertEquals(80, coordinator.compareB().macros.bassImpact)
        coordinator.cancelPreview()
        assertFalse(coordinator.isDirty)
        assertEquals(50, coordinator.preview.macros.bassImpact)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 61)) }
        assertTrue(coordinator.undo())
        assertEquals(50, coordinator.preview.macros.bassImpact)
        coordinator.edit { it.copy(macros = it.macros.copy(bassImpact = 62)) }
        assertEquals(DspWorkspaceSaveResult.SAVED, coordinator.save())
        assertFalse(coordinator.isDirty)
        assertEquals(62, (repo.load() as DspWorkspaceReadResult.Loaded).workspace.macros.bassImpact)
    }
}
