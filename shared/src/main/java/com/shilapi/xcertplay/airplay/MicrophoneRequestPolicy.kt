package com.shilapi.xcertplay.airplay

internal object MicrophoneRequestPolicy {
    const val MAIN_AUDIO_STREAM_TYPE = 100

    private val legacyInputAudioTypes = setOf("telephony", "speechrecognition")
    private const val COMPATIBILITY_AUDIO_TYPE = "compatibility"
    private const val DEFAULT_AUDIO_TYPE = "default"

    fun wantsMicrophone(streamType: Int, params: Map<String, Any?>): Boolean {
        if (streamType != MAIN_AUDIO_STREAM_TYPE) return false
        if (params[IS_INPUT_KEY] == false) return false
        if (!hasValidDataPort(params[DATA_PORT_KEY])) return false
        val audioType = normalizeAudioType(params[AUDIO_TYPE_KEY]?.toString() ?: DEFAULT_AUDIO_TYPE)
        return audioType in legacyInputAudioTypes ||
            audioType == COMPATIBILITY_AUDIO_TYPE ||
            (audioType == DEFAULT_AUDIO_TYPE && params[IS_INPUT_KEY] == true)
    }

    private fun hasValidDataPort(value: Any?): Boolean {
        val port = value as? Number ?: return false
        val asLong = port.toLong()
        return asLong in MIN_PORT..MAX_PORT && port.toDouble() == asLong.toDouble()
    }

    private const val IS_INPUT_KEY = "isInput"
    private const val DATA_PORT_KEY = "dataPort"
    private const val AUDIO_TYPE_KEY = "audioType"
    private const val MIN_PORT = 1L
    private const val MAX_PORT = 65_535L
}
