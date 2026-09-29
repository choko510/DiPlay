package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
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

            assertEquals(AudioCodecKind.LPCM, format.codec)
            assertEquals(expected.first, format.sampleRate)
            assertEquals(expected.second, format.channels)
            assertEquals("default", format.audioType)
        }
    }

    @Test
    fun compressedDecoderMappingsRemainAvailable() {
        val aac441 = AudioStreamCodec.fromFormatBits(0x400000L, payloadType = 102, audioType = "media")
        val aac480 = AudioStreamCodec.fromFormatBits(0x800000L, payloadType = 102, audioType = "media")
        val opus = AudioStreamCodec.fromFormatBits(0x70000000L, payloadType = 100, audioType = "telephony")

        assertEquals(AudioCodecKind.AAC_LC, aac441.codec)
        assertEquals(44_100, aac441.sampleRate)
        assertEquals(AudioCodecKind.AAC_LC, aac480.codec)
        assertEquals(48_000, aac480.sampleRate)
        assertEquals(AudioCodecKind.OPUS, opus.codec)
        assertEquals(48_000, opus.sampleRate)
        assertEquals(1, opus.channels)
    }
}
