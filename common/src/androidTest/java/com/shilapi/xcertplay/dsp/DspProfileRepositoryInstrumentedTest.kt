package com.shilapi.xcertplay.dsp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspProfileRepositoryInstrumentedTest {
    @Test
    fun cancelledWorkspaceAtomicCommitRestoresThePreviousFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-studio-workspace-atomic-cancel-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val repository = DspStudioWorkspaceRepository(filesDirectory)
        val profile = DspProfilePresets.find("flat")!!
        val committed = DspStudioWorkspace(profile.id, profile)
        val candidate = committed.copy(macros = committed.macros.copy(enabled = true, bassImpact = 95))
        val workspaceFile = File(filesDirectory, "dsp/workspace/current.json")

        try {
            assertEquals(DspWorkspaceSaveResult.SAVED, repository.save(committed))
            val previousBytes = workspaceFile.readBytes()

            assertEquals(
                DspWorkspaceSaveResult.STALE,
                repository.save(candidate, beforeCommit = { false }),
            )

            assertArrayEquals(previousBytes, workspaceFile.readBytes())
            assertEquals(committed, (repository.load() as DspWorkspaceReadResult.Loaded).workspace)
        } finally {
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun schemaThreeProfileLoadsAtRuntimeAndSavesWithAndroidAtomicFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DspProfileRepository(context.filesDir)
        val preferences = context.getSharedPreferences("dsp_profile_selection", Context.MODE_PRIVATE)

        try {
            DspProfileRuntime.resetForTesting()
            assertTrue(
                preferences.edit()
                    .clear()
                    .putBoolean("enabled", true)
                    .putString("selected_profile_id", "custom1")
                    .commit(),
            )
            val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
            assertTrue(profileFile.parentFile!!.mkdirs() || profileFile.parentFile!!.isDirectory)
            profileFile.writeText(
                """{"schemaVersion":3,"id":"custom1","name":"U74 Legacy Test","enabled":true,"preampDb":-4.0,"autoHeadroom":false,"stereo":{"width":1.25}}""",
                Charsets.UTF_8,
            )

            val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

            assertEquals(3, loaded.migratedFromVersion)
            assertEquals("U74 Legacy Test", loaded.profile.name)
            assertEquals(-4.0, loaded.profile.preampDb, 0.0)
            assertFalse(loaded.profile.autoHeadroomEnabled)
            assertEquals(1.25, loaded.profile.stereoWidth, 0.0)
            assertFalse(loaded.profile.seatAlignment.enabled)
            assertFalse(loaded.profile.advancedMs.enabled)
            assertFalse(loaded.profile.cabinCorrection.enabled)
            assertFalse(loaded.profile.harmonic.enabled)

            val runtime = DspProfileRuntime.get(context)
            assertTrue(runtime.isDspEnabled())
            assertEquals(loaded.profile, runtime.selectedProfile())
            assertEquals(-4.0, runtime.configProvider.snapshot().preampDb, 0.0)
            assertFalse(runtime.configProvider.snapshot().seatAlignment.enabled)
            assertFalse(runtime.configProvider.snapshot().advancedMs.enabled)
            assertFalse(runtime.configProvider.snapshot().cabinCorrection.enabled)
            assertFalse(runtime.configProvider.snapshot().harmonic.enabled)
            assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))

            val reloaded = repository.load("custom1") as DspProfileReadResult.Loaded
            assertEquals(null, reloaded.migratedFromVersion)
            assertEquals(loaded.profile, reloaded.profile)
            assertTrue(profileFile.readText(Charsets.UTF_8).contains("\"schemaVersion\":7"))
        } finally {
            DspProfileRuntime.resetForTesting()
            preferences.edit().clear().commit()
            File(context.filesDir, "dsp").deleteRecursively()
        }
    }
}
