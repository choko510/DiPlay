package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test
    fun outputOffsetsSatisfyObservedFourByteNtbAlignment() {
        val frame = ByteArray(128)
        val block = Ntb16Codec.build(frame, 0)
        val blockLength = readU16(block, 8)
        val ndpOffset = readU16(block, 10)
        val datagramOffset = readU16(block, 20)

        assertEquals(0, ndpOffset % 4)
        assertEquals(0, datagramOffset % 4)
        assertEquals(28, datagramOffset)
        assertEquals(frame.size, readU16(block, 22))
        assertEquals(12 + 16 + frame.size, blockLength)
        assertEquals(true, blockLength <= 32_764)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)
}
