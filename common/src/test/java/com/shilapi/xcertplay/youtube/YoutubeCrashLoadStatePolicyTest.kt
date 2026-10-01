package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeCrashLoadStatePolicyTest {
    @Test
    fun popupCrashPreservesHealthyPrimaryStatus() {
        listOf(YoutubeLoadState.LOADING, YoutubeLoadState.READY).forEach { primaryState ->
            assertEquals(primaryState, YoutubeCrashLoadStatePolicy.afterPopupCrash(primaryState))
        }
    }

    @Test
    fun primaryCrashProducesErrorState() {
        assertEquals(YoutubeLoadState.ERROR, YoutubeCrashLoadStatePolicy.afterPrimaryCrash())
    }

    @Test
    fun loadingAndErrorStatusesExitFullscreenBeforeOverlayIsShown() {
        assertTrue(YoutubeFullscreenStatusPolicy.shouldExitFullscreen(YoutubeLoadState.LOADING))
        assertTrue(YoutubeFullscreenStatusPolicy.shouldExitFullscreen(YoutubeLoadState.ERROR))
        assertFalse(YoutubeFullscreenStatusPolicy.shouldExitFullscreen(YoutubeLoadState.READY))
    }
}
