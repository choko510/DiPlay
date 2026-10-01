package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeBrowserLifecycleTest {
    @Test
    fun activeSameProfileDoesNotCountAsWarmReopen() {
        assertEquals(
            YoutubeBrowserOpenDecision.PRESERVE_ACTIVE_SESSION,
            decide(lifecycle = YoutubeBrowserLifecycle.ACTIVE),
        )
    }

    @Test
    fun activeOpenDecisionPreservesTheSelectedSession() {
        assertEquals(
            YoutubeBrowserOpenDecision.PRESERVE_ACTIVE_SESSION,
            decide(lifecycle = YoutubeBrowserLifecycle.ACTIVE),
        )
    }

    @Test
    fun suspendedSameProfileReactivatesThePrimarySession() {
        assertEquals(
            YoutubeBrowserOpenDecision.REACTIVATE_SUSPENDED_SESSION,
            decide(lifecycle = YoutubeBrowserLifecycle.SUSPENDED),
        )
    }

    @Test
    fun suspendedDifferentProfileCrashOrClosedSessionCreatesANewSession() {
        assertEquals(
            YoutubeBrowserOpenDecision.CREATE_SESSION,
            decide(currentContextId = "diplay_youtube_old", requestedContextId = "diplay_youtube_new"),
        )
        assertEquals(YoutubeBrowserOpenDecision.CREATE_SESSION, decide(crashed = true))
        assertEquals(YoutubeBrowserOpenDecision.CREATE_SESSION, decide(primarySessionOpen = false))
    }

    @Test
    fun destroyedControllerCannotBeReopened() {
        assertEquals(
            YoutubeBrowserOpenDecision.IGNORE_DESTROYED_CONTROLLER,
            decide(lifecycle = YoutubeBrowserLifecycle.DESTROYED),
        )
    }

    private fun decide(
        lifecycle: YoutubeBrowserLifecycle = YoutubeBrowserLifecycle.SUSPENDED,
        currentContextId: String? = "diplay_youtube_example",
        requestedContextId: String = "diplay_youtube_example",
        primarySessionOpen: Boolean = true,
        crashed: Boolean = false,
    ): YoutubeBrowserOpenDecision = YoutubeBrowserSessionReusePolicy.decide(
        lifecycle = lifecycle,
        currentContextId = currentContextId,
        requestedContextId = requestedContextId,
        primarySessionOpen = primarySessionOpen,
        crashed = crashed,
    )
}
