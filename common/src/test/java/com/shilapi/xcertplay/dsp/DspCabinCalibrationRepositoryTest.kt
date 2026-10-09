package com.shilapi.xcertplay.dsp

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspCabinCalibrationRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalizedMeasurementIsSavedOutsideTheProfileAndCanBeDeleted() {
        val repository = DspCabinCalibrationRepository(temporaryFolder.newFolder())
        val imported = repository.import("cal_front", ::measurementStream)

        assertTrue(imported is DspCabinCalibrationImportResult.Imported)
        val loaded = repository.load("cal_front")
        assertNotNull(loaded)
        assertEquals(48_000, loaded?.sampleRate)
        assertEquals("front seat", loaded?.position)
        assertTrue(repository.available().any { it.id == "cal_front" })
        assertEquals(DspCabinCalibrationImportResult.AlreadyExists, repository.import("cal_front", ::measurementStream))
        assertTrue(repository.delete("cal_front"))
        assertFalse(repository.contains("cal_front"))
    }

    @Test
    fun invalidOversizedAndUnsafeIdsDoNotCreateCalibrationFiles() {
        val repository = DspCabinCalibrationRepository(temporaryFolder.newFolder())
        assertEquals(
            DspCabinCalibrationImportResult.InvalidMeasurement,
            repository.import("../escape", ::measurementStream),
        )
        assertEquals(
            DspCabinCalibrationImportResult.InvalidMeasurement,
            repository.import("cal_bad", { ByteArrayInputStream("not a response".toByteArray()) }),
        )
        val oversized = ByteArray(DspCabinCalibrationData.MAX_INPUT_BYTES + 1)
        assertEquals(
            DspCabinCalibrationImportResult.TooLarge,
            repository.import("cal_large", { ByteArrayInputStream(oversized) }),
        )
        assertTrue(repository.available().isEmpty())
    }

    private fun measurementStream(): ByteArrayInputStream = ByteArrayInputStream(
        """
            # format=diplay-cabin-calibration-v1
            # formatVersion=1
            # sampleRate=48000
            # position=front seat
            # measuredAt=2026-10-09
            # microphoneCorrectionApplied=true
            frequencyHz,magnitudeDb
            40,-1.0
            60,0.0
            82,5.0
            150,0.0
            250,-2.0
        """.trimIndent().toByteArray(),
    )
}
