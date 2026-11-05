package com.shilapi.xcertplay.airplay

import java.math.BigInteger

internal object MicrophoneRequestPolicy {
    const val MAIN_AUDIO_STREAM_TYPE = 100

    fun wantsMicrophone(type: Int, params: Map<String, Any?>): Boolean =
        type == MAIN_AUDIO_STREAM_TYPE && params["input"] != false &&
            isVoiceInputType(params["audioType"]?.toString() ?: "default") &&
            destinationPort(params) != null

    fun destinationPort(params: Map<String, Any?>): Int? {
        val port = when (val value = params["dataPort"]) {
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Int -> value
            is Long -> if (value in 1L..MAX_PORT.toLong()) value.toInt() else return null
            is BigInteger -> if (value in BIG_INTEGER_PORT_RANGE) value.toInt() else return null
            else -> return null
        }
        return port.takeIf { it in 1..MAX_PORT }
    }

    private fun isVoiceInputType(audioType: String): Boolean = when (normalizeAudioType(audioType)) {
        "default", "compatibility", "telephony", "speechrecognition" -> true
        else -> false
    }

    private const val MAX_PORT = 65_535
    private val BIG_INTEGER_PORT_RANGE = BigInteger.ONE..BigInteger.valueOf(MAX_PORT.toLong())
}
