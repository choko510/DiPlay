package com.shilapi.xcertplay.youtube

internal object YoutubeCrashLoadStatePolicy {
    fun afterPopupCrash(primaryLoadState: YoutubeLoadState): YoutubeLoadState = primaryLoadState

    fun afterPrimaryCrash(): YoutubeLoadState = YoutubeLoadState.ERROR
}

internal object YoutubeFullscreenStatusPolicy {
    fun shouldExitFullscreen(state: YoutubeLoadState): Boolean = state != YoutubeLoadState.READY
}
