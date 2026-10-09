package com.shilapi.xcertplay.dsp

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspCabinCalibrationParserTest {
    @Test
    fun csvImportsRequiredMetadataAndSortedFrequencyResponse() {
        val data = DspCabinCalibrationParser.parse("cal_front", ByteArrayInputStream(csv().toByteArray()))

        assertEquals("cal_front", data.id)
        assertEquals(1, data.formatVersion)
        assertEquals(48_000, data.sampleRate)
        assertEquals("front seat", data.position)
        assertEquals("2026-10-09", data.measuredAt)
        assertTrue(data.microphoneCorrectionApplied)
        assertEquals(5, data.points.size)
        assertEquals(82.0, data.points[2].frequencyHz, 0.0)
    }

    @Test
    fun jsonImportsStrictMetadataAndObjectOrPairPoints() {
        val objectPoints = """
            {"format":"diplay-cabin-calibration-v1","formatVersion":1,"sampleRate":48000,
             "position":"front","measuredAt":"2026-10-09","microphoneCorrectionApplied":false,
             "points":[{"frequencyHz":40,"magnitudeDb":-1},{"frequencyHz":60,"magnitudeDb":0},
             {"frequencyHz":90,"magnitudeDb":4},{"frequencyHz":150,"magnitudeDb":0},
             {"frequencyHz":250,"magnitudeDb":-2}]}
        """.trimIndent()
        val pairPoints = """
            {"format":"diplay-cabin-calibration-v1","formatVersion":1,"sampleRate":48000,
             "position":"front","measuredAt":"2026-10-09","microphoneCorrectionApplied":false,
             "points":[[40,-1],[60,0],[90,4],[150,0],[250,-2]]}
        """.trimIndent()

        val objectData = DspCabinCalibrationParser.parseJson("cal_json", objectPoints)
        val pairData = DspCabinCalibrationParser.parseJson("cal_pair", pairPoints)

        assertFalse(objectData.microphoneCorrectionApplied)
        assertEquals(objectData.points, pairData.points)
    }

    @Test
    fun malformedNumbersDuplicatesUnknownPropertiesAndUnsortedRowsAreRejected() {
        assertRejected(csv().replace("82,5.0", "82,NaN"))
        assertRejected(csv().replace("82,5.0", "82,5.0\n82,4.0"))
        assertRejected(csv().replace("# position=front seat", "# position=front seat\n# sampleRate=48000"))
        assertRejected(csv().replace("frequencyHz,magnitudeDb", "frequencyHz,magnitudeDb\nextra"))
        assertRejected(
            """{"format":"diplay-cabin-calibration-v1","formatVersion":1,"sampleRate":48000,"sampleRate":48000,"position":"front","measuredAt":"today","microphoneCorrectionApplied":false,"points":[]}""",
        )
        assertRejected(csv().replace("82,5.0", "82,5.0\n81,4.0"))
    }

    @Test
    fun inputSizeIsBoundedBeforeParsing() {
        val input = ByteArray(DspCabinCalibrationData.MAX_INPUT_BYTES + 1)
        val error = runCatching { DspCabinCalibrationParser.parse("cal_large", ByteArrayInputStream(input)) }
            .exceptionOrNull()

        assertTrue(error is DspCabinMeasurementTooLargeException)
    }

    private fun assertRejected(text: String) {
        val error = runCatching { DspCabinCalibrationParser.parse("cal_invalid", ByteArrayInputStream(text.toByteArray())) }
            .exceptionOrNull()
        assertTrue("Expected invalid measurement, got $error", error is IllegalArgumentException)
    }

    private fun csv(): String = """
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
    """.trimIndent()
}
