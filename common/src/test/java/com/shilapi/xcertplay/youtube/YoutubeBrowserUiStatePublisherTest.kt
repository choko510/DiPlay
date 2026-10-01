package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeBrowserUiStatePublisherTest {
    @Test
    fun suspendedSessionReadyDoesNotPublishUiState() {
        val (publisher, loadStates, backStates) = createPublisher()
        suspend(publisher)

        publisher.updateLoadState(YoutubeLoadState.READY)

        assertEquals(emptyList<YoutubeLoadState>(), loadStates)
        assertEquals(emptyList<Boolean>(), backStates)
    }

    @Test
    fun suspendedSessionErrorDoesNotPublishUiState() {
        val (publisher, loadStates, backStates) = createPublisher()
        suspend(publisher)

        publisher.updateLoadState(YoutubeLoadState.ERROR)

        assertEquals(emptyList<YoutubeLoadState>(), loadStates)
        assertEquals(emptyList<Boolean>(), backStates)
    }

    @Test
    fun suspendedPrimaryCrashDoesNotPublishStaleError() {
        val (publisher, loadStates, backStates) = createPublisher()
        suspend(publisher)

        publisher.updateCanGoBack(false)
        publisher.updateLoadState(YoutubeLoadState.ERROR)

        assertEquals(YoutubeLoadState.ERROR, publisher.loadState)
        assertEquals(emptyList<YoutubeLoadState>(), loadStates)
        assertEquals(emptyList<Boolean>(), backStates)
    }

    @Test
    fun warmActivationPublishesLatestCachedStateOnce() {
        val (publisher, loadStates, backStates) = createPublisher()
        suspend(publisher)
        publisher.updateLoadState(YoutubeLoadState.LOADING)
        publisher.updateCanGoBack(true)
        publisher.updateLoadState(YoutubeLoadState.READY)
        publisher.setLifecycle(YoutubeBrowserLifecycle.ACTIVE)

        publisher.setActive(true)
        publisher.setActive(true)

        assertEquals(listOf(YoutubeLoadState.READY), loadStates)
        assertEquals(listOf(true), backStates)
    }

    @Test
    fun destroyClearsBackStateEvenWhenPublisherIsInactive() {
        val (publisher, _, backStates) = createPublisher()
        publisher.updateCanGoBack(true)
        suspend(publisher)

        publisher.clearBackStateForDestroy()

        assertEquals(false, publisher.canGoBack)
        assertEquals(listOf(true, false), backStates)
    }

    private fun suspend(publisher: YoutubeBrowserUiStatePublisher) {
        publisher.setLifecycle(YoutubeBrowserLifecycle.SUSPENDED)
        publisher.setActive(false)
    }

    private fun createPublisher(): Triple<YoutubeBrowserUiStatePublisher, MutableList<YoutubeLoadState>, MutableList<Boolean>> {
        val loadStates = mutableListOf<YoutubeLoadState>()
        val backStates = mutableListOf<Boolean>()
        return Triple(
            YoutubeBrowserUiStatePublisher(
                onLoadStateChanged = { loadStates.add(it) },
                onCanGoBackChanged = { backStates.add(it) },
            ),
            loadStates,
            backStates,
        )
    }
}
