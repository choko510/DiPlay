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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

            val restoreResult = AtomicReference<Boolean>()
            val restoreComplete = CountDownLatch(1)
            runtime.restoreCommittedStudioWorkspaceAsync(enabled = true) {
                restoreResult.set(it)
                restoreComplete.countDown()
            }
            drainMainLooperUntil(restoreComplete)

            assertEquals(true, restoreResult.get())
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

    private fun awaitSmartLoudnessState(runtime: DspProfileRuntime, enabled: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runtime.configProvider.snapshot().smartLoudness.enabled != enabled && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(enabled, runtime.configProvider.snapshot().smartLoudness.enabled)
    }
}
