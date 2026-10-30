package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneRequestPolicyTest {
    @Test
    fun acceptsAdvertisedVoiceInputTypesOnMainAudioWithAValidPort() {
        val voiceTypes = listOf("default", "compatibility", "telephony", "speechRecognition")

        voiceTypes.forEach { audioType ->
            assertTrue(
                audioType,
                MicrophoneRequestPolicy.wantsMicrophone(
                    MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
                    mapOf("audioType" to audioType, "dataPort" to 49_152),
                ),
            )
        }
        assertTrue(
            MicrophoneRequestPolicy.wantsMicrophone(
                MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
                mapOf("dataPort" to 49_152),
            ),
        )
    }

    @Test
    fun explicitInputFalseVetoesEveryVoiceInputType() {
        listOf("default", "compatibility", "telephony", "speechRecognition").forEach { audioType ->
            assertFalse(
                audioType,
                MicrophoneRequestPolicy.wantsMicrophone(
                    MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
                    mapOf("input" to false, "audioType" to audioType, "dataPort" to 49_152),
                ),
            )
        }
    }

    @Test
    fun rejectsNonVoiceTypesNonMainStreamsAndMissingOrInvalidPorts() {
        assertFalse(
            MicrophoneRequestPolicy.wantsMicrophone(
                MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
                mapOf("audioType" to "media", "dataPort" to 49_152),
            ),
        )
        assertFalse(
            MicrophoneRequestPolicy.wantsMicrophone(
                101,
                mapOf("audioType" to "default", "dataPort" to 49_152),
            ),
        )
        listOf(null, 0, -1, 65_536, 4_294_967_297L, BigInteger("4294967297"), "49152").forEach { port ->
            assertFalse(
                port.toString(),
                MicrophoneRequestPolicy.wantsMicrophone(
                    MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
                    mapOf("audioType" to "default", "dataPort" to port),
                ),
            )
        }
    }
}
