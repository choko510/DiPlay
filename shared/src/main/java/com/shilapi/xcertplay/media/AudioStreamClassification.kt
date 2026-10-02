package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.dsp.DspStreamRole

internal data class AudioStreamClassification(
    val route: AudioChannelSelection,
    val dspRole: DspStreamRole,
)

internal object AudioStreamClassifier {
    fun classify(
        audioType: String,
        payloadType: Int,
        mappingMode: AudioChannelMappingMode,
        navigationRoute: NavigationAudioRoute,
    ): AudioStreamClassification {
        val normalizedAudioType = audioType.lowercase()
        val dspRole = when (normalizedAudioType) {
            "media" -> DspStreamRole.MEDIA
            "telephony" -> DspStreamRole.PHONE
            "speechrecognition" -> DspStreamRole.ASSISTANT
            "alert" -> DspStreamRole.ALERT
            "default", "compatibility" -> DspStreamRole.NAVIGATION
            else -> if (payloadType == AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO) {
                DspStreamRole.MEDIA
            } else {
                DspStreamRole.OTHER_LOW_LATENCY
            }
        }
        return AudioStreamClassification(
            route = AudioChannelMapper.map(
                audioType = audioType,
                payloadType = payloadType,
                mode = mappingMode,
                navigationRoute = navigationRoute,
            ),
            dspRole = dspRole,
        )
    }
}
