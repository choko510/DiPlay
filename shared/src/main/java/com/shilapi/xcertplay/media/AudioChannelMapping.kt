package com.shilapi.xcertplay.media

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

enum class NavigationAudioRoute {
    FULL_BAND,
    SYSTEM_NAVIGATION,
    LEGACY_STREAM_MUSIC,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    val useLegacyMusicStream: Boolean = false,
)

/**
 * Maps CarPlay stream metadata to Android AudioAttributes values.
 *
 * AAOS routes AudioTrack instances by usage, so the automotive mode keeps generic media streams
 * on the media bus instead of reusing mobile's navigation fallback for default/compatibility.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationRoute: NavigationAudioRoute = NavigationAudioRoute.FULL_BAND,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE ->
                mapMobileCompatible(normalized, payloadType, navigationRoute)
            AudioChannelMappingMode.AUTOMOTIVE_BUS -> mapAutomotiveBus(normalized, payloadType)
        }
    }

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
        navigationRoute: NavigationAudioRoute,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert", "compatibility" -> mapNavigationRoute(navigationRoute)
        else -> mainHighAudioOrNavigation(payloadType)
    }

    private fun mapNavigationRoute(route: NavigationAudioRoute): AudioChannelSelection = when (route) {
        NavigationAudioRoute.FULL_BAND ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        NavigationAudioRoute.SYSTEM_NAVIGATION ->
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        NavigationAudioRoute.LEGACY_STREAM_MUSIC ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC, useLegacyMusicStream = true)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media", "default", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        else -> mainHighAudioOrNavigation(payloadType)
    }

    private fun mainHighAudioOrNavigation(payloadType: Int): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        }
}
