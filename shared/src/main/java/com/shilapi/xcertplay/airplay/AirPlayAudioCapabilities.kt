package com.shilapi.xcertplay.airplay

internal data class AirPlayAudioOutputCapability(
    val type: Int,
    val audioType: String,
    val outputFormats: Long,
)

internal object AirPlayAudioCapabilities {
    const val OPUS_16_KHZ_MONO = 0x10000000L
    const val OPUS_24_KHZ_MONO = 0x20000000L
    const val OPUS_48_KHZ_MONO = 0x40000000L
    const val OPUS_FORMATS = OPUS_16_KHZ_MONO or OPUS_24_KHZ_MONO or OPUS_48_KHZ_MONO
    const val AAC_LC_44_1_KHZ_STEREO = 0x400000L
    const val AAC_LC_48_KHZ_STEREO = 0x800000L

    private const val PCM_8_KHZ_MONO = 0x4L
    private const val PCM_8_KHZ_STEREO = 0x8L
    private const val PCM_16_KHZ_MONO = 0x10L
    private const val PCM_16_KHZ_STEREO = 0x20L
    private const val PCM_24_KHZ_MONO = 0x40L
    private const val PCM_24_KHZ_STEREO = 0x80L
    private const val PCM_32_KHZ_MONO = 0x100L
    private const val PCM_32_KHZ_STEREO = 0x200L
    private const val PCM_44_1_KHZ_MONO = 0x400L
    private const val PCM_44_1_KHZ_STEREO = 0x800L
    private const val PCM_48_KHZ_MONO = 0x4000L
    private const val PCM_48_KHZ_STEREO = 0x8000L

    private val outputKeys = listOf(
        Triple(100, "compatibility", "compatibility"),
        Triple(101, "compatibility", "compatibility"),
        Triple(100, "default", "default"),
        Triple(100, "alert", "alert"),
        Triple(100, "media", "media"),
        Triple(100, "telephony", "telephony"),
        Triple(100, "speechRecognition", "speechrecognition"),
        Triple(101, "default", "default"),
        Triple(102, "media", "media"),
    )

    fun outputFormatMask(
        entertainmentSampleRate: Int,
        wirelessAudio: Boolean,
        type: Int,
        audioType: String,
    ): Long? = capability(
        entertainmentSampleRate,
        wirelessAudio,
        type,
        audioType,
    )?.outputFormats

    fun advertisedOutputFormats(
        entertainmentSampleRate: Int,
        wirelessAudio: Boolean,
    ): List<AirPlayAudioOutputCapability> = outputKeys.mapNotNull { (type, audioType, normalizedAudioType) ->
        capability(entertainmentSampleRate, wirelessAudio, type, normalizedAudioType)
            ?.copy(audioType = audioType)
    }

    fun inputFormatMask(
        microphone: Boolean,
        entertainmentSampleRate: Int,
        wirelessAudio: Boolean,
        type: Int,
        audioType: String,
    ): Long? {
        if (!microphone || type != 100) return null
        return when (normalizeAudioType(audioType)) {
            "compatibility" -> pcmMonoFormatMask(entertainmentSampleRate)
            "default", "telephony", "speechrecognition" ->
                (pcmMonoFormatMask(entertainmentSampleRate) ?: return null) or
                    (if (wirelessAudio) OPUS_FORMATS else 0L)
            else -> null
        }
    }

    private fun capability(
        entertainmentSampleRate: Int,
        wirelessAudio: Boolean,
        type: Int,
        audioType: String,
    ): AirPlayAudioOutputCapability? {
        val highRatePcm = highRatePcmFormatMask(entertainmentSampleRate) ?: return null
        val highRateStereoPcm = highRateStereoPcmFormatMask(entertainmentSampleRate) ?: return null
        val pcm = pcmFormatMask(entertainmentSampleRate) ?: return null
        val pcmMono = pcmMonoFormatMask(entertainmentSampleRate) ?: return null
        val opus = if (wirelessAudio) OPUS_FORMATS else 0L
        val outputFormats = when (type to normalizeAudioType(audioType)) {
            100 to "compatibility" -> pcm
            101 to "compatibility" -> highRatePcm
            100 to "default", 100 to "alert" -> pcm or opus
            100 to "media" -> if (wirelessAudio) pcm else highRateStereoPcm
            100 to "telephony", 100 to "speechrecognition" -> pcmMono or opus
            101 to "default" -> highRatePcm or opus
            102 to "media" -> if (!wirelessAudio) return null else if (entertainmentSampleRate == 48_000) {
                AAC_LC_48_KHZ_STEREO
            } else {
                AAC_LC_44_1_KHZ_STEREO
            }
            else -> return null
        }
        return AirPlayAudioOutputCapability(type, normalizeAudioType(audioType), outputFormats)
    }

    private fun pcmFormatMask(entertainmentSampleRate: Int): Long? {
        val highRate = highRatePcmFormatMask(entertainmentSampleRate) ?: return null
        return lowRatePcmFormatMask() or highRate
    }

    private fun highRatePcmFormatMask(entertainmentSampleRate: Int): Long? = when (entertainmentSampleRate) {
        44_100 -> PCM_44_1_KHZ_MONO or PCM_44_1_KHZ_STEREO
        48_000 -> PCM_48_KHZ_MONO or PCM_48_KHZ_STEREO
        else -> null
    }

    private fun highRateStereoPcmFormatMask(entertainmentSampleRate: Int): Long? = when (entertainmentSampleRate) {
        44_100 -> PCM_44_1_KHZ_STEREO
        48_000 -> PCM_48_KHZ_STEREO
        else -> null
    }

    private fun pcmMonoFormatMask(entertainmentSampleRate: Int = 44_100): Long? {
        val highRateMono = when (entertainmentSampleRate) {
            44_100 -> PCM_44_1_KHZ_MONO
            48_000 -> PCM_48_KHZ_MONO
            else -> return null
        }
        return PCM_8_KHZ_MONO or PCM_16_KHZ_MONO or PCM_24_KHZ_MONO or PCM_32_KHZ_MONO or highRateMono
    }

    private fun lowRatePcmFormatMask(): Long =
        PCM_8_KHZ_MONO or PCM_8_KHZ_STEREO or
            PCM_16_KHZ_MONO or PCM_16_KHZ_STEREO or
            PCM_24_KHZ_MONO or PCM_24_KHZ_STEREO or
            PCM_32_KHZ_MONO or PCM_32_KHZ_STEREO
}
