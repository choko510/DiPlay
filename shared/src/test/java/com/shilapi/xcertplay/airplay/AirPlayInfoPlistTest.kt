package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayInfoPlistTest {
    @Test
    fun defaultDisplayIncludesFullViewAndSafeAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(0, display["initialViewArea"])
        assertEquals(1280, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        assertEquals(0, view["originXPixels"])
        assertEquals(0, view["originYPixels"])
        assertEquals(1, display["primaryInputDevice"])
        assertNotNull(safe)
        assertEquals(1280, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun hevcCapabilityIsAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        assertFalse(AirPlayInfoPlist.build(base).containsKey("hevcInfo"))
        assertTrue(AirPlayInfoPlist.build(base.copy(hevc = true)).containsKey("hevcInfo"))
    }

    @Test
    fun drivingSideAndDisplayValuesAreAdvertised() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1280,
                    heightPixels = 720,
                    fps = 37,
                    widthPhysicalMm = 125,
                ),
                rightHandDrive = true,
                manufacturer = "Example",
                model = "HeadUnit",
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["rightHandDrive"])
        assertEquals("Example", info["manufacturer"])
        assertEquals("HeadUnit", info["model"])
        assertEquals(35, display["maxFPS"])
        assertEquals(125, display["widthPhysical"])
        assertEquals(70, display["heightPhysical"])
    }

    @Test
    fun squareOemIconIsAdvertisedWithItsOriginalBytes() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                icons = listOf(AirPlayIcon(1, 1, iconBytes)),
                oemLabel = "xcertplay",
            ),
        )

        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["oemIconVisible"])
        assertEquals("xcertplay", info["oemIconLabel"])
        assertEquals(1, icon["widthPixels"])
        assertEquals(1, icon["heightPixels"])
        assertTrue((icon["imageData"] as ByteArray).contentEquals(iconBytes))
    }

    @Test
    fun safeAreaInsetsReachTheAirPlayViewArea() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 960,
                    heightPixels = 540,
                    safeArea = AirPlayInsets(top = 10, bottom = 15, left = 20, right = 25),
                    safeAreaDrawOutside = false,
                ),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(915, safe["widthPixels"])
        assertEquals(515, safe["heightPixels"])
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(false, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun reportedSafeAreaUsesActivityMappingAndEvenAlignment() {
        val fullHdInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 0, top = 0, right = 1920, bottom = 975),
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 1920,
            displayHeightPixels = 1080,
        )
        val compactInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 34, top = 75, right = 734, bottom = 725),
            activityWidthPixels = 768,
            activityHeightPixels = 800,
            displayWidthPixels = 768,
            displayHeightPixels = 800,
        )

        fun safeArea(
            widthPixels: Int,
            heightPixels: Int,
            insets: AirPlayInsets,
        ): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(
                        widthPixels = widthPixels,
                        heightPixels = heightPixels,
                        safeArea = insets,
                    ),
                ),
            )
            val display = (info["displays"] as List<*>).single() as Map<*, *>
            val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
            return view["safeArea"] as Map<*, *>
        }

        val fullHd = safeArea(1920, 1080, fullHdInsets)
        val compact = safeArea(768, 800, compactInsets)

        assertEquals(1920, fullHd["widthPixels"])
        assertEquals(976, fullHd["heightPixels"])
        assertEquals(700, compact["widthPixels"])
        assertEquals(650, compact["heightPixels"])
        assertEquals(34, compact["originXPixels"])
        assertEquals(75, compact["originYPixels"])
    }

    @Test
    fun microphoneInputsAreAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        fun telephony(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "telephony" }

        fun defaultAudio(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }

        assertFalse(telephony(AirPlayInfoPlist.build(base)).containsKey("audioInputFormats"))
        assertTrue(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))
                .containsKey("audioInputFormats"),
        )
        val wired = AirPlayInfoPlist.build(base.copy(microphone = true))
        val wireless = AirPlayInfoPlist.build(base.copy(microphone = true, wirelessAudio = true))
        assertEquals(0x4154, defaultAudio(wired)["audioInputFormats"])
        assertEquals(0x4154, telephony(wired)["audioInputFormats"])
        assertEquals(0x70004154, defaultAudio(wireless)["audioInputFormats"])
        assertEquals(0x70004154, telephony(wireless)["audioInputFormats"])
        assertEquals(0x70004154, audioCapability(wireless, 100, "speechRecognition")["audioInputFormats"])
    }

    @Test
    fun altAudioOutputsRespectTransportCapabilities() {
        val lowRatePcmFormats = 0x3fc
        val opusFormats = 0x70000000

        listOf(48000 to 0xc000, 44100 to 0xc00).forEach { (sampleRate, highRatePcm) ->
            val wired = audioInfo(sampleRate)
            val wireless = audioInfo(sampleRate, wirelessAudio = true)
            listOf("compatibility", "default").forEach { audioType ->
                val wiredFormats = audioCapability(wired, 101, audioType)["audioOutputFormats"] as Number
                assertEquals("$sampleRate Hz wired type=101 audioType=$audioType", highRatePcm, wiredFormats.toInt())
                assertEquals(
                    "$sampleRate Hz wired type=101 audioType=$audioType low-rate PCM",
                    0,
                    wiredFormats.toInt() and lowRatePcmFormats,
                )
                assertEquals(0, wiredFormats.toInt() and opusFormats)
            }

            val wirelessCompatibility =
                audioCapability(wireless, 101, "compatibility")["audioOutputFormats"] as Number
            val wirelessDefault = audioCapability(wireless, 101, "default")["audioOutputFormats"] as Number
            assertEquals(highRatePcm, wirelessCompatibility.toInt())
            assertEquals(highRatePcm or opusFormats, wirelessDefault.toInt())
            assertEquals(0, wirelessDefault.toInt() and lowRatePcmFormats)
        }
    }

    @Test
    fun mainAudioMediaAndVoiceCapabilitiesRemainTransportAppropriate() {
        val lowRatePcmFormats = 0x3fc
        val opusFormats = 0x70000000
        listOf(48000 to 0xc000, 44100 to 0xc00).forEach { (sampleRate, highRatePcm) ->
            val wired = audioInfo(sampleRate, microphone = true)
            val wireless = audioInfo(sampleRate, microphone = true, wirelessAudio = true)
            val pcm = lowRatePcmFormats or highRatePcm
            val monoPcm = 0x154 or (if (sampleRate == 48000) 0x4000 else 0x400)

            listOf("compatibility", "media").forEach { audioType ->
                assertEquals(pcm, audioCapability(wired, 100, audioType)["audioOutputFormats"])
                assertEquals(pcm, audioCapability(wireless, 100, audioType)["audioOutputFormats"])
            }
            assertEquals(pcm, audioCapability(wired, 100, "default")["audioOutputFormats"])
            assertEquals(pcm, audioCapability(wired, 100, "alert")["audioOutputFormats"])
            assertEquals(pcm or opusFormats, audioCapability(wireless, 100, "default")["audioOutputFormats"])
            assertEquals(pcm or opusFormats, audioCapability(wireless, 100, "alert")["audioOutputFormats"])
            val aacFormats = if (sampleRate == 48000) 0x800000 else 0x400000
            assertEquals(aacFormats, audioCapability(wired, 102, "media")["audioOutputFormats"])
            assertEquals(aacFormats, audioCapability(wireless, 102, "media")["audioOutputFormats"])
            listOf("telephony", "speechRecognition").forEach { audioType ->
                val wiredCapability = audioCapability(wired, 100, audioType)
                val wirelessCapability = audioCapability(wireless, 100, audioType)
                assertEquals(monoPcm, wiredCapability["audioOutputFormats"])
                assertEquals(monoPcm or opusFormats, wirelessCapability["audioOutputFormats"])
                assertEquals(monoPcm, wiredCapability["audioInputFormats"])
                assertEquals(monoPcm or opusFormats, wirelessCapability["audioInputFormats"])
            }
            assertEquals(monoPcm, audioCapability(wired, 100, "compatibility")["audioInputFormats"])
            assertEquals(monoPcm, audioCapability(wired, 100, "default")["audioInputFormats"])
            assertEquals(monoPcm or opusFormats, audioCapability(wireless, 100, "default")["audioInputFormats"])
        }
    }

    @Test
    fun mainAltAndHighAudioStreamsAreDeclared() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val types = (info["audioFormats"] as List<*>)
            .map { (it as Map<*, *>)["type"] }
            .toSet()
        assertEquals(setOf(100, 101, 102), types)
    }

    private fun audioInfo(
        sampleRate: Int,
        microphone: Boolean = false,
        wirelessAudio: Boolean = false,
    ): Map<String, Any?> =
        AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                entertainmentSampleRate = sampleRate,
                microphone = microphone,
                wirelessAudio = wirelessAudio,
            ),
        )

    private fun audioCapability(info: Map<String, Any?>, type: Int, audioType: String): Map<*, *> =
        (info["audioFormats"] as List<*>)
            .map { it as Map<*, *> }
            .single { it["type"] == type && it["audioType"] == audioType }
}
