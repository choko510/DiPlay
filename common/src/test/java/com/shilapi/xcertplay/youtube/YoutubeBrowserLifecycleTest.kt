package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeBrowserLifecycleTest {
    @Test
    fun sameProfileReopenReusesAnOpenSuspendedSession() {
        assertTrue(
            YoutubeBrowserSessionReusePolicy.canReuse(
                lifecycle = YoutubeBrowserLifecycle.SUSPENDED,
                currentContextId = "diplay_youtube_example",
                requestedContextId = "diplay_youtube_example",
                primarySessionOpen = true,
                crashed = false,
            ),
        )
    }

    @Test
    fun profileChangeCrashAndDestroyedLifecycleRequireANewSession() {
        assertFalse(canReuse(currentContextId = "diplay_youtube_old", requestedContextId = "diplay_youtube_new"))
        assertFalse(canReuse(crashed = true))
        assertFalse(canReuse(lifecycle = YoutubeBrowserLifecycle.DESTROYED))
        assertFalse(canReuse(primarySessionOpen = false))
    }

    private fun canReuse(
        lifecycle: YoutubeBrowserLifecycle = YoutubeBrowserLifecycle.SUSPENDED,
        currentContextId: String? = "diplay_youtube_example",
        requestedContextId: String = "diplay_youtube_example",
        primarySessionOpen: Boolean = true,
        crashed: Boolean = false,
    ): Boolean = YoutubeBrowserSessionReusePolicy.canReuse(
        lifecycle,
        currentContextId,
        requestedContextId,
        primarySessionOpen,
        crashed,
    )
}
