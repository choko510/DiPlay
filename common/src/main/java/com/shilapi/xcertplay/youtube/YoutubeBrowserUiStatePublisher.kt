package com.shilapi.xcertplay.youtube

internal class YoutubeBrowserUiStatePublisher(
    private val onLoadStateChanged: (YoutubeLoadState) -> Unit,
    private val onCanGoBackChanged: (Boolean) -> Unit,
) {
    private var lifecycle = YoutubeBrowserLifecycle.ACTIVE
    private var active = true

    var loadState = YoutubeLoadState.LOADING
        private set

    var canGoBack = false
        private set

    private var lastPublishedLoadState: YoutubeLoadState? = null
    private var lastPublishedCanGoBack: Boolean? = null

    fun setLifecycle(value: YoutubeBrowserLifecycle) {
        lifecycle = value
    }

    fun invalidatePublishedState() {
        lastPublishedLoadState = null
        lastPublishedCanGoBack = null
    }

    fun clearBackStateForDestroy() {
        canGoBack = false
        lastPublishedCanGoBack = false
        onCanGoBackChanged(false)
    }

    fun shouldPublish(isCurrentSession: Boolean): Boolean =
        lifecycle == YoutubeBrowserLifecycle.ACTIVE && active && isCurrentSession

    fun updateLoadState(state: YoutubeLoadState, isCurrentSession: Boolean = true) {
        if (!isCurrentSession) return
        loadState = state
        if (shouldPublish(true) && lastPublishedLoadState != state) {
            onLoadStateChanged(state)
            lastPublishedLoadState = state
        }
    }

    fun updateCanGoBack(value: Boolean, isCurrentSession: Boolean = true) {
        if (!isCurrentSession) return
        canGoBack = value
        if (shouldPublish(true) && lastPublishedCanGoBack != value) {
            onCanGoBackChanged(value)
            lastPublishedCanGoBack = value
        }
    }

    fun setActive(value: Boolean) {
        val becameActive = value && !active
        active = value
        if (becameActive && lifecycle == YoutubeBrowserLifecycle.ACTIVE) {
            onCanGoBackChanged(canGoBack)
            onLoadStateChanged(loadState)
            lastPublishedCanGoBack = canGoBack
            lastPublishedLoadState = loadState
        }
    }
}
