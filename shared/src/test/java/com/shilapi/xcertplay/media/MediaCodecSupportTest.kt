package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.io.ByteArrayOutputStream

class MediaCodecSupportTest {
    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    @Test
    fun avcCodecSpecificDataKeepsSpsAndPpsAtTheirFixedIndices() {
        val sps = byteArrayOf(0x67, 0x64)
        val pps = byteArrayOf(0x68, 0xee.toByte())

        val csd = MediaCodecSupport.avcCodecSpecificData(avcRecord(sps, pps))

        assertEquals(listOf(0, 1), csd.map { it.index })
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, csd[0].bytes)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, csd[1].bytes)
    }

    @Test
    fun missingAvcSpsDoesNotShiftPpsIntoCsdZero() {
        val pps = byteArrayOf(0x68, 0xee.toByte())

        val csd = MediaCodecSupport.avcCodecSpecificData(avcRecord(null, pps))

        assertEquals(listOf(1), csd.map { it.index })
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, csd.single().bytes)
    }

    @Test
    fun adaptiveAvcConfigAndIdrShareOneAccessUnitWithEveryParameterSet() {
        val sequenceSets = listOf(byteArrayOf(0x67, 0x64), byteArrayOf(0x67, 0x4d))
        val pictureSets = listOf(byteArrayOf(0x68, 0xee.toByte()), byteArrayOf(0x68, 0x3c))
        val config = avcRecordWithSets(sequenceSets, pictureSets)
        val csd = MediaCodecSupport.adaptiveCodecSpecificData(VideoCodec.H264, config)
        val idr = byteArrayOf(0, 0, 0, 2, 0x65, 0x11)

        assertArrayEquals(
            sequenceSets.fold(ByteArray(0)) { result, set -> result + byteArrayOf(0, 0, 0, 1) + set } +
                pictureSets.fold(ByteArray(0)) { result, set -> result + byteArrayOf(0, 0, 0, 1) + set },
            csd,
        )
        assertArrayEquals(csd + byteArrayOf(0, 0, 0, 1, 0x65, 0x11),
            MediaCodecSupport.combineAdaptiveConfigAndKeyFrame(VideoCodec.H264, csd, idr))
        assertNull(
            MediaCodecSupport.combineAdaptiveConfigAndKeyFrame(
                VideoCodec.H264,
                csd,
                byteArrayOf(0, 0, 0, 2, 0x41, 0x11),
            ),
        )
    }

    @Test
    fun adaptiveHevcConfigAndIrapShareOneAccessUnit() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val csd = MediaCodecSupport.adaptiveCodecSpecificData(VideoCodec.H265, hevcRecord(vps, sps, pps))
        val irap = byteArrayOf(0, 0, 0, 3, 0x26, 0x01, 0x55)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x55),
            MediaCodecSupport.combineAdaptiveConfigAndKeyFrame(VideoCodec.H265, csd, irap),
        )
    }

    @Test
    fun duplicateDecoderNameAndFormatIsSkippedWithoutDroppingOtherAttempts() {
        val attempts = mutableSetOf<DecoderAttemptKey>()

        assertTrue(recordDecoderAttempt(attempts, "software.avc", tuned = true))
        assertTrue(recordDecoderAttempt(attempts, "software.avc", tuned = false))
        assertFalse(recordDecoderAttempt(attempts, "software.avc", tuned = false))
        assertTrue(recordDecoderAttempt(attempts, "hardware.avc", tuned = false))
    }

    @Test
    fun tunedDecoderFailureFallsBackToMinimalDefaultFormat() {
        val tried = mutableListOf<DecoderAttempt>()
        val configured = firstSuccessfulDecoderAttempt(videoDecoderAttemptPlan("software.avc")) {
            tried += it
            if (it.codecName == null && !it.tuned) "minimal" else null
        }

        assertEquals("minimal", configured)
        assertEquals(
            listOf(
                DecoderAttempt(codecName = null, tuned = true),
                DecoderAttempt(codecName = null, tuned = false),
            ),
            tried,
        )
    }

    @Test
    fun defaultDecoderFailuresReachTheExplicitSoftwareFallback() {
        val tried = mutableListOf<DecoderAttempt>()
        val configured = firstSuccessfulDecoderAttempt(videoDecoderAttemptPlan("software.avc")) {
            tried += it
            if (it.codecName == "software.avc") "software" else null
        }

        assertEquals("software", configured)
        assertEquals(
            listOf(
                DecoderAttempt(codecName = null, tuned = true),
                DecoderAttempt(codecName = null, tuned = false),
                DecoderAttempt(codecName = "software.avc", tuned = false),
            ),
            tried,
        )
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = (32 + index).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }

    private fun avcRecord(sps: ByteArray?, pps: ByteArray?): ByteArray {
        val size = 7 + (sps?.size ?: 0) + (if (sps == null) 0 else 2) +
            (pps?.size ?: 0) + (if (pps == null) 0 else 2)
        val record = ByteArray(size)
        record[0] = 1
        record[5] = if (sps == null) 0 else 1
        var cursor = 6
        sps?.let {
            record[cursor++] = (it.size ushr 8).toByte()
            record[cursor++] = it.size.toByte()
            it.copyInto(record, cursor)
            cursor += it.size
        }
        record[cursor++] = if (pps == null) 0 else 1
        pps?.let {
            record[cursor++] = (it.size ushr 8).toByte()
            record[cursor++] = it.size.toByte()
            it.copyInto(record, cursor)
            cursor += it.size
        }
        return record.copyOf(cursor)
    }

    private fun avcRecordWithSets(sps: List<ByteArray>, pps: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), (0xe0 or sps.size).toByte()))
        sps.forEach { set ->
            output.write((set.size ushr 8) and 0xff)
            output.write(set.size and 0xff)
            output.write(set, 0, set.size)
        }
        output.write(pps.size)
        pps.forEach { set ->
            output.write((set.size ushr 8) and 0xff)
            output.write(set.size and 0xff)
            output.write(set, 0, set.size)
        }
        return output.toByteArray()
    }
}
