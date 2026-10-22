package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCarPlayScalePolicyTest {
    @Test
    fun splitBoundsPreserveTheRemoteAspectRatioAndLeaveLetterboxBarsUntouched() {
        val bounds = requireNotNull(
            LocalCarPlayScalePolicy.fitBounds(
                remote = DisplaySize(1920, 1080),
                viewport = DisplaySize(1056, 1080),
            ),
        )

        assertEquals(LocalCarPlayContentBounds(left = 0, top = 243, width = 1056, height = 594), bounds)
        assertFalse(bounds.contains(528f, 242f))
        assertTrue(bounds.contains(528f, 243f))
        assertFalse(bounds.contains(528f, 837f))
    }

    @Test
    fun fittedContentBoundsExcludeTheRightAndBottomEdges() {
        val bounds = LocalCarPlayContentBounds(0, 0, 100, 60)

        assertTrue(bounds.contains(0f, 0f))
        assertTrue(bounds.contains(99.99f, 59.99f))
        assertFalse(bounds.contains(100f, 20f))
        assertFalse(bounds.contains(20f, 60f))
    }

    @Test
    fun portraitAndLandscapeBoundsStayInsideTheirViewport() {
        val portraitViewport = DisplaySize(1080, 1920)
        val portraitBounds = requireNotNull(
            LocalCarPlayScalePolicy.fitBounds(DisplaySize(1920, 1080), portraitViewport),
        )
        assertEquals(1080, portraitBounds.width)
        assertEquals(608, portraitBounds.height)
        assertEquals(656, portraitBounds.top)
        assertTrue(portraitBounds.left >= 0)
        assertTrue(portraitBounds.top >= 0)
        assertTrue(portraitBounds.left + portraitBounds.width <= portraitViewport.width)
        assertTrue(portraitBounds.top + portraitBounds.height <= portraitViewport.height)
    }

    @Test
    fun repeatedFullAndSplitCyclesKeepTheNegotiatedRemoteSizeAndFitTheCurrentViewport() {
        val remote = DisplaySize(1920, 1080)
        val full = DisplaySize(1920, 1080)
        val split = DisplaySize(1056, 1080)

        repeat(20) {
            assertTrue(canKeepSession())
            assertEquals(LocalCarPlayContentBounds(0, 0, 1920, 1080), LocalCarPlayScalePolicy.fitBounds(remote, full))
            assertEquals(LocalCarPlayContentBounds(0, 243, 1056, 594), LocalCarPlayScalePolicy.fitBounds(remote, split))
        }
        assertEquals(DisplaySize(1920, 1080), remote)
    }

    @Test
    fun invalidSizesAndUnsafeLifecycleStatesUseTheRestartFallback() {
        assertNull(LocalCarPlayScalePolicy.fitBounds(DisplaySize(0, 1080), DisplaySize(1056, 1080)))
        assertNull(LocalCarPlayScalePolicy.fitBounds(DisplaySize(1920, 1080), DisplaySize(0, 1080)))

        val safe = mapOf(
            "hasController" to true,
            "ownsController" to true,
            "hasUsableSurface" to true,
            "hasRemoteSize" to true,
            "handshakeReset" to false,
            "restartPending" to false,
            "shuttingDown" to false,
        )
        assertTrue(shouldKeepSession(safe))

        listOf(
            safe + ("hasController" to false),
            safe + ("ownsController" to false),
            safe + ("hasUsableSurface" to false),
            safe + ("hasRemoteSize" to false),
            safe + ("handshakeReset" to true),
            safe + ("restartPending" to true),
            safe + ("shuttingDown" to true),
        ).forEach { assertFalse(shouldKeepSession(it)) }
    }

    private fun shouldKeepSession(values: Map<String, Boolean>): Boolean =
        LocalCarPlayScalePolicy.shouldKeepSession(
            hasController = values.getValue("hasController"),
            ownsController = values.getValue("ownsController"),
            hasUsableSurface = values.getValue("hasUsableSurface"),
            hasRemoteSize = values.getValue("hasRemoteSize"),
            handshakeReset = values.getValue("handshakeReset"),
            restartPending = values.getValue("restartPending"),
            shuttingDown = values.getValue("shuttingDown"),
        )

    private fun canKeepSession(): Boolean =
        LocalCarPlayScalePolicy.shouldKeepSession(
            hasController = true,
            ownsController = true,
            hasUsableSurface = true,
            hasRemoteSize = true,
            handshakeReset = false,
            restartPending = false,
            shuttingDown = false,
        )
}
