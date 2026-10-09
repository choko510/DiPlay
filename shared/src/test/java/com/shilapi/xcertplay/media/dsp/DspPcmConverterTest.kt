package com.shilapi.xcertplay.media.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPcmConverterTest {
    @Test
    fun pcm8MapsUnsignedBoundariesToCanonicalFloat() {
        val decoded = decode(
            byteArrayOf(0, 127, 128.toByte(), 0xff.toByte()),
            DspPcmEncoding.PCM8,
            DspAudioFormat(48_000, 1),
        )

        assertArrayEquals(floatArrayOf(-1f, -1f / 128f, 0f, 127f / 128f), decoded, 0f)
    }

    @Test
    fun pcm16MapsSignedBoundariesToCanonicalFloat() {
        val decoded = decode(
            byteArrayOf(0x00, 0x80.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00, 0x00, 0xff.toByte(), 0x7f),
            DspPcmEncoding.PCM16,
            DspAudioFormat(48_000, 1),
        )

        assertArrayEquals(floatArrayOf(-1f, -1f / 32768f, 0f, 32767f / 32768f), decoded, 0f)
    }

    @Test
    fun packedPcm24SignExtendsAndScalesCorrectly() {
        val decoded = decode(
            byteArrayOf(
                0x00, 0x00, 0x80.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0x00, 0x00, 0x00,
                0xff.toByte(), 0xff.toByte(), 0x7f,
            ),
            DspPcmEncoding.PCM24_PACKED,
            DspAudioFormat(48_000, 1),
        )

        assertArrayEquals(floatArrayOf(-1f, -1f / 8_388_608f, 0f, 8_388_607f / 8_388_608f), decoded, 0f)
    }

    @Test
    fun pcm32UsesTheFullSignedRange() {
        val decoded = decode(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x80.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0x00, 0x00, 0x00, 0x00,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f,
            ),
            DspPcmEncoding.PCM32,
            DspAudioFormat(48_000, 1),
        )

        assertArrayEquals(floatArrayOf(-1f, -1f / 2_147_483_648f, 0f, 2_147_483_647f / 2_147_483_648f), decoded, 0f)
    }

    @Test
    fun pcmFloatSanitizesNanAndInfinities() {
        val diagnostics = DspDiagnostics()
        val decoded = decode(
            floatBytes(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.25f),
            DspPcmEncoding.PCM_FLOAT,
            DspAudioFormat(48_000, 1),
            diagnostics,
        )

        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0.25f), decoded, 0f)
        assertEquals(3L, diagnostics.nonFiniteInputSamples)
    }

    @Test
    fun incompleteInputFrameIsNotReadPastTheCompletePrefix() {
        val destination = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        val diagnostics = DspDiagnostics()
        val frames = DspPcmConverter.pcmToFloat(
            source = byteArrayOf(0x00, 0x40, 0x00, 0xc0.toByte(), 0x7f),
            offset = 0,
            length = 5,
            encoding = DspPcmEncoding.PCM16,
            format = DspAudioFormat(48_000, 2),
            destination = destination,
            diagnostics = diagnostics,
        )

        assertEquals(1, frames)
        destination.flip()
        assertEquals(0.5f, destination.float, 0f)
        assertEquals(-0.5f, destination.float, 0f)
        assertEquals(0L, diagnostics.incompleteFrameBytes)
    }

    @Test
    fun pcm16QuantizationRoundsAndSaturatesBeforeWritingLittleEndian() {
        val values = floatBuffer(
            -2f,
            -0.51f / 32768f,
            -0.49f / 32768f,
            0f,
            0.49f / 32768f,
            0.51f / 32768f,
            2f,
        )
        val output = ByteArray(14)

        assertTrue(
            DspPcmConverter.floatToPcm16(
                source = values,
                frames = 7,
                format = DspAudioFormat(48_000, 1),
                destination = output,
                offset = 0,
                dither = null,
            ),
        )

        assertArrayEquals(
            shortArrayOf(-32768, -1, 0, 0, 0, 1, 32767).flatMap { short ->
                listOf(short.toByte(), (short.toInt() shr 8).toByte())
            }.toByteArray(),
            output,
        )
    }

    @Test
    fun pcm16OutputSanitizesNonFiniteProcessorSamples() {
        val diagnostics = DspDiagnostics()
        val output = ByteArray(4)

        assertTrue(
            DspPcmConverter.floatToPcm16(
                source = floatBuffer(Float.NaN, Float.POSITIVE_INFINITY),
                frames = 2,
                format = DspAudioFormat(48_000, 1),
                destination = output,
                offset = 0,
                dither = null,
                diagnostics = diagnostics,
            ),
        )

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), output)
        assertEquals(2L, diagnostics.nonFiniteOutputSamples)
    }

    @Test
    fun fixedSeedDitherIsRepeatableAndHasNoMeasurableNearZeroBias() {
        val samples = FloatArray(32_768)
        val format = DspAudioFormat(48_000, 1)
        val first = ByteArray(samples.size * Short.SIZE_BYTES)
        val second = ByteArray(samples.size * Short.SIZE_BYTES)

        assertTrue(DspPcmConverter.floatToPcm16(floatBuffer(*samples), samples.size, format, first, 0, DspTpdfDither(0x51a7L)))
        assertTrue(DspPcmConverter.floatToPcm16(floatBuffer(*samples), samples.size, format, second, 0, DspTpdfDither(0x51a7L)))

        assertArrayEquals(first, second)
        var sum = 0L
        for (index in samples.indices) {
            val low = first[index * 2].toInt() and 0xff
            val high = first[index * 2 + 1].toInt()
            sum += ((high shl 8) or low).toShort().toInt()
        }
        assertTrue("mean error was ${sum.toDouble() / samples.size} LSB", kotlin.math.abs(sum.toDouble() / samples.size) < 0.05)
        assertFalse(first.all { it == 0.toByte() })
    }

    private fun decode(
        bytes: ByteArray,
        encoding: DspPcmEncoding,
        format: DspAudioFormat,
        diagnostics: DspDiagnostics = DspDiagnostics(),
    ): FloatArray {
        val destination = ByteBuffer.allocateDirect(bytes.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val frames = DspPcmConverter.pcmToFloat(
            source = bytes,
            offset = 0,
            length = bytes.size,
            encoding = encoding,
            format = format,
            destination = destination,
            diagnostics = diagnostics,
        )
        assertTrue(frames >= 0)
        destination.flip()
        return FloatArray(frames * format.channels) { destination.float }
    }

    private fun floatBytes(vararg values: Float): ByteArray = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    private fun floatBuffer(vararg values: Float): ByteBuffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .apply { values.forEach(::putFloat); flip() }
}
