package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeBrowserMemoryPolicyTest {
    @Test
    fun runningCriticalPressureEvictsSuspendedBrowserOnlyOutsideSplitBeforeApi34() {
        assertTrue(
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = 33,
                trimLevel = 15,
                splitMode = false,
                browserSuspended = true,
            ),
        )
    }

    @Test
    fun uiHiddenLevelDoesNotEvictSuspendedBrowser() {
        assertFalse(
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = 33,
                trimLevel = 20,
                splitMode = false,
                browserSuspended = true,
            ),
        )
    }

    @Test
    fun runningTrimLevelsAreNotExpectedOnApi34OrLater() {
        assertFalse(
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = 34,
                trimLevel = 15,
                splitMode = false,
                browserSuspended = true,
            ),
        )
    }

    @Test
    fun splitModeAndActiveBrowserKeepTheirSessions() {
        assertFalse(
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = 33,
                trimLevel = 15,
                splitMode = true,
                browserSuspended = true,
            ),
        )
        assertFalse(
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = 33,
                trimLevel = 15,
                splitMode = false,
                browserSuspended = false,
            ),
        )
    }
}
