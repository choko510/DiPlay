package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChannelMappingTest {
    @Test
    fun mobileCompatibleMappingMatchesTheOriginalRouting() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        listOf("default", "alert", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                navigationRoute = NavigationAudioRoute.SYSTEM_NAVIGATION,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
            )
        }
    }

    @Test
    fun fullBandIsTheMobileDefaultAndLeavesPhoneAndAssistantStreamsAlone() {
        listOf("default", "alert", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun legacyMusicStreamIsLimitedToNavigationFamilies() {
        listOf("default", "alert", "compatibility").forEach { audioType ->
            val selection = AudioChannelMapper.map(
                audioType,
                100,
                AudioChannelMappingMode.MOBILE_COMPATIBLE,
                NavigationAudioRoute.LEGACY_STREAM_MUSIC,
            )
            assertEquals(AudioChannel.MEDIA, selection.channel)
            assertEquals(AudioContentType.MUSIC, selection.contentType)
            assertEquals(true, selection.useLegacyMusicStream)
        }
        assertEquals(
            false,
            AudioChannelMapper.map(
                "telephony",
                100,
                AudioChannelMappingMode.MOBILE_COMPATIBLE,
                NavigationAudioRoute.LEGACY_STREAM_MUSIC,
            ).useLegacyMusicStream,
        )
        val media = AudioChannelMapper.map(
            "media",
            102,
            AudioChannelMappingMode.MOBILE_COMPATIBLE,
            NavigationAudioRoute.LEGACY_STREAM_MUSIC,
        )
        assertEquals(AudioChannel.MEDIA, media.channel)
        assertEquals(AudioContentType.MUSIC, media.contentType)
        assertEquals(false, media.useLegacyMusicStream)
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        listOf("media", "default", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "alert",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
        val aaosDefault = AudioChannelMapper.map(
            "default",
            100,
            AudioChannelMappingMode.AUTOMOTIVE_BUS,
            NavigationAudioRoute.LEGACY_STREAM_MUSIC,
        )
        assertEquals(AudioChannel.MEDIA, aaosDefault.channel)
        assertEquals(AudioContentType.MUSIC, aaosDefault.contentType)
        assertEquals(false, aaosDefault.useLegacyMusicStream)
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        navigationRoute: NavigationAudioRoute = NavigationAudioRoute.FULL_BAND,
        channel: AudioChannel,
        contentType: AudioContentType,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType),
            AudioChannelMapper.map(audioType, payloadType, mode, navigationRoute),
        )
    }
}
