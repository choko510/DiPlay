package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayAudioCapabilitiesTest {
    @Test
    fun outputMasksMatchTheTransportAndStreamRole() {
        val wiredMedia = AirPlayAudioCapabilities.outputFormatMask(48_000, false, 100, "media")!!
        val wirelessMedia = AirPlayAudioCapabilities.outputFormatMask(48_000, true, 100, "media")!!
        val wirelessVoice = AirPlayAudioCapabilities.outputFormatMask(48_000, true, 100, "TeLePhOnY")!!
        val wirelessNavigation = AirPlayAudioCapabilities.outputFormatMask(48_000, true, 101, "default")!!

        assertEquals(0x8000L, wiredMedia)
        assertFalse((wiredMedia and AirPlayAudioCapabilities.OPUS_16_KHZ_MONO) != 0L)
        assertEquals(0xc3fcL, wirelessMedia)
        assertFalse((wirelessMedia and AirPlayAudioCapabilities.OPUS_FORMATS) != 0L)
        assertTrue((wirelessVoice and AirPlayAudioCapabilities.OPUS_FORMATS) == AirPlayAudioCapabilities.OPUS_FORMATS)
        assertTrue((wirelessNavigation and AirPlayAudioCapabilities.OPUS_FORMATS) == AirPlayAudioCapabilities.OPUS_FORMATS)
        assertEquals(
            0x800000L,
            AirPlayAudioCapabilities.outputFormatMask(48_000, true, 102, "media"),
        )
        assertEquals(
            0x400000L,
            AirPlayAudioCapabilities.outputFormatMask(44_100, true, 102, "MEDIA"),
        )
        assertNull(AirPlayAudioCapabilities.outputFormatMask(48_000, false, 102, "media"))
        assertNull(AirPlayAudioCapabilities.outputFormatMask(48_000, true, 102, "default"))
    }

    @Test
    fun infoPlistAndSetupValidationShareEveryAdvertisedOutputMask() {
        listOf(44_100, 48_000).forEach { rate ->
            listOf(false, true).forEach { wireless ->
                val advertised = AirPlayAudioCapabilities.advertisedOutputFormats(rate, wireless)
                val infoConfig = AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:01",
                    sourceVersion = "1.0",
                    main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    entertainmentSampleRate = rate,
                    wirelessAudio = wireless,
                )
                val infoEntries = AirPlayInfoPlist.build(infoConfig)["audioFormats"] as List<*>
                advertised.forEach { capability ->
                    assertEquals(
                        capability.outputFormats,
                        AirPlayAudioCapabilities.outputFormatMask(
                            rate,
                            wireless,
                            capability.type,
                            capability.audioType,
                        ),
                    )
                    val infoEntry = infoEntries
                        .mapNotNull { it as? Map<*, *> }
                        .single {
                            (it["type"] as Number).toInt() == capability.type &&
                                normalizeAudioType(it["audioType"].toString()) ==
                                normalizeAudioType(capability.audioType)
                        }
                    assertEquals(capability.outputFormats.toInt(), (infoEntry["audioOutputFormats"] as Number).toInt())
                    KNOWN_SINGLE_FORMATS.filter { bits -> capability.outputFormats and bits == bits }.forEach { bits ->
                        assertNotNull(AudioStreamCodec.fromFormatBits(bits, capability.type, capability.audioType))
                    }
                }
            }
        }
    }

    @Test
    fun knownButUnadvertisedFormatIsRejectedByCapabilityCheck() {
        val opus = AudioStreamCodec.fromFormatBits(
            AirPlayAudioCapabilities.OPUS_16_KHZ_MONO,
            payloadType = 100,
            audioType = "media",
        )
        assertNotNull(opus)
        val wiredMedia = AirPlayAudioCapabilities.outputFormatMask(48_000, false, 100, "media")!!
        assertFalse(
            (wiredMedia and AirPlayAudioCapabilities.OPUS_16_KHZ_MONO) ==
                AirPlayAudioCapabilities.OPUS_16_KHZ_MONO,
        )
    }

    private companion object {
        val KNOWN_SINGLE_FORMATS = listOf(
            0x4L, 0x8L, 0x10L, 0x20L, 0x40L, 0x80L, 0x100L, 0x200L,
            0x400L, 0x800L, 0x4000L, 0x8000L,
            AirPlayAudioCapabilities.AAC_LC_44_1_KHZ_STEREO,
            AirPlayAudioCapabilities.AAC_LC_48_KHZ_STEREO,
            AirPlayAudioCapabilities.OPUS_16_KHZ_MONO,
            AirPlayAudioCapabilities.OPUS_24_KHZ_MONO,
            AirPlayAudioCapabilities.OPUS_48_KHZ_MONO,
        )
    }
}
