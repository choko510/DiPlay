package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitViewModeTest {
    @Test fun featureFlagDefaultsToLegacyAndOnlyEnablesLocalModeInDebugBuilds() {
        assertEquals(SplitViewMode.LEGACY_RECONNECT, SplitViewMode.fromBuild(debuggable = true, value = "off"))
        assertEquals(
            SplitViewMode.LOCAL_SCALE_NO_RESTART,
            SplitViewMode.fromBuild(debuggable = true, value = "local"),
        )
        assertEquals(
            SplitViewMode.DYNAMIC_VIEW_AREA_EXPERIMENTAL,
            SplitViewMode.fromBuild(debuggable = true, value = "dynamic-experimental"),
        )
        assertEquals(
            SplitViewMode.LEGACY_RECONNECT,
            SplitViewMode.fromBuild(debuggable = false, value = "local"),
        )
        assertEquals(
            SplitViewMode.LEGACY_RECONNECT,
            SplitViewMode.fromBuild(debuggable = false, value = "dynamic-experimental"),
        )
        assertEquals(SplitViewMode.LEGACY_RECONNECT, SplitViewMode.fromBuild(debuggable = true, value = "unknown"))
    }

    @Test fun localSplitPaneResizeKeepsTheActiveCanvasButPhysicalResizeStillNegotiates() {
        val active = DisplaySize(1920, 1080)
        assertNull(
            SplitDisplaySizePolicy.negotiatedSize(
                localSplit = true,
                observed = DisplaySize(1056, 1080),
                fullCanvas = active,
                activeCanvas = active,
                restartReady = false,
                restartPending = false,
            ),
        )
        assertEquals(
            DisplaySize(2048, 1080),
            SplitDisplaySizePolicy.negotiatedSize(
                localSplit = true,
                observed = DisplaySize(1126, 1080),
                fullCanvas = DisplaySize(2048, 1080),
                activeCanvas = active,
                restartReady = false,
                restartPending = false,
            ),
        )
        assertEquals(
            DisplaySize(1056, 1080),
            SplitDisplaySizePolicy.negotiatedSize(
                localSplit = false,
                observed = DisplaySize(1056, 1080),
                fullCanvas = active,
                activeCanvas = active,
                restartReady = false,
                restartPending = false,
            ),
        )
    }
}
