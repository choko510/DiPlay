package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneRequestPolicyTest {
    @Test
    fun explicitInputFalseOverridesLegacyAudioTypesAndPorts() {
        listOf("telephony", "speechrecognition", "default", "compatibility").forEach { audioType ->
            assertFalse(wants(audioType, isInput = false, dataPort = 12_345))
        }
    }

    @Test
    fun legacyTelephonyAndSpeechInputsRemainEnabledWithoutAnInputFlag() {
        assertTrue(wants("telephony", dataPort = 12_345))
        assertTrue(wants("speechRecognition", dataPort = 12_345))
    }

    @Test
    fun defaultRequiresAnExplicitRequestWhileCompatibilityCanUseItsLegacyInputMode() {
        assertTrue(wants("default", isInput = true, dataPort = 12_345))
        assertTrue(wants("compatibility", isInput = true, dataPort = 12_345))
        assertTrue(wants("compatibility", dataPort = 12_345))
        assertFalse(wants("default", dataPort = 12_345))
    }

    @Test
    fun musicAlertsAndNonMainAudioStreamsNeverRequestMicrophoneCapture() {
        assertFalse(wants("media", isInput = true, dataPort = 12_345))
        assertFalse(wants("alert", isInput = true, dataPort = 12_345))
        assertFalse(wants("telephony", dataPort = 12_345, streamType = 101))
        assertFalse(wants("default", isInput = true, dataPort = 12_345, streamType = 101))
    }

    @Test
    fun missingMalformedAndOutOfRangePortsAreRejected() {
        assertFalse(wants("telephony", dataPort = null))
        assertFalse(wants("default", isInput = true, dataPort = "12345"))
        assertFalse(wants("default", isInput = true, dataPort = 0))
        assertFalse(wants("default", isInput = true, dataPort = 65_536))
        assertFalse(wants("default", isInput = true, dataPort = 12_345.5))
    }

    private fun wants(
        audioType: String,
        isInput: Any? = null,
        dataPort: Any?,
        streamType: Int = MicrophoneRequestPolicy.MAIN_AUDIO_STREAM_TYPE,
    ): Boolean {
        val params = buildMap<String, Any?> {
            put("audioType", audioType)
            if (isInput != null) put("isInput", isInput)
            if (dataPort != null) put("dataPort", dataPort)
        }
        return MicrophoneRequestPolicy.wantsMicrophone(streamType, params)
    }
}
