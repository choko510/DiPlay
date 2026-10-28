package com.shilapi.xcertplay.dsp

import android.app.Application
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionNotch
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspProfileRuntimeTest {
    @Test
    fun missingGlobalMasterPreferenceKeepsDspDisabled() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DspProfileRuntime.resetForTesting()

        val runtime = DspProfileRuntime.get(context)

        assertFalse(runtime.isDspEnabled())
        assertFalse(runtime.configProvider.snapshot().enabled)
        DspProfileRuntime.resetForTesting()
    }

    @Test
    fun studioFeatureFlagsPersistWithSafeDefaultsForUnimplementedWarmPaths() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        val defaults = DspStudioFeatureFlags.load(preferences)
        assertTrue(defaults.workspaceUiEnabled)
        assertTrue(defaults.macroOverlayEnabled)
        assertTrue(defaults.smartLoudnessEnabled)
        assertTrue(defaults.dynamicBassEnabled)
        assertTrue(defaults.nativeHotUpdatesEnabled)
        assertFalse(defaults.localWarmEnabled)
        assertFalse(defaults.irWarmBudgetEnabled)
        val disabled = defaults.disableEnhancedDsp()
        assertFalse(disabled.workspaceUiEnabled)
        assertFalse(disabled.macroOverlayEnabled)
        assertFalse(disabled.smartLoudnessEnabled)
        assertFalse(disabled.dynamicBassEnabled)
        assertFalse(disabled.nativeHotUpdatesEnabled)
        assertFalse(disabled.localWarmEnabled)
        assertFalse(disabled.irWarmBudgetEnabled)

        val persisted = defaults.copy(
            macroOverlayEnabled = false,
            nativeHotUpdatesEnabled = false,
            localWarmEnabled = true,
        )
        preferences.edit().also(persisted::writeTo).commit()

        assertEquals(persisted, DspStudioFeatureFlags.load(preferences))
        preferences.edit().clear().commit()
    }

    @Test
    fun disablingEnhancedStagesRollsBackAudioAndReenableLeavesDraftUncommitted() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        val profile = DspProfilePresets.find("flat")!!
        val committedWorkspace = DspStudioWorkspace(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 100),
            smartLoudness = DspSmartLoudnessSettings(mode = DspSmartLoudnessMode.FIXED),
            dynamicBass = DspDynamicBassSettings(enabled = true),
        )
        assertEquals(DspWorkspaceSaveResult.SAVED, DspStudioWorkspaceRepository(context.filesDir).save(committedWorkspace))
        val savedContents = workspaceFile.readText()
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, true)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .commit()
        val runtime = DspProfileRuntime.get(context)

        try {
            val session = runtime.createStudioSessionCoordinator()
            session.edit { it.copy(macros = it.macros.copy(bassImpact = 0)) }
            val previewResult = AtomicReference<Boolean?>()
            val previewComplete = CountDownLatch(1)
            runtime.previewStudioWorkspaceAsync(session.preview, enabled = true) {
                previewResult.set(it)
                previewComplete.countDown()
            }
            drainMainLooperUntil(previewComplete)
            assertEquals(true, previewResult.get())
            assertEquals(-2.0, runtime.configProvider.snapshot().macroTone.bassGainDb, 0.0)

            val disabled = runtime.currentStudioFeatureFlags().copy(
                macroOverlayEnabled = false,
                smartLoudnessEnabled = false,
                dynamicBassEnabled = false,
                nativeHotUpdatesEnabled = false,
            )
            val disableResult = AtomicReference<Boolean?>()
            val disableComplete = CountDownLatch(1)
            runtime.updateStudioFeatureFlagsAsync(disabled) {
                disableResult.set(it)
                disableComplete.countDown()
            }
            drainMainLooperUntil(disableComplete)
            assertEquals(true, disableResult.get())
            assertFalse(runtime.configProvider.snapshot().macroTone.enabled)
            assertFalse(runtime.configProvider.snapshot().smartLoudness.enabled)
            assertFalse(runtime.configProvider.snapshot().dynamicBass.enabled)
            assertFalse(runtime.configProvider.studioHotUpdatesEnabled())

            val reenabled = disabled.copy(
                macroOverlayEnabled = true,
                smartLoudnessEnabled = true,
                dynamicBassEnabled = true,
                nativeHotUpdatesEnabled = true,
                localWarmEnabled = true,
            )
            val enableResult = AtomicReference<Boolean?>()
            val enableComplete = CountDownLatch(1)
            runtime.updateStudioFeatureFlagsAsync(reenabled) {
                enableResult.set(it)
                enableComplete.countDown()
            }
            drainMainLooperUntil(enableComplete)

            assertEquals(true, enableResult.get())
            assertEquals(4.0, runtime.configProvider.snapshot().macroTone.bassGainDb, 0.0)
            assertTrue(runtime.configProvider.snapshot().smartLoudness.enabled)
            assertTrue(runtime.configProvider.snapshot().dynamicBass.enabled)
            assertTrue(runtime.configProvider.studioHotUpdatesEnabled())
            assertTrue(runtime.configProvider.studioLocalWarmEnabled())
            assertEquals(0, session.preview.macros.bassImpact)
            assertTrue(session.isDirty)
            assertEquals(savedContents, workspaceFile.readText())
            assertEquals(reenabled, DspStudioFeatureFlags.load(preferences))
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun failedFeatureReenableRestoresThePreviouslyActiveStudioPreview() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        val profile = DspProfilePresets.find("flat")!!
        val committedWorkspace = DspStudioWorkspace(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 100),
        )
        assertEquals(DspWorkspaceSaveResult.SAVED, DspStudioWorkspaceRepository(context.filesDir).save(committedWorkspace))
        val runtime = DspProfileRuntime.get(context)

        try {
            val session = runtime.createStudioSessionCoordinator()
            session.edit { it.copy(macros = it.macros.copy(bassImpact = 0)) }
            val previewResult = AtomicReference<Boolean?>()
            val previewComplete = CountDownLatch(1)
            runtime.previewStudioWorkspaceAsync(session.preview, enabled = true) {
                previewResult.set(it)
                previewComplete.countDown()
            }
            drainMainLooperUntil(previewComplete)
            assertEquals(true, previewResult.get())

            val disabled = runtime.currentStudioFeatureFlags().copy(
                macroOverlayEnabled = false,
                smartLoudnessEnabled = false,
                dynamicBassEnabled = false,
                nativeHotUpdatesEnabled = false,
            )
            val disableResult = AtomicReference<Boolean?>()
            val disableComplete = CountDownLatch(1)
            runtime.updateStudioFeatureFlagsAsync(disabled) {
                disableResult.set(it)
                disableComplete.countDown()
            }
            drainMainLooperUntil(disableComplete)
            assertEquals(true, disableResult.get())

            runtime.persistStudioFeatureFlagsForTest = { false }
            val reenabled = disabled.copy(
                macroOverlayEnabled = true,
                smartLoudnessEnabled = true,
                dynamicBassEnabled = true,
                nativeHotUpdatesEnabled = true,
            )
            val enableResult = AtomicReference<Boolean?>()
            val enableComplete = CountDownLatch(1)
            runtime.updateStudioFeatureFlagsAsync(reenabled) {
                enableResult.set(it)
                enableComplete.countDown()
            }
            drainMainLooperUntil(enableComplete)

            assertEquals(false, enableResult.get())
            assertEquals(disabled, runtime.currentStudioFeatureFlags())
            assertEquals(0, activeStudioWorkspace(runtime).macros.bassImpact)
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun globalMasterPreferenceEnablesLegacyDisabledCustomProfileAfterRuntimeRecreation() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspAudioProfile(
            id = "custom1",
            name = "Legacy off profile",
            enabled = false,
            preampDb = 2.0,
        )
        assertEquals(DspProfileSaveResult.SAVED, DspProfileRepository(context.filesDir).save(profile))
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, true)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .commit()

        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        assertTrue(runtime.configProvider.snapshot().enabled)
        assertEquals(2.0, runtime.configProvider.snapshot().gainDb, 0.0)

        DspProfileRuntime.resetForTesting()
        val recreated = DspProfileRuntime.get(context)
        assertTrue(recreated.isDspEnabled())
        assertTrue(recreated.configProvider.snapshot().enabled)
        assertEquals(2.0, recreated.configProvider.snapshot().gainDb, 0.0)
        DspProfileRuntime.resetForTesting()
    }

    @Test
    fun persistedStudioMacrosAreResolvedIntoTheRuntimeConfig() {
        val context: Application = RuntimeEnvironment.getApplication()
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        workspaceFile.delete()
        try {
            val profile = DspProfilePresets.find("flat")!!
            val workspace = DspStudioWorkspace(
                profileSourceId = profile.id,
                baseProfileSnapshot = profile,
                macros = DspSoundMacroSettings(enabled = true, bassImpact = 100),
            )
            assertEquals(
                DspWorkspaceSaveResult.SAVED,
                DspStudioWorkspaceRepository(context.filesDir).save(workspace),
            )
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DSP_ENABLED, true)
                .putString(KEY_SELECTED_PROFILE_ID, profile.id)
                .commit()

            DspProfileRuntime.resetForTesting()
            val runtime = DspProfileRuntime.get(context)

            val resolved = runtime.configProvider.snapshot()
            assertTrue(resolved.enabled)
            assertTrue(resolved.macroTone.enabled)
            assertEquals(4.0, resolved.macroTone.bassGainDb, 0.0)
        } finally {
            DspProfileRuntime.resetForTesting()
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun startupRejectsWorkspaceWhoseSourceIdDoesNotMatchItsSnapshot() {
        val context: Application = RuntimeEnvironment.getApplication()
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        try {
            val selectedProfile = DspProfilePresets.find("flat")!!
            val mismatchedSnapshot = DspProfilePresets.find("vocal")!!
            val workspace = DspStudioWorkspace(
                profileSourceId = mismatchedSnapshot.id,
                baseProfileSnapshot = mismatchedSnapshot,
                macros = DspSoundMacroSettings(enabled = true, bassImpact = 100),
            )
            assertEquals(DspWorkspaceSaveResult.SAVED, DspStudioWorkspaceRepository(context.filesDir).save(workspace))
            val savedJson = JSONObject(workspaceFile.readText()).put("baseProfileSourceId", selectedProfile.id)
            workspaceFile.writeText(savedJson.toString())
            preferences.edit()
                .putBoolean(KEY_DSP_ENABLED, true)
                .putString(KEY_SELECTED_PROFILE_ID, selectedProfile.id)
                .commit()

            val runtime = DspProfileRuntime.get(context)

            assertEquals(selectedProfile.id, runtime.selectedProfile().id)
            assertEquals(selectedProfile.id, runtime.createStudioSessionCoordinator().preview.baseProfileSnapshot.id)
            assertFalse(runtime.configProvider.snapshot().macroTone.enabled)
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun playbackVolumeUpdatesAppliedAutoConfigWhileStudioIsClosed() {
        val context: Application = RuntimeEnvironment.getApplication()
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        try {
            val profile = DspProfilePresets.find("flat")!!
            val workspace = DspStudioWorkspace(
                profileSourceId = profile.id,
                baseProfileSnapshot = profile,
                smartLoudness = DspSmartLoudnessSettings(
                    mode = DspSmartLoudnessMode.AUTO_CALIBRATED,
                    calibration = DspLoudnessCalibration("route-token", 0.0, 1.0),
                ),
            )
            assertEquals(DspWorkspaceSaveResult.SAVED, DspStudioWorkspaceRepository(context.filesDir).save(workspace))
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DSP_ENABLED, true)
                .putString(KEY_SELECTED_PROFILE_ID, profile.id)
                .commit()
            val runtime = DspProfileRuntime.get(context)
            assertFalse(runtime.configProvider.snapshot().smartLoudness.enabled)

            runtime.onPlaybackVolumeReadingChanged(
                DspVolumeReading(routeToken = "route-token", volumeFraction = 0.1, trustworthy = true),
            )
            awaitSmartLoudnessState(runtime, enabled = true)

            runtime.onPlaybackVolumeReadingChanged(
                DspVolumeReading(routeToken = "different-route", volumeFraction = 0.1, trustworthy = true),
            )
            awaitSmartLoudnessState(runtime, enabled = false)

            val generation = runtime.configProvider.versionedSnapshot().generation
            runtime.onPlaybackVolumeReadingChanged(
                DspVolumeReading(routeToken = "untrusted-route-a", volumeFraction = 0.4, trustworthy = false),
            )
            runtime.onPlaybackVolumeReadingChanged(
                DspVolumeReading(routeToken = "untrusted-route-b", volumeFraction = 0.7, trustworthy = false),
            )
            val completed = CountDownLatch(1)
            runtime.previewStudioWorkspaceAsync(workspace, enabled = true) { completed.countDown() }
            drainMainLooperUntil(completed)

            assertEquals(generation, runtime.configProvider.versionedSnapshot().generation)
        } finally {
            DspProfileRuntime.resetForTesting()
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun savedWorkspaceWinsOverMismatchedLegacyProfileSelectionOnStartup() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        try {
            val savedProfile = DspProfilePresets.find("flat")!!
            val savedWorkspace = DspStudioWorkspace(
                profileSourceId = savedProfile.id,
                baseProfileSnapshot = savedProfile,
                macros = DspSoundMacroSettings(enabled = true, bassImpact = 91),
            )
            assertEquals(
                DspWorkspaceSaveResult.SAVED,
                DspStudioWorkspaceRepository(context.filesDir).save(savedWorkspace),
            )
            val savedFileContents = workspaceFile.readText()
            preferences.edit()
                .putBoolean(KEY_DSP_ENABLED, true)
                .putString(KEY_SELECTED_PROFILE_ID, "vocal")
                .commit()

            DspProfileRuntime.resetForTesting()
            val runtime = DspProfileRuntime.get(context)

            assertEquals(savedProfile, runtime.selectedProfile())
            assertTrue(runtime.configProvider.snapshot().macroTone.enabled)
            assertEquals(savedProfile.id, preferences.getString(KEY_SELECTED_PROFILE_ID, null))
            assertEquals(savedFileContents, workspaceFile.readText())
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun studioSessionCoordinatorRetainsDraftWhenSettingsScreenIsRecreated() {
        val context: Application = RuntimeEnvironment.getApplication()
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()

        try {
            val runtime = DspProfileRuntime.get(context)
            val originalSession = runtime.createStudioSessionCoordinator()
            originalSession.edit { workspace ->
                workspace.copy(macros = workspace.macros.copy(enabled = true, bassImpact = 91))
            }

            val recreatedScreenSession = runtime.createStudioSessionCoordinator()

            assertSame(originalSession, recreatedScreenSession)
            assertTrue(recreatedScreenSession.isDirty)
            assertTrue(recreatedScreenSession.preview.macros.enabled)
            assertEquals(91, recreatedScreenSession.preview.macros.bassImpact)
            assertEquals(50, recreatedScreenSession.compareA().macros.bassImpact)
        } finally {
            DspProfileRuntime.resetForTesting()
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun runtimeRecreationRestoresPersistedWorkspaceAndDiscardsPreviewDraft() {
        val context: Application = RuntimeEnvironment.getApplication()
        val filesDirectory = File(context.cacheDir, "dsp-studio-process-recreation-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferencesName = "dsp_studio_process_recreation_test"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspProfilePresets.find("flat")!!
        val persistedWorkspace = DspStudioWorkspace(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 67),
        )
        val workspaceRepository = DspStudioWorkspaceRepository(filesDirectory)
        assertEquals(DspWorkspaceSaveResult.SAVED, workspaceRepository.save(persistedWorkspace))
        val workspaceFile = File(filesDirectory, "dsp/workspace/current.json")
        val persistedFileContents = workspaceFile.readText()
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, true)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .commit()

        var firstRuntime: DspProfileRuntime? = null
        var secondRuntime: DspProfileRuntime? = null
        try {
            val initialRuntime = DspProfileRuntime.createForTesting(context, filesDirectory, preferencesName)
            firstRuntime = initialRuntime
            val initialSession = initialRuntime.createStudioSessionCoordinator()
            initialSession.edit { workspace ->
                workspace.copy(macros = workspace.macros.copy(bassImpact = 99))
            }
            assertTrue(initialSession.isDirty)

            val previewResult = AtomicReference<Boolean?>()
            val previewComplete = CountDownLatch(1)
            initialRuntime.previewStudioWorkspaceAsync(initialSession.preview, enabled = true) {
                previewResult.set(it)
                previewComplete.countDown()
            }
            drainMainLooperUntil(previewComplete)

            assertEquals(true, previewResult.get())
            assertEquals(3.92, initialRuntime.configProvider.snapshot().macroTone.bassGainDb, 1.0e-9)
            assertEquals(persistedFileContents, workspaceFile.readText())
            initialRuntime.closeForTesting()
            firstRuntime = null

            val restoredRuntime = DspProfileRuntime.createForTesting(context, filesDirectory, preferencesName)
            secondRuntime = restoredRuntime
            val restoredSession = restoredRuntime.createStudioSessionCoordinator()

            assertFalse(restoredSession.isDirty)
            assertEquals(persistedWorkspace, restoredSession.preview)
            assertEquals(67, restoredSession.preview.macros.bassImpact)
            assertEquals(1.36, restoredRuntime.configProvider.snapshot().macroTone.bassGainDb, 1.0e-9)
            assertEquals(persistedFileContents, workspaceFile.readText())
        } finally {
            firstRuntime?.closeForTesting()
            secondRuntime?.closeForTesting()
            preferences.edit().clear().commit()
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun deletingSelectedModulePresetClearsSavedAndSessionReferences() {
        val context: Application = RuntimeEnvironment.getApplication()
        val filesDirectory = File(context.cacheDir, "dsp-delete-module-preset-runtime-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferencesName = "dsp_delete_module_preset_runtime_test"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspProfilePresets.find("flat")!!
        val presetId = "user_bass_delete_runtime"
        val workspace = DspStudioWorkspace(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile.copy(bass = profile.bass.copy(enabled = true, gainDb = 8.0)),
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 77),
            selectedModulePresetIds = mapOf("bass" to presetId),
        )
        val modulePresets = DspModulePresetRepository(filesDirectory)
        val workspaceRepository = DspStudioWorkspaceRepository(filesDirectory)
        assertEquals(
            DspPresetStoreResult.SAVED,
            modulePresets.saveCurrent(workspace, "bass", presetId, "Night"),
        )
        assertEquals(DspWorkspaceSaveResult.SAVED, workspaceRepository.save(workspace))
        val runtime = DspProfileRuntime.createForTesting(context, filesDirectory, preferencesName)
        val session = runtime.createStudioSessionCoordinator()
        val result = AtomicReference<DspPresetStoreResult?>()
        val complete = CountDownLatch(1)

        try {
            runtime.deleteModulePresetAsync("bass", presetId) {
                result.set(it)
                complete.countDown()
            }
            drainMainLooperUntil(complete)

            assertEquals(DspPresetStoreResult.DELETED, result.get())
            val saved = (workspaceRepository.load() as DspWorkspaceReadResult.Loaded).workspace
            assertNull(saved.selectedModulePresetIds["bass"])
            assertNull(session.compareA().selectedModulePresetIds["bass"])
            assertNull(session.preview.selectedModulePresetIds["bass"])
            assertEquals(8.0, saved.baseProfileSnapshot.bass.gainDb, 0.0)
            assertEquals(8.0, session.preview.baseProfileSnapshot.bass.gainDb, 0.0)
            assertEquals(77, saved.macros.bassImpact)
            assertEquals(77, session.preview.macros.bassImpact)
            assertFalse(session.isDirty)
            assertNull(modulePresets.load("bass", presetId))
        } finally {
            runtime.closeForTesting()
            preferences.edit().clear().commit()
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun deletingModulePresetPreservesItWhenWorkspaceUsesAFutureSchema() {
        val context: Application = RuntimeEnvironment.getApplication()
        val filesDirectory = File(context.cacheDir, "dsp-delete-future-workspace-runtime-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferencesName = "dsp_delete_future_workspace_runtime_test"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspProfilePresets.find("flat")!!
        val presetId = "user_bass_future_workspace"
        val workspace = DspStudioWorkspace(profile.id, profile)
        val modulePresets = DspModulePresetRepository(filesDirectory)
        assertEquals(
            DspPresetStoreResult.SAVED,
            modulePresets.saveCurrent(workspace, "bass", presetId, "Night"),
        )
        val workspaceFile = File(filesDirectory, "dsp/workspace/current.json").apply {
            parentFile!!.mkdirs()
        }
        val futureWorkspace = "{\"workspaceSchema\":99,\"sentinel\":\"preserve\"}"
        workspaceFile.writeText(futureWorkspace)
        val runtime = DspProfileRuntime.createForTesting(context, filesDirectory, preferencesName)
        val result = AtomicReference<DspPresetStoreResult?>()
        val complete = CountDownLatch(1)

        try {
            runtime.deleteModulePresetAsync("bass", presetId) {
                result.set(it)
                complete.countDown()
            }
            drainMainLooperUntil(complete)

            assertEquals(DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED, result.get())
            assertEquals(futureWorkspace, workspaceFile.readText())
            assertEquals(presetId, modulePresets.load("bass", presetId)?.id)
        } finally {
            runtime.closeForTesting()
            preferences.edit().clear().commit()
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun restoringCommittedStudioWorkspaceRestoresSelectedProfileAcrossRuntimeRecreation() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val committedProfile = DspProfilePresets.find("flat")!!
        val committedWorkspace = DspStudioWorkspace(committedProfile.id, committedProfile)
        val saveResult = AtomicReference<DspWorkspaceSaveResult?>()
        val saveComplete = CountDownLatch(1)

        try {
            runtime.saveStudioWorkspaceAsync(committedWorkspace, enabled = true) {
                saveResult.set(it)
                saveComplete.countDown()
            }
            drainMainLooperUntil(saveComplete)
            assertEquals(DspWorkspaceSaveResult.SAVED, saveResult.get())

            runtime.setMasterEnabled(false)
            assertFalse(preferences.getBoolean(KEY_DSP_ENABLED, true))
            val appliedProfile = DspProfilePresets.find("vocal")!!
            assertTrue(runtime.apply(appliedProfile, enabled = false))
            assertEquals(appliedProfile.id, preferences.getString(KEY_SELECTED_PROFILE_ID, null))

            val restoreRequest = AtomicReference<DspStudioRestoreRequest?>()
            val restoreComplete = CountDownLatch(1)
            runtime.requestCommittedStudioWorkspaceRestoreAsync(enabled = true) {
                restoreRequest.set(it)
                restoreComplete.countDown()
            }
            drainMainLooperUntil(restoreComplete)

            assertTrue(restoreRequest.get() != null)
            assertNull(restoreRequest.get()?.rendererOwnerToken)
            assertEquals(committedProfile.id, preferences.getString(KEY_SELECTED_PROFILE_ID, null))
            assertTrue(preferences.getBoolean(KEY_DSP_ENABLED, false))
            assertTrue(runtime.isDspEnabled())
            assertEquals(committedProfile, runtime.selectedProfile())

            DspProfileRuntime.resetForTesting()
            val recreated = DspProfileRuntime.get(context)
            assertEquals(committedProfile.id, recreated.selectedProfile().id)
            assertEquals(committedProfile, recreated.selectedProfile())
            assertTrue(recreated.isDspEnabled())
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun failedCommittedWorkspaceRestoreKeepsDraftUntilAConfirmedRetry() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val profile = DspProfilePresets.find("flat")!!
        val committed = DspStudioWorkspace(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
            macros = DspSoundMacroSettings(enabled = true, bassImpact = 90),
        )
        val saveResult = AtomicReference<DspWorkspaceSaveResult?>()
        val saveComplete = CountDownLatch(1)

        try {
            runtime.saveStudioWorkspaceAsync(committed, enabled = true) {
                saveResult.set(it)
                saveComplete.countDown()
            }
            drainMainLooperUntil(saveComplete)
            assertEquals(DspWorkspaceSaveResult.SAVED, saveResult.get())

            val session = runtime.createStudioSessionCoordinator()
            session.edit { it.copy(macros = it.macros.copy(bassImpact = 10)) }
            val draft = session.preview
            val previewComplete = CountDownLatch(1)
            runtime.previewStudioWorkspaceAsync(draft, enabled = true) { previewComplete.countDown() }
            drainMainLooperUntil(previewComplete)

            val failedOperation = requireNotNull(session.beginCancel())
            runtime.beforeStudioWorkspaceRestoreForTest = { false }
            val failedRequest = AtomicReference<DspStudioRestoreRequest?>()
            val failedComplete = CountDownLatch(1)
            runtime.requestCommittedStudioWorkspaceRestoreAsync(enabled = true) {
                failedRequest.set(it)
                failedComplete.countDown()
            }
            drainMainLooperUntil(failedComplete)

            assertNull(failedRequest.get())
            assertTrue(session.failCancel(failedOperation))
            assertEquals(draft, session.preview)
            assertTrue(session.isDirty)
            assertFalse(runtime.isCommittedStudioWorkspaceActive())

            runtime.beforeStudioWorkspaceRestoreForTest = null
            val retryOperation = requireNotNull(session.beginCancel())
            val retryRequest = AtomicReference<DspStudioRestoreRequest?>()
            val retryComplete = CountDownLatch(1)
            runtime.requestCommittedStudioWorkspaceRestoreAsync(enabled = true) {
                retryRequest.set(it)
                retryComplete.countDown()
            }
            drainMainLooperUntil(retryComplete)

            assertTrue(retryRequest.get() != null)
            assertNull(retryRequest.get()?.rendererOwnerToken)
            assertTrue(session.commitCancel(retryOperation))
            assertEquals(committed, session.preview)
            assertFalse(session.isDirty)
            assertTrue(runtime.isCommittedStudioWorkspaceActive())
        } finally {
            runtime.beforeStudioWorkspaceRestoreForTest = null
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
        }
    }

    @Test
    fun cancelInvalidatesSaveBeforeItsWorkspaceCommit() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        File(workspaceFile.path + ".bak").delete()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val committedProfile = DspProfilePresets.find("flat")!!
        val committed = DspStudioWorkspace(committedProfile.id, committedProfile)
        val candidateProfile = DspProfilePresets.find("vocal")!!
        val candidate = DspStudioWorkspace(candidateProfile.id, candidateProfile)
        val saveResult = AtomicReference<DspWorkspaceSaveResult?>()
        val saveComplete = CountDownLatch(1)
        val reachedSaveBarrier = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val restoreRequest = AtomicReference<DspStudioRestoreRequest?>()
        val restoreComplete = CountDownLatch(1)

        try {
            val initialSave = CountDownLatch(1)
            runtime.saveStudioWorkspaceAsync(committed, enabled = true) { initialSave.countDown() }
            drainMainLooperUntil(initialSave)

            runtime.afterStudioWorkspaceApplyForTest = {
                reachedSaveBarrier.countDown()
                check(releaseSave.await(5, TimeUnit.SECONDS))
            }
            runtime.saveStudioWorkspaceAsync(candidate, enabled = true) {
                saveResult.set(it)
                saveComplete.countDown()
            }
            assertTrue(reachedSaveBarrier.await(5, TimeUnit.SECONDS))

            runtime.requestCommittedStudioWorkspaceRestoreAsync(enabled = true) {
                restoreRequest.set(it)
                restoreComplete.countDown()
            }
            releaseSave.countDown()
            drainMainLooperUntil(saveComplete)
            drainMainLooperUntil(restoreComplete)

            assertEquals(DspWorkspaceSaveResult.STALE, saveResult.get())
            assertTrue(restoreRequest.get() != null)
            assertTrue(runtime.isCommittedStudioWorkspaceActive())
            assertEquals(committedProfile, runtime.selectedProfile())
            assertEquals(committed, DspStudioWorkspaceRepository(context.filesDir).load().let {
                (it as DspWorkspaceReadResult.Loaded).workspace
            })
        } finally {
            releaseSave.countDown()
            runtime.afterStudioWorkspaceApplyForTest = null
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
            File(workspaceFile.path + ".bak").delete()
        }
    }

    @Test
    fun cancelDuringAtomicWorkspaceWriteDoesNotBlockAndPreventsStaleCommit() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val workspaceFile = File(context.filesDir, "dsp/workspace/current.json")
        preferences.edit().clear().commit()
        workspaceFile.delete()
        File(workspaceFile.path + ".bak").delete()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val committedProfile = DspProfilePresets.find("flat")!!
        val committed = DspStudioWorkspace(committedProfile.id, committedProfile)
        val candidateProfile = DspProfilePresets.find("vocal")!!
        val candidate = DspStudioWorkspace(candidateProfile.id, candidateProfile)
        val saveResult = AtomicReference<DspWorkspaceSaveResult?>()
        val saveComplete = CountDownLatch(1)
        val reachedCommitBarrier = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val restoreRequest = AtomicReference<DspStudioRestoreRequest?>()
        val restoreComplete = CountDownLatch(1)

        try {
            val initialSave = CountDownLatch(1)
            runtime.saveStudioWorkspaceAsync(committed, enabled = true) { initialSave.countDown() }
            drainMainLooperUntil(initialSave)

            runtime.beforeStudioWorkspaceCommitForTest = {
                reachedCommitBarrier.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
            }
            runtime.saveStudioWorkspaceAsync(candidate, enabled = true) {
                saveResult.set(it)
                saveComplete.countDown()
            }
            assertTrue(reachedCommitBarrier.await(5, TimeUnit.SECONDS))

            val cancelStart = System.nanoTime()
            runtime.requestCommittedStudioWorkspaceRestoreAsync(enabled = true) {
                restoreRequest.set(it)
                restoreComplete.countDown()
            }
            val cancelElapsedNanos = System.nanoTime() - cancelStart
            assertTrue(
                "Cancel generation request must not wait for the workspace file write",
                cancelElapsedNanos < TimeUnit.MILLISECONDS.toNanos(500),
            )

            releaseCommit.countDown()
            drainMainLooperUntil(saveComplete)
            drainMainLooperUntil(restoreComplete)

            assertEquals(DspWorkspaceSaveResult.STALE, saveResult.get())
            assertTrue(restoreRequest.get() != null)
            assertTrue(runtime.isCommittedStudioWorkspaceActive())
            assertEquals(committedProfile, runtime.selectedProfile())
            assertEquals(committed, DspStudioWorkspaceRepository(context.filesDir).load().let {
                (it as DspWorkspaceReadResult.Loaded).workspace
            })
        } finally {
            releaseCommit.countDown()
            runtime.beforeStudioWorkspaceCommitForTest = null
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            workspaceFile.delete()
            File(workspaceFile.path + ".bak").delete()
        }
    }

    @Test
    fun masterToggleChangesOnlyActiveProfileAndApplyCommitsTheDraft() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val active = DspAudioProfile(id = "custom2", name = "Active", enabled = false, preampDb = 0.0)
        val draft = active.copy(preampDb = 6.0)
        runtime.apply(active, enabled = false)

        runtime.setMasterEnabled(false)
        runtime.setMasterEnabled(true)

        assertTrue(runtime.configProvider.snapshot().enabled)
        assertEquals(0.0, runtime.configProvider.snapshot().gainDb, 0.0)
        assertEquals(active, runtime.selectedProfile())

        runtime.apply(draft, enabled = runtime.isDspEnabled())

        assertEquals(6.0, runtime.configProvider.snapshot().gainDb, 0.0)
        assertFalse(runtime.selectedProfile().enabled)
        assertTrue(runtime.configProvider.snapshot().enabled)
        DspProfileRuntime.resetForTesting()
    }

    @Test
    fun discardingImportedImpulseRemovesOnlyUnreferencedFiles() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val repository = DspImpulseResponseRepository(context.filesDir)
        val unreferencedId = "unadopted_${System.nanoTime()}"
        val unreferencedFile = createImpulseFile(repository, unreferencedId)
        var referencedId: String? = null

        try {
            runtime.discardImportedImpulseResponse(unreferencedId)

            assertFalse(unreferencedFile.exists())

            referencedId = "referenced_${System.nanoTime()}"
            val referencedFile = createImpulseFile(repository, referencedId)
            val referencedProfile = runtime.selectedProfile().copy(
                convolver = DspConvolverConfig(enabled = true, impulseResponseId = referencedId),
            )
            assertTrue(runtime.apply(referencedProfile, runtime.isDspEnabled()))

            runtime.discardImportedImpulseResponse(referencedId)

            assertTrue(referencedFile.isFile)
        } finally {
            repository.delete(unreferencedId)
            referencedId?.let(repository::delete)
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            DspProfileRuntime.resetForTesting()
        }
    }

    @Test
    fun overCapacityApplyAndSaveLeaveTheActiveRuntimeUntouched() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val valid = DspProfilePresets.customProfile("custom1").copy(
            eqBands = List(12) { index ->
                DspEqBand(DspEqType.PEAK, 100.0 + index * 100.0, gainDb = -1.0)
            },
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                notches = List(3) { index ->
                    DspCabinCorrectionNotch(enabled = true, frequencyHz = 80.0 + index * 40.0)
                },
            ),
        )
        val invalid = valid.copy(
            eqBands = valid.eqBands + DspEqBand(DspEqType.PEAK, 1_500.0, gainDb = -1.0),
        )
        assertTrue(runtime.apply(valid, enabled = true))
        val previousConfig = runtime.configProvider.snapshot()
        val previousProfile = runtime.selectedProfile()
        var saveResult: DspProfileSaveResult? = null

        assertFalse(runtime.apply(invalid, enabled = false))
        runtime.saveCustomAsync(invalid, enabled = false) { saveResult = it }

        assertEquals(DspProfileSaveResult.INVALID_EQ_CAPACITY, saveResult)
        assertSame(previousConfig, runtime.configProvider.snapshot())
        assertEquals(previousProfile, runtime.selectedProfile())
        assertTrue(runtime.isDspEnabled())
        DspProfileRuntime.resetForTesting()
    }

    private companion object {
        const val PREFERENCES_NAME = "dsp_profile_selection"
        const val KEY_DSP_ENABLED = "enabled"
        const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    }

    private fun createImpulseFile(repository: DspImpulseResponseRepository, id: String): File {
        val file = requireNotNull(repository.impulseFileForTesting(id))
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private fun drainMainLooperUntil(completed: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (completed.count > 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("main-thread callback completed", completed.await(0, TimeUnit.MILLISECONDS))
    }

    private fun activeStudioWorkspace(runtime: DspProfileRuntime): DspStudioWorkspace {
        val field = DspProfileRuntime::class.java.getDeclaredField("activeStudioWorkspace").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(runtime) as AtomicReference<DspStudioWorkspace>).get()
    }

    private fun awaitSmartLoudnessState(runtime: DspProfileRuntime, enabled: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runtime.configProvider.snapshot().smartLoudness.enabled != enabled && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(enabled, runtime.configProvider.snapshot().smartLoudness.enabled)
    }
}
