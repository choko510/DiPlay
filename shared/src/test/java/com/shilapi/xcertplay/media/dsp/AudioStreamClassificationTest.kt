package com.shilapi.xcertplay.media.dsp

import com.shilapi.xcertplay.media.AudioChannel
import com.shilapi.xcertplay.media.AudioChannelMapper
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.NavigationAudioRoute
import com.shilapi.xcertplay.media.AudioStreamClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioStreamClassificationTest {
    @Test
    fun knownAudioTypesUseSemanticRolesRegardlessOfRoutingMode() {
        val expectedRoles = mapOf(
            "MeDiA" to DspStreamRole.MEDIA,
            "Telephony" to DspStreamRole.PHONE,
            "speechRecognition" to DspStreamRole.ASSISTANT,
            "Alert" to DspStreamRole.ALERT,
            "Default" to DspStreamRole.NAVIGATION,
            "Compatibility" to DspStreamRole.NAVIGATION,
        )

        for (mappingMode in AudioChannelMappingMode.entries) {
            for ((audioType, expectedRole) in expectedRoles) {
                val classification = AudioStreamClassifier.classify(
                    audioType = audioType,
                    payloadType = 100,
                    mappingMode = mappingMode,
                    navigationRoute = NavigationAudioRoute.SYSTEM_NAVIGATION,
                )
                assertEquals(expectedRole, classification.dspRole)
            }
        }
    }

    @Test
    fun navigationRoleStaysNavigationWhenAndroidRoutesItAsMedia() {
        val classification = AudioStreamClassifier.classify(
            audioType = "default",
            payloadType = 100,
            mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationRoute = NavigationAudioRoute.FULL_BAND,
        )

        assertEquals(AudioChannel.MEDIA, classification.route.channel)
        assertEquals(DspStreamRole.NAVIGATION, classification.dspRole)
    }

    @Test
    fun unknownTypesUseTheMainHighAudioPayloadFallbackOnly() {
        assertEquals(
            DspStreamRole.MEDIA,
            classify("future-type", AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO).dspRole,
        )
        assertEquals(
            DspStreamRole.OTHER_LOW_LATENCY,
            classify("future-type", 100).dspRole,
        )
    }

    @Test
    fun routeIsDelegatedToTheExistingMapper() {
        val audioTypes = listOf("media", "telephony", "speechRecognition", "default", "alert", "unknown")
        for (mode in AudioChannelMappingMode.entries) {
            for (audioType in audioTypes) {
                val classification = AudioStreamClassifier.classify(
                    audioType = audioType,
                    payloadType = 102,
                    mappingMode = mode,
                    navigationRoute = NavigationAudioRoute.LEGACY_STREAM_MUSIC,
                )
                assertEquals(
                    AudioChannelMapper.map(
                        audioType,
                        102,
                        mode,
                        NavigationAudioRoute.LEGACY_STREAM_MUSIC,
                    ),
                    classification.route,
                )
            }
        }
    }

    private fun classify(audioType: String, payloadType: Int) = AudioStreamClassifier.classify(
        audioType = audioType,
        payloadType = payloadType,
        mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
        navigationRoute = NavigationAudioRoute.FULL_BAND,
    )
}
