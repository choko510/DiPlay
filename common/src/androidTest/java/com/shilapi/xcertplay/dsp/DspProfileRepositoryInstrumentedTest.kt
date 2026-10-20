package com.shilapi.xcertplay.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspProfileRepositoryInstrumentedTest {
    @Test
    fun schemaThreeProfileLoadsAndSavesWithAndroidAtomicFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "u74-profile-${UUID.randomUUID()}")
        val repository = DspProfileRepository(filesDirectory)

        try {
            val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
            assertTrue(profileFile.parentFile!!.mkdirs())
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
            assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))

            val reloaded = repository.load("custom1") as DspProfileReadResult.Loaded
            assertEquals(null, reloaded.migratedFromVersion)
            assertEquals(loaded.profile, reloaded.profile)
            assertTrue(profileFile.readText(Charsets.UTF_8).contains("\"schemaVersion\":7"))
        } finally {
            filesDirectory.deleteRecursively()
        }
    }
}
