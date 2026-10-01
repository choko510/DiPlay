package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacRtpPayloadTest {
    @Test
    fun preservesRawAccessUnitBytes() {
        val raw = byteArrayOf(0x21, 0x10, 0x56, 0x7f)
        val parsed = AacRtpPayloadParser.parse(raw)!!
        assertEquals(AacPayloadMode.RAW, parsed.mode)
        assertArrayEquals(raw, parsed.accessUnits.single())
    }

    @Test
    fun fakeRfc3640PrefixDoesNotStripRawPayloadBytes() {
        val raw = byteArrayOf(0, 16, 0, 8, 1, 2)
        val parsed = AacRtpPayloadParser.parse(raw)!!
        assertEquals(AacPayloadMode.RAW, parsed.mode)
        assertArrayEquals(raw, parsed.accessUnits.single())
    }

    @Test
    fun extractsOneValidRfc3640AccessUnit() {
        val payload = byteArrayOf(0, 16, 0, 24, 1, 2, 3)
        val parsed = AacRtpPayloadParser.parse(payload)!!
        assertEquals(AacPayloadMode.RFC3640, parsed.mode)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.accessUnits.single())
    }

    @Test
    fun extractsMultipleConsistentRfc3640AccessUnits() {
        val payload = byteArrayOf(0, 32, 0, 16, 0, 16, 1, 2, 3, 4)
        val parsed = AacRtpPayloadParser.parse(payload)!!
        assertEquals(AacPayloadMode.RFC3640, parsed.mode)
        assertEquals(2, parsed.accessUnits.size)
        assertArrayEquals(byteArrayOf(1, 2), parsed.accessUnits[0])
        assertArrayEquals(byteArrayOf(3, 4), parsed.accessUnits[1])
    }

    @Test
    fun malformedOrAmbiguousHeadersRemainUntouchedRawPayloads() {
        val malformed = listOf(
            byteArrayOf(0, 17, 0, 8, 1),
            byteArrayOf(0, 32, 0, 8, 1),
            byteArrayOf(0, 16, 0xff.toByte(), 0xf8.toByte(), 1),
            byteArrayOf(0, 16, 0, 0, 1),
            byteArrayOf(0, 16, 0, 24, 1, 2),
            byteArrayOf(0, 16, 0, 8),
        )
        for (payload in malformed) {
            val parsed = AacRtpPayloadParser.parse(payload)!!
            assertEquals(AacPayloadMode.RAW, parsed.mode)
            assertArrayEquals(payload, parsed.accessUnits.single())
        }
    }

    @Test
    fun possibleFragmentedRfc3640UnitIsReportedWithoutStrippingPayload() {
        val payload = byteArrayOf(0, 16, 0, 24, 1, 2)
        val parsed = AacRtpPayloadParser.parse(payload)!!

        assertEquals(AacPayloadMode.RAW, parsed.mode)
        assertArrayEquals(payload, parsed.accessUnits.single())
        assertTrue(parsed.possibleFragmentation)
    }

    @Test
    fun rejectsEmptyPayload() {
        assertNull(AacRtpPayloadParser.parse(ByteArray(0)))
    }
}
