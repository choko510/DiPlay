package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AudioStreamCodecTest {
    @Test
    fun pcmDecoderKeepsAllAdvertisedRateMappings() {
        val formats = mapOf(
            0x4L to (8_000 to 1),
            0x8L to (8_000 to 2),
            0x10L to (16_000 to 1),
            0x20L to (16_000 to 2),
            0x40L to (24_000 to 1),
            0x80L to (24_000 to 2),
            0x100L to (32_000 to 1),
            0x200L to (32_000 to 2),
            0x400L to (44_100 to 1),
            0x800L to (44_100 to 2),
            0x4000L to (48_000 to 1),
            0x8000L to (48_000 to 2),
        )

        formats.forEach { (bits, expected) ->
            val format = AudioStreamCodec.fromFormatBits(bits, payloadType = 100, audioType = "default")
                ?: throw AssertionError("expected PCM format for 0x${bits.toString(16)}")

            assertEquals(AudioCodecKind.LPCM, format.codec)
            assertEquals(expected.first, format.sampleRate)
            assertEquals(expected.second, format.channels)
            assertEquals("default", format.audioType)
        }
    }

    @Test
    fun compressedFormatsKeepTheirSelectedRates() {
        val aac441 = AudioStreamCodec.fromFormatBits(0x400000L, payloadType = 102, audioType = "media")!!
        val aac480 = AudioStreamCodec.fromFormatBits(0x800000L, payloadType = 102, audioType = "media")!!
        val opus16 = AudioStreamCodec.fromFormatBits(0x10000000L, payloadType = 100, audioType = "telephony")!!
        val opus24 = AudioStreamCodec.fromFormatBits(0x20000000L, payloadType = 100, audioType = "telephony")!!
        val opus48 = AudioStreamCodec.fromFormatBits(0x40000000L, payloadType = 100, audioType = "telephony")!!

        assertEquals(AudioCodecKind.AAC_LC, aac441.codec)
        assertEquals(44_100, aac441.sampleRate)
        assertEquals(AudioCodecKind.AAC_LC, aac480.codec)
        assertEquals(48_000, aac480.sampleRate)
        assertEquals(AudioCodecKind.OPUS, opus16.codec)
        assertEquals(16_000, opus16.sampleRate)
        assertEquals(AudioCodecKind.OPUS, opus24.codec)
        assertEquals(24_000, opus24.sampleRate)
        assertEquals(AudioCodecKind.OPUS, opus48.codec)
        assertEquals(48_000, opus48.sampleRate)
        assertEquals(1, opus48.channels)
    }

    @Test
    fun rejectsUnknownAndMultiBitSelectedFormats() {
        listOf(
            0L,
            0x70000000L,
            0x30000000L,
            0x400000L or 0x800000L,
            0x10000000L or 0x20000000L,
            0x10000001L,
            0x4L or 0x8000L,
            0x02000000L,
        ).forEach { bits ->
            assertNull("expected rejection for 0x${bits.toString(16)}", AudioStreamCodec.fromFormatBits(bits, 100))
        }
    }

    @Test
    fun normalizesAudioTypeForDecodedFormat() {
        val format = AudioStreamCodec.fromFormatBits(0x10000000L, payloadType = 100, audioType = "Telephony")
        assertEquals("telephony", format?.audioType)
        assertNotNull(format)
    }
}
