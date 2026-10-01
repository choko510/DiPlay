package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpReorderBufferTest {
    @Test
    fun deliversInOrderAndReordersSmallGaps() {
        val ordered = RtpReorderBuffer<String>(8, 10)
        assertEquals(listOf("a"), ordered.offer(1, "a", 0).values())
        assertEquals(listOf("b"), ordered.offer(2, "b", 1).values())
        assertEquals(listOf("c"), ordered.offer(3, "c", 2).values())

        val reordered = RtpReorderBuffer<String>(8, 10)
        assertEquals(listOf("1"), reordered.offer(1, "1", 0).values())
        assertEquals(emptyList<String>(), reordered.offer(3, "3", 1).values())
        assertEquals(listOf("2", "3"), reordered.offer(2, "2", 2).values())
        assertEquals(1, reordered.stats().reordered.toInt())
    }

    @Test
    fun ignoresDuplicatesAndLatePackets() {
        val buffer = RtpReorderBuffer<String>(8, 10)
        assertEquals(listOf("1"), buffer.offer(1, "1", 0).values())
        assertEquals(emptyList<String>(), buffer.offer(1, "duplicate", 1).values())
        assertEquals(emptyList<String>(), buffer.offer(0, "late", 2).values())
        assertEquals(listOf("2"), buffer.offer(2, "2", 3).values())
        assertEquals(1, buffer.stats().duplicates.toInt())
        assertEquals(1, buffer.stats().late.toInt())
        assertEquals(4, buffer.stats().received.toInt())
        assertEquals(2, buffer.stats().delivered.toInt())
        assertEquals(0, buffer.stats().maxGap)
    }

    @Test
    fun duplicatePendingOutOfOrderPacketDoesNotIncrementReorderedAgain() {
        val buffer = RtpReorderBuffer<String>(8, 10)
        assertEquals(listOf("1"), buffer.offer(1, "1", 0).values())
        assertEquals(emptyList<String>(), buffer.offer(3, "3", 1).values())
        assertEquals(emptyList<String>(), buffer.offer(3, "3 duplicate", 2).values())

        assertEquals(1, buffer.stats().reordered.toInt())
        assertEquals(1, buffer.stats().duplicates.toInt())
    }

    @Test
    fun handlesSequenceWraparound() {
        val buffer = RtpReorderBuffer<Int>(8, 10)
        val output = listOf(65534, 65535, 0, 1).flatMapIndexed { index, sequence ->
            buffer.offer(sequence, index, index.toLong())
        }
        assertEquals(listOf(0, 1, 2, 3), output.map { it.value })
    }

    @Test
    fun waitsForMissingPacketAndThenDeclaresLossAfterHoldTime() {
        val reordered = RtpReorderBuffer<String>(8, 10)
        reordered.offer(10, "10", 0)
        assertEquals(emptyList<String>(), reordered.offer(12, "12", 1).values())
        assertEquals(emptyList<String>(), reordered.offer(13, "13", 2).values())
        assertEquals(emptyList<String>(), reordered.poll(10).values())
        assertEquals(listOf("11", "12", "13"), reordered.offer(11, "11", 11).values())
        assertEquals(2, reordered.stats().maxGap)

        val lost = RtpReorderBuffer<String>(8, 10)
        lost.offer(10, "10", 0)
        lost.offer(12, "12", 1)
        lost.offer(13, "13", 2)
        assertEquals(listOf("12", "13"), lost.poll(11).values())
        assertEquals(1, lost.stats().lost.toInt())
    }

    @Test
    fun overflowIsBoundedAndFlushDoesNotWait() {
        val buffer = RtpReorderBuffer<Int>(3, Long.MAX_VALUE)
        buffer.offer(1, 1, 0)
        buffer.offer(3, 3, 1)
        buffer.offer(4, 4, 2)
        val overflowRelease = buffer.offer(5, 5, 3)
        assertEquals(3, buffer.stats().maxReorderDepth)
        assertEquals(listOf(3, 4, 5), overflowRelease.map { it.value })
        assertEquals(emptyList<Int>(), buffer.flush().map { it.value })
        assertEquals(1, buffer.stats().lost.toInt())
        buffer.clear()
        assertEquals(0, buffer.pendingCount)
    }

    @Test
    fun parsesRtpSequenceAndUnsignedTimestamp() {
        val packet = ByteArray(12)
        packet[2] = 0x12
        packet[3] = 0x34
        packet[4] = 0xff.toByte()
        packet[5] = 0xff.toByte()
        packet[6] = 0xff.toByte()
        packet[7] = 0xfe.toByte()
        val header = parseRtpHeader(packet)
        assertEquals(0x1234, header?.sequenceNumber)
        assertEquals(0xffff_fffeL, header?.timestamp)
        assertNull(parseRtpHeader(ByteArray(11)))
    }

    private fun <T> List<RtpDelivery<T>>.values(): List<T> = map { it.value }
}
