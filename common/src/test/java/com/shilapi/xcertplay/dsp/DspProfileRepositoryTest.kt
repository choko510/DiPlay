package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionNotch
import com.shilapi.xcertplay.media.dsp.DspCabinMeasurementSummary
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspHarmonicMode
import com.shilapi.xcertplay.media.dsp.DspHarmonicQuality
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMsSideCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatAlignmentConfig
import com.shilapi.xcertplay.media.dsp.DspSeatNearSpeaker
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
            seatAlignment = DspSeatAlignmentConfig(
                enabled = true,
                preset = DspSeatPreset.PASSENGER,
                nearSpeaker = DspSeatNearSpeaker.LEFT,
                distanceDifferenceCm = 90.0,
                leftTrimDb = 2.0,
                rightTrimDb = -3.0,
                invertRight = true,
            ),
            advancedMs = DspAdvancedMsConfig(
                enabled = true,
                crossoverHz = 315.0,
                centerFocusDb = 1.0,
                midLowGainDb = -2.0,
                midHighGainDb = 3.0,
                sideLowGainDb = -1.0,
                sideHighGainDb = 2.0,
                lowBandWidth = 0.0,
                highBandWidth = 1.5,
                sideCompressor = DspMsSideCompressorConfig(enabled = true, thresholdDb = -30.0, ratio = 3.0),
            ),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                measurement = DspCabinMeasurementSummary(
                    sourceSha256 = "a".repeat(64),
                    pointCount = 25,
                    minimumFrequencyHz = 20.0,
                    maximumFrequencyHz = 320.0,
                    noiseFloorDb = -80.0,
                    clippingDetected = false,
                ),
                notches = listOf(
                    DspCabinCorrectionNotch(enabled = true, frequencyHz = 80.0, gainDb = -6.0, q = 4.0),
                ),
            ),
            harmonic = DspHarmonicConfig(
                enabled = true,
                mode = DspHarmonicMode.ODD,
                quality = DspHarmonicQuality.HIGH,
                exciterEnabled = true,
                exciterAmount = 0.3,
                exciterCutoffHz = 9_000.0,
            ),
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
    fun impulseResponseReferenceScanIncludesSavedCustomProfiles() {
        val repository = repository()
        val impulseId = "custom_profile_ir"
        val profile = DspProfilePresets.customProfile("custom1").copy(
            convolver = DspConvolverConfig(enabled = true, impulseResponseId = impulseId),
        )

        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        assertTrue(repository.referencesImpulseResponse(impulseId))
        assertFalse(repository.referencesImpulseResponse("other_ir"))
    }

    @Test
    fun oversizedCustomProfileIsRejectedWithoutReplacingItsFile() {
        val repository = repository()
        val target = requireNotNull(repository.profileFileForTesting("custom1"))
        target.parentFile!!.mkdirs()
        val original = ByteArray(1_048_577) { (it % 251).toByte() }
        target.writeBytes(original)

        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        assertEquals(DspProfileSaveResult.WRITE_FAILED, repository.save(DspProfilePresets.customProfile("custom1")))
        assertTrue(target.readBytes().contentEquals(original))
    }

    @Test
    fun profileStoreRejectsSymlinkedDirectoryOutsideAppFiles() {
        val root = temporaryFolder.newFolder("profile-root")
        val repository = DspProfileRepository(root)
        val outside = temporaryFolder.newFolder("outside-profile-store")
        repository.profilesDirectory.parentFile!!.mkdirs()
        Files.createSymbolicLink(repository.profilesDirectory.toPath(), outside.toPath())

        assertEquals(null, repository.profileFileForTesting("custom1"))
        assertEquals(DspProfileSaveResult.INVALID_PROFILE_ID, repository.save(DspProfilePresets.customProfile("custom1")))
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
        assertEquals(profile.seatAlignment, enabled.seatAlignment)
        assertEquals(profile.advancedMs, enabled.advancedMs)
        assertEquals(profile.cabinCorrection, enabled.cabinCorrection)
        assertEquals(profile.harmonic, enabled.harmonic)
        assertEquals(profile.stereoWidth, enabled.stereoWidth, 0.0)
        assertEquals(profile.monoBass, enabled.monoBass)
        assertEquals(profile.convolver, enabled.convolver)
        assertEquals(profile.multiband, enabled.multiband)
        assertEquals(profile.dynamicEq, enabled.dynamicEq)
        assertTrue(runCatching { (enabled.peqBands as MutableList).clear() }.isFailure)
    }

    @Test
    fun activePeqAndCabinCutsShareOneFifteenBandCapacityOnSaveAndLoad() {
        val repository = repository()
        val exactlyFifteen = DspProfilePresets.customProfile("custom1").copy(
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
        val overCapacity = exactlyFifteen.copy(
            eqBands = exactlyFifteen.eqBands + DspEqBand(DspEqType.PEAK, 1_500.0, gainDb = -1.0),
        )

        assertEquals(15, exactlyFifteen.activeEqBandCount())
        assertTrue(exactlyFifteen.hasValidEqCapacity())
        assertEquals(DspProfileSaveResult.SAVED, repository.save(exactlyFifteen))
        assertEquals(exactlyFifteen, (repository.load("custom1") as DspProfileReadResult.Loaded).profile)
        assertEquals(16, overCapacity.activeEqBandCount())
        assertFalse(overCapacity.hasValidEqCapacity())
        assertEquals(DspProfileSaveResult.INVALID_EQ_CAPACITY, repository.save(overCapacity))
        assertEquals(exactlyFifteen, (repository.load("custom1") as DspProfileReadResult.Loaded).profile)
        assertTrue(runCatching { overCapacity.toRuntimeConfig(masterEnabled = true) }.isFailure)
    }

    @Test
    fun storedOverCapacityProfileIsRejectedOnLoadAndSavedStateRestore() {
        val repository = repository()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        val bands = List(13) { index ->
            """{"type":"PEAK","frequencyHz":${100 + index * 100},"gainDb":-1,"q":1,"enabled":true}"""
        }.joinToString(",")
        val notches = List(3) { index ->
            """{"enabled":true,"frequencyHz":${80 + index * 40},"gainDb":-3,"q":4}"""
        }.joinToString(",")
        val storedInvalidProfile =
            """{"schemaVersion":7,"id":"custom1","name":"Over capacity","peq":[$bands],"cabinCorrection":{"enabled":true,"notches":[$notches]}}"""
        profileFile.writeText(storedInvalidProfile)

        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        assertEquals(null, repository.decodeDraftFromSavedState(storedInvalidProfile))
    }

    @Test
    fun rollingBackEachAddedModulePreservesLegacyConfigAndOtherProfiles() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profile = DspProfilePresets.customProfile("custom1").copy(
            preampDb = -2.5,
            eqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 2.0)),
            bass = DspBassConfig(enabled = true, gainDb = 3.0, frequencyHz = 90.0),
            seatAlignment = DspSeatAlignmentConfig(
                enabled = true,
                preset = DspSeatPreset.DRIVER,
                nearSpeaker = DspSeatNearSpeaker.LEFT,
                distanceDifferenceCm = 34.3,
            ),
            advancedMs = DspAdvancedMsConfig(enabled = true, sideLowGainDb = 1.0),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                notches = listOf(DspCabinCorrectionNotch(enabled = true, frequencyHz = 80.0, gainDb = -3.0)),
            ),
            harmonic = DspHarmonicConfig(enabled = true, exciterEnabled = true, exciterAmount = 0.3),
        )
        val otherProfile = DspProfilePresets.customProfile("custom2").copy(preampDb = 4.0)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        assertEquals(DspProfileSaveResult.SAVED, repository.save(otherProfile))

        val rollbackProfiles = listOf(
            profile.copy(seatAlignment = profile.seatAlignment.copy(enabled = false)),
            profile.copy(advancedMs = profile.advancedMs.copy(enabled = false)),
            profile.copy(cabinCorrection = profile.cabinCorrection.copy(enabled = false)),
            profile.copy(harmonic = profile.harmonic.copy(enabled = false)),
        )
        for (rolledBackProfile in rollbackProfiles) {
            assertEquals(DspProfileSaveResult.SAVED, repository.save(rolledBackProfile))
            assertEquals(
                rolledBackProfile,
                (repository.load("custom1") as DspProfileReadResult.Loaded).profile,
            )
            assertEquals(
                otherProfile,
                (repository.load("custom2") as DspProfileReadResult.Loaded).profile,
            )
            assertEquals(DspProfileSaveResult.SAVED, repository.save(profile))
        }
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
            """{"schemaVersion":4,"id":"custom1","seatAlignment":{"nearSpeaker":"UP"}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":4,"id":"custom1","seatAlignment":{"distanceDifferenceCm":172}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":5,"id":"custom1","advancedMs":{"highBandWidth":2.1}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":6,"id":"custom1","cabinCorrection":{"notches":[{"frequencyHz":500}]}}""",
        )
        assertEquals(DspProfileReadResult.Invalid, repository.load("custom1"))
        profileFile.writeText(
            """{"schemaVersion":7,"id":"custom1","harmonic":{"mode":"TERRIBLE"}}""",
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
        assertTrue("schema v1 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":7"))
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
        assertTrue("schema v2 saved profile=$savedProfile", savedProfile.contains("\"schemaVersion\":7"))
    }

    @Test
    fun versionThreeProfileMigratesWithSeatAlignmentDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":3,"id":"custom1","name":"Existing","enabled":true,"stereo":{"width":1.25}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(3, loaded.migratedFromVersion)
        assertFalse(loaded.profile.seatAlignment.enabled)
        assertEquals(DspSeatAlignmentConfig(), loaded.profile.seatAlignment)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":7"))
    }

    @Test
    fun versionFourProfileMigratesWithAdvancedMsDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":4,"id":"custom1","stereo":{"width":1.25,"monoBass":{"enabled":true,"cutoffHz":120}},"advancedMs":{"enabled":true}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(4, loaded.migratedFromVersion)
        assertFalse(loaded.profile.advancedMs.enabled)
        assertEquals(DspAdvancedMsConfig(), loaded.profile.advancedMs)
        assertEquals(1.25, loaded.profile.stereoWidth, 0.0)
        assertTrue(loaded.profile.monoBass.enabled)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":7"))
    }

    @Test
    fun versionFiveProfileMigratesWithCabinCorrectionDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":5,"id":"custom1","cabinCorrection":{"enabled":true,"notches":[{"enabled":true,"frequencyHz":80,"gainDb":-6,"q":4}]}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(5, loaded.migratedFromVersion)
        assertEquals(DspCabinCorrectionConfig(), loaded.profile.cabinCorrection)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":7"))
    }

    @Test
    fun versionSixProfileMigratesWithHarmonicEffectsDisabledByDefault() {
        val repository = repositoryWithDirectWriterForMigrationTests()
        val profileFile = requireNotNull(repository.profileFileForTesting("custom1"))
        assertTrue(profileFile.parentFile!!.mkdirs())
        profileFile.writeText(
            """{"schemaVersion":6,"id":"custom1","harmonic":{"enabled":true,"exciterEnabled":true,"exciterAmount":0.5}}""",
        )

        val loaded = repository.load("custom1") as DspProfileReadResult.Loaded

        assertEquals(6, loaded.migratedFromVersion)
        assertEquals(DspHarmonicConfig(), loaded.profile.harmonic)
        assertEquals(DspProfileSaveResult.SAVED, repository.save(loaded.profile))
        assertTrue(profileFile.readText().contains("\"schemaVersion\":7"))
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
