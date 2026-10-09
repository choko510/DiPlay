package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsMonitorMode
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCut
import com.shilapi.xcertplay.media.dsp.DspCabinCutSource
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspHarmonicEngineConfig
import com.shilapi.xcertplay.media.dsp.DspHarmonicMode
import com.shilapi.xcertplay.media.dsp.DspHarmonicQuality
import com.shilapi.xcertplay.media.dsp.DspHighExciterConfig
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatFocusConfig
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
import com.shilapi.xcertplay.media.dsp.DspVirtualBassConfig
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
import org.json.JSONObject

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
                preset = DspSeatPreset.PASSENGER,
                leftDelayMs = 0.65,
                rightDelayMs = 0.0,
                leftTrimDb = 1.5,
                rightTrimDb = -1.0,
                invertRight = true,
            ),
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                midGainDb = 1.0,
                sideGainDb = -1.5,
                midEqBands = listOf(DspEqBand(DspEqType.PEAK, 1_200.0, gainDb = 1.0)),
                sideEqBands = listOf(DspEqBand(DspEqType.NOTCH, 4_000.0, q = 2.0)),
                lowWidth = 0.8,
                midWidth = 1.1,
                highWidth = 1.4,
                lowMidCrossoverHz = 180.0,
                midHighCrossoverHz = 3_600.0,
                centerFocusDb = 1.5,
            ),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                calibrationId = "cal_front_seat",
                dynamicControlEnabled = true,
                maxDynamicCutDb = 2.0,
                cuts = listOf(
                    DspCabinCut(
                        frequencyHz = 82.0,
                        q = 4.0,
                        reductionDb = -4.0,
                        enabled = true,
                        source = DspCabinCutSource.MEASUREMENT,
                        prominenceDb = 6.0,
                        confidence = 0.9,
                    ),
                ),
            ),
            harmonicEngine = DspHarmonicEngineConfig(
                enabled = true,
                quality = DspHarmonicQuality.FOUR_X,
                highExciter = DspHighExciterConfig(
                    enabled = true,
                    frequencyHz = 5_500.0,
                    drive = 2.5,
                    amount = 0.7,
                    mode = DspHarmonicMode.EVEN,
                    wet = 0.25,
                    outputCompensationDb = -1.5,
                ),
                virtualBass = DspVirtualBassConfig(
                    enabled = true,
                    fundamentalLowHz = 40.0,
                    fundamentalHighHz = 70.0,
                    drive = 2.0,
                    amount = 0.6,
                    secondHarmonicAmount = 0.7,
                    thirdHarmonicAmount = 0.3,
                    wet = 0.2,
                ),
            ),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        val persistedCabin = JSONObject(
            requireNotNull(repository.profileFileForTesting("custom1")).readText(),
        ).getJSONObject("cabinCorrection")
        assertFalse(persistedCabin.has("measurementPoints"))
        assertFalse(persistedCabin.has("points"))
        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded
        assertEquals(profile, loaded.profile)
        assertEquals(null, loaded.migratedFromVersion)
        assertEquals(
            profile,
            repository.decodeDraftFromSavedState(repository.encodeDraftForSavedState(profile)),
        )
    }

    @Test
    fun temporarySeatFocusBypassIsNotPersistedInTheProfile() {
        val repository = repository()
        val profile = DspProfilePresets.customProfile("custom1").copy(
            seatFocus = DspSeatFocusConfig(enabled = true, bypassed = true, rightDelayMs = 0.65),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertTrue(profile.seatFocus.bypassed)
        assertFalse(loaded.profile.seatFocus.bypassed)
        assertEquals(0.65, loaded.profile.seatFocus.rightDelayMs, 0.0)
    }

    @Test
    fun temporaryAdvancedMsMonitorModeIsNotPersistedInTheProfile() {
        val repository = DspProfileRepository(temporaryFolder.newFolder("transient-profile")) { file, contents ->
            file.writeBytes(contents)
        }
        val base = DspProfilePresets.customProfile("custom1").copy(
            advancedMs = DspAdvancedMsConfig(enabled = true),
        )
        assertEquals(DspProfileSaveResult.SAVED, repository.save(base))
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        val persisted = JSONObject(profileFile.readText())
        persisted.getJSONObject("advancedMs").put("monitorMode", DspAdvancedMsMonitorMode.SIDE_ONLY.name)
        persisted.getJSONObject("cabinCorrection").put("bypassed", true)
        profileFile.writeText(persisted.toString())
        val profile = base.copy(
            advancedMs = base.advancedMs.copy(monitorMode = DspAdvancedMsMonitorMode.SIDE_ONLY),
            cabinCorrection = base.cabinCorrection.copy(bypassed = true),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        val writtenProfile = JSONObject(profileFile.readText())
        assertFalse(writtenProfile.getJSONObject("advancedMs").has("monitorMode"))
        assertFalse(writtenProfile.getJSONObject("cabinCorrection").has("bypassed"))
        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(DspAdvancedMsMonitorMode.SIDE_ONLY, profile.advancedMs.monitorMode)
        assertEquals(DspAdvancedMsMonitorMode.NORMAL, loaded.profile.advancedMs.monitorMode)
        assertTrue(profile.cabinCorrection.bypassed)
        assertFalse(loaded.profile.cabinCorrection.bypassed)
        assertTrue(loaded.profile.advancedMs.enabled)
    }

    @Test
    fun temporaryCabinBypassIsNotPersistedInTheProfile() {
        val repository = repository()
        val profile = DspProfilePresets.customProfile("custom1").copy(
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                bypassed = true,
                cuts = listOf(DspCabinCut(82.0, 4.0, -3.0, enabled = true)),
            ),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertTrue(profile.cabinCorrection.bypassed)
        assertFalse(loaded.profile.cabinCorrection.bypassed)
        assertEquals(profile.cabinCorrection.cuts, loaded.profile.cabinCorrection.cuts)
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
            advancedMs = DspAdvancedMsConfig(enabled = true, midGainDb = 2.0),
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
            """{"schemaVersion":4,"id":"custom1","dynamicEq":{"bands":[{"mode":"NOT_A_MODE"}]}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
    }

    @Test
    fun legacyProfileMigratesToCurrentSchema() {
        val repository = repositoryWithDirectWriterForMigrationTests()
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
        assertNewModulesOff(loaded.profile)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertEquals(4, JSONObject(profileFile.readText()).getInt("schemaVersion"))
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
        assertNewModulesOff(loaded.profile)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        val savedProfile = profileFile.readText()
        assertTrue("schema v1 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":4"))
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
        assertNewModulesOff(loaded.profile)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        val savedProfile = profileFile.readText()
        assertTrue("schema v2 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":4"))
    }

    @Test
    fun versionThreeProfileMigratesWithAllNewModulesDisabled() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":3,"id":"custom1","name":"Existing","enabled":true,"preampDb":-2.0,"stereo":{"width":1.5,"monoBass":{"enabled":true,"cutoffHz":100}}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(3, loaded.migratedFromVersion)
        assertEquals(1.5, loaded.profile.stereoWidth, 0.0)
        assertTrue(loaded.profile.monoBass.enabled)
        assertFalse(loaded.profile.seatFocus.enabled)
        assertFalse(loaded.profile.advancedMs.enabled)
        assertFalse(loaded.profile.cabinCorrection.enabled)
        assertFalse(loaded.profile.harmonicEngine.enabled)
        assertNewModulesOff(loaded.profile)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":4"))
    }

    @Test
    fun unknownFieldsSurviveProfileMigrationAndEditing() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":3,"id":"custom1","name":"Existing","futureTop":{"owner":"newer-app"},"peq":[{"type":"PEAK","frequencyHz":1000,"futureBand":7}],"stereo":{"width":1.25,"futureStereo":true,"monoBass":{"enabled":false,"cutoffHz":120,"futureMono":"preserve"}},"seatFocus":{"futureSeat":[1,2]}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile.copy(name = "Edited")))

        val saved = JSONObject(profileFile.readText())
        assertEquals("newer-app", saved.getJSONObject("futureTop").getString("owner"))
        assertEquals(7, saved.getJSONArray("peq").getJSONObject(0).getInt("futureBand"))
        assertTrue(saved.getJSONObject("stereo").getBoolean("futureStereo"))
        assertEquals("preserve", saved.getJSONObject("stereo").getJSONObject("monoBass").getString("futureMono"))
        assertEquals(listOf(1, 2), saved.getJSONObject("seatFocus").getJSONArray("futureSeat").let { list ->
            listOf(list.getInt(0), list.getInt(1))
        })
    }

    @Test
    fun duplicateKeysMalformedNewTypesAndOversizedFilesAreRejectedAndNotOverwritten() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        val duplicateJson = """{"schemaVersion":4,"id":"custom1","seatFocus":{"enabled":false,"en\u0061bled":true}}"""
        profileFile.writeText(duplicateJson)
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        assertEquals(DspProfileSaveResult.INVALID_EXISTING_PROFILE, repository.save(DspProfilePresets.customProfile("custom1")))
        assertEquals(duplicateJson, profileFile.readText())

        profileFile.writeText("""{"schemaVersion":4,"id":"custom1","seatFocus":{"enabled":"true"}}""")
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))

        val oversized = "{" + " ".repeat(512 * 1024) + "}"
        profileFile.writeText(oversized)
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        assertEquals(DspProfileSaveResult.INVALID_EXISTING_PROFILE, repository.save(DspProfilePresets.customProfile("custom1")))
        assertEquals(oversized.length, profileFile.length().toInt())
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

    private fun assertNewModulesOff(profile: DspAudioProfile) {
        assertFalse(profile.seatFocus.enabled)
        assertFalse(profile.advancedMs.enabled)
        assertFalse(profile.cabinCorrection.enabled)
        assertFalse(profile.harmonicEngine.enabled)
    }
}
