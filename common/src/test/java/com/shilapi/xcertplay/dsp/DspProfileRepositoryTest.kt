package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsBandConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatFocusConfig
import com.shilapi.xcertplay.media.dsp.DspSeatFocusPreset
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspProfileRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun customProfileRoundTripsEverySchemaField() {
        val repository = repository()
        val profile = DspAudioProfile(
            id = "custom1",
            name = "Test profile",
            enabled = true,
            preampDb = -3.0,
            autoHeadroomEnabled = false,
            autoHeadroomMarginDb = 2.0,
            eqBands = listOf(DspEqBand(DspEqType.HIGH_SHELF, 6_000.0, gainDb = 2.0, q = 1.2)),
            bass = DspBassConfig(enabled = true, gainDb = 3.0, frequencyHz = 90.0),
            compressor = DspCompressorConfig(enabled = true, thresholdDb = -25.0, ratio = 3.0, makeupDb = 2.0),
            stereoWidth = 1.4,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 100),
            convolver = DspConvolverConfig(enabled = true, impulseResponseId = "ir_a", wet = 0.75),
            multiband = DspMultibandConfig(
                enabled = true,
                lowMidCrossoverHz = 150.0,
                midHighCrossoverHz = 2_200.0,
                low = DspCompressorConfig(enabled = true, thresholdDb = -30.0, ratio = 5.0, makeupDb = 1.0),
                mid = DspCompressorConfig(enabled = true, thresholdDb = -22.0, ratio = 3.0),
                high = DspCompressorConfig(enabled = true, thresholdDb = -18.0, ratio = 2.0),
            ),
            dynamicEq = DspDynamicEqConfig(
                enabled = true,
                bands = listOf(
                    DspDynamicEqBandConfig(
                        enabled = true,
                        frequencyHz = 2_000.0,
                        q = 1.5,
                        thresholdDb = -28.0,
                        ratio = 3.0,
                        attackMs = 5.0,
                        releaseMs = 120.0,
                        maxBoostDb = 4.0,
                        maxCutDb = 8.0,
                        mode = DspDynamicEqMode.BOOST,
                    ),
                ),
            ),
            limiter = DspSafetyLimiterConfig(enabled = true, thresholdDb = -2.0, releaseMs = 80.0),
            seatFocus = DspSeatFocusConfig(
                enabled = true,
                preset = DspSeatFocusPreset.PASSENGER,
                leftDistanceCm = 125.0,
                rightDistanceCm = 165.0,
                leftTrimDb = -2.5,
                rightTrimDb = 1.5,
                leftPolarityInverted = true,
            ),
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                crossoverHz = 180.0,
                low = DspAdvancedMsBandConfig(midGainDb = 1.0, sideGainDb = -1.0, width = 0.5),
                high = DspAdvancedMsBandConfig(midGainDb = -2.0, sideGainDb = 2.0, width = 1.5),
                centerFocusDb = 2.5,
                sideCompressor = DspCompressorConfig(
                    enabled = true,
                    thresholdDb = -26.0,
                    ratio = 3.0,
                    attackMs = 8.0,
                    releaseMs = 150.0,
                ),
            ),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded
        assertEquals(profile, loaded.profile)
        assertEquals(null, loaded.migratedFromVersion)
        assertEquals(
            profile,
            repository.decodeDraftFromSavedState(repository.encodeDraftForSavedState(profile)),
        )
    }

    @Test
    fun profileMapsToAnImmutableRuntimeSnapshotAndRespectsTheMasterSwitch() {
        val profile = DspAudioProfile(
            id = "custom2",
            name = "Runtime map",
            enabled = false,
            eqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 3.0)),
            bass = DspBassConfig(enabled = true, gainDb = 4.0),
            stereoWidth = 1.5,
            monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 100),
            convolver = DspConvolverConfig(enabled = true, impulseResponseId = "room1"),
            multiband = DspMultibandConfig(enabled = true, low = DspCompressorConfig(enabled = true)),
            dynamicEq = DspDynamicEqConfig(enabled = true),
        )

        val disabled = profile.toRuntimeConfig(masterEnabled = false)
        val enabled = profile.toRuntimeConfig(masterEnabled = true)

        assertFalse(disabled.enabled)
        assertTrue(enabled.enabled)
        assertEquals(profile.preampDb, enabled.preampDb, 0.0)
        assertEquals(profile.eqBands, enabled.peqBands)
        assertEquals(profile.bass, enabled.bass)
        assertEquals(profile.stereoWidth, enabled.stereoWidth, 0.0)
        assertEquals(profile.monoBass, enabled.monoBass)
        assertEquals(profile.convolver, enabled.convolver)
        assertEquals(profile.multiband, enabled.multiband)
        assertEquals(profile.dynamicEq, enabled.dynamicEq)
        assertEquals(profile.advancedMs, enabled.advancedMs)
        assertTrue(runCatching { (enabled.peqBands as MutableList).clear() }.isFailure)
    }

    @Test
    fun malformedNonFiniteAndUnknownEnumProfilesUseSafeInvalidResult() {
        val repository = repository()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())

        profileFile.writeText("{")
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))

        profileFile.writeText("""{"schemaVersion":1,"id":"custom1","preampDb":"NaN"}""")
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText("""{"schemaVersion":1,"id":"custom1","preampDb":"Infinity"}""")
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))

        profileFile.writeText(
            """{"schemaVersion":1,"id":"custom1","peq":[{"type":"NOT_A_FILTER","frequencyHz":1000}]}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":3,"id":"custom1","dynamicEq":{"bands":[{"mode":"NOT_A_MODE"}]}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":4,"id":"custom1","seatFocus":{"preset":"NOT_A_PRESET"}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
    }

    @Test
    fun legacyProfileMigratesToCurrentSchema() {
        val repository = repository()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":0,"id":"custom1","name":"Old","enabled":true,"preamp":3.0,"autoHeadroom":false}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(0, loaded.migratedFromVersion)
        assertEquals(3.0, loaded.profile.preampDb, 0.0)
        assertFalse(loaded.profile.autoHeadroomEnabled)
        assertEquals(15, loaded.profile.eqBands.size)
        val disabledRuntime = loaded.profile.toRuntimeConfig(masterEnabled = false)
        assertFalse(disabledRuntime.enabled)
        assertEquals(3.0, disabledRuntime.gainDb, 0.0)
    }

    @Test
    fun versionOneProfileMigratesWithMultibandDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":1,"id":"custom1","name":"Existing","enabled":true,"preampDb":-2.0}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(1, loaded.migratedFromVersion)
        assertFalse(loaded.profile.multiband.enabled)
        assertEquals(DspMultibandConfig.DEFAULT_LOW_MID_CROSSOVER_HZ, loaded.profile.multiband.lowMidCrossoverHz, 0.0)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        val savedProfile = profileFile.readText()
        assertTrue("schema v1 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":5"))
    }

    @Test
    fun versionTwoProfileMigratesWithDynamicEqDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":2,"id":"custom1","name":"Existing","enabled":true,"preampDb":-2.0}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(2, loaded.migratedFromVersion)
        assertFalse(loaded.profile.dynamicEq.enabled)
        assertEquals(DspDynamicEqConfig.MAX_BANDS, loaded.profile.dynamicEq.bands.size)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        val savedProfile = profileFile.readText()
        assertTrue("schema v2 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":5"))
    }

    @Test
    fun versionThreeProfileMigratesWithSeatFocusDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":3,"id":"custom1","name":"Existing","enabled":true,"preampDb":-2.0}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(3, loaded.migratedFromVersion)
        assertFalse(loaded.profile.seatFocus.enabled)
        assertEquals(DspSeatFocusPreset.CENTER, loaded.profile.seatFocus.preset)
        assertEquals(0.0, loaded.profile.seatFocus.leftDistanceCm, 0.0)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":5"))
    }

    @Test
    fun versionFourProfileMigratesWithAdvancedMsDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":4,"id":"custom1","name":"Existing","seatFocus":{"enabled":true,"leftDistanceCm":120.0,"rightDistanceCm":100.0}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(4, loaded.migratedFromVersion)
        assertTrue(loaded.profile.seatFocus.enabled)
        assertEquals(120.0, loaded.profile.seatFocus.leftDistanceCm, 0.0)
        assertFalse(loaded.profile.advancedMs.enabled)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":5"))
    }

    @Test
    fun futureSchemaFileIsReturnedSafelyAndNeverOverwritten() {
        val repository = repository()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        val futureFile = """{"schemaVersion":99,"id":"custom1","futureField":"keep"}"""
        profileFile.writeText(futureFile)

        assertEquals(DspProfileReadResult.FutureSchema(99), repository.load("custom1"))
        assertEquals(DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED, repository.save(DspProfilePresets.customProfile("custom1")))
        assertEquals(futureFile, profileFile.readText())
    }

    @Test
    fun failedAtomicWriteReturnsFailureWithoutPublishingAProfile() {
        val repository = DspProfileRepository(temporaryFolder.newFolder("files")) { _, _ ->
            throw IOException("simulated AtomicFile write failure")
        }

        assertEquals(DspProfileSaveResult.WRITE_FAILED, repository.save(DspProfilePresets.customProfile("custom1")))
        assertEquals(DspProfileReadResult.NotFound, repository.load("custom1"))
    }

    @Test
    fun profileAndImpulseIdsRejectTraversalAndEncodedTraversal() {
        val repository = repository()
        assertTrue(runCatching { DspAudioProfile(id = "../escape", name = "Bad") }.isFailure)
        assertTrue(runCatching { DspConvolverConfig(enabled = true, impulseResponseId = "%2e%2e") }.isFailure)
        assertEquals(null, repository.profileFileForTesting("C:\\outside"))
        assertEquals(null, repository.profileFileForTesting("%2e%2e"))
    }

    private fun repository(): DspProfileRepository = DspProfileRepository(temporaryFolder.newFolder("files"))

    private fun repositoryWithDirectWriterForMigrationTests(): DspProfileRepository =
        DspProfileRepository(temporaryFolder.newFolder("files")) { file, contents -> file.writeBytes(contents) }
}
