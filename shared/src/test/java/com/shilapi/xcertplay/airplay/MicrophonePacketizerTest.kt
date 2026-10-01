package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }

    @Test
    fun opusMicrophoneFramesAndRtpTimestampsUseTheNegotiatedSampleRate() {
        listOf(
            Triple(16_000, 320, 640),
            Triple(24_000, 480, 960),
            Triple(48_000, 960, 1_920),
        ).forEach { (rate, samples, bytes) ->
            val config = MicrophoneConfig(
                audioType = "telephony",
                sampleRate = rate,
                channels = 1,
                payloadType = 100,
                frameMillis = 20,
                host = InetAddress.getLoopbackAddress(),
                port = 1,
                key = ByteArray(32),
                codec = AudioCodecKind.OPUS,
            )
            val encoder = com.shilapi.xcertplay.media.OpusEncoderConfiguration(
                sampleRate = rate,
                channels = 1,
                bitrate = 48_000,
                frameMillis = 20,
            )
            val counters = MicrophoneCounters()

            assertEquals(samples, config.samplesPerPacket)
            assertEquals(bytes, config.frameBytes)
            assertEquals(rate, encoder.sampleRate)
            assertEquals(1, encoder.channels)
            assertEquals(samples, encoder.samplesPerFrame)
            assertEquals(bytes, encoder.frameBytes)
            assertEquals(20_000L, encoder.frameDurationUs)

            val first = MicrophonePacketizer.sealPacket(
                config.key,
                config.payloadType,
                counters,
                byteArrayOf(0x01),
                config.samplesPerPacket,
            )
            val second = MicrophonePacketizer.sealPacket(
                config.key,
                config.payloadType,
                counters,
                byteArrayOf(0x02),
                config.samplesPerPacket,
            )

            assertEquals(0, rtpTimestamp(first))
            assertEquals(samples, rtpTimestamp(second))
            assertEquals(samples * 2, counters.timestamp)
        }
    }

    private fun rtpTimestamp(packet: ByteArray): Int =
        ((packet[4].toInt() and 0xff) shl 24) or
            ((packet[5].toInt() and 0xff) shl 16) or
            ((packet[6].toInt() and 0xff) shl 8) or
            (packet[7].toInt() and 0xff)
}
