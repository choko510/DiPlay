package com.shilapi.xcertplay.youtube

import com.shilapi.xcertplay.CarPlayHostActions
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitLayoutStateTest {
    @Test
    fun splitCanBeEnteredAndExitedWithoutChangingItsRatio() {
        val full = HostLayoutState()
        val split = full.enterYoutubeSplit()

        assertEquals(HostLayoutMode.CARPLAY_YOUTUBE_SPLIT, split.mode)
        assertEquals(SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION, split.carPlayFraction)
        assertEquals(HostLayoutMode.CARPLAY_FULL, split.returnToCarPlay().mode)
    }

    @Test
    fun splitRatioIsClampedAndInvalidValuesUseTheDefault() {
        assertEquals(0.50f, SplitLayoutConfig.normalizeCarPlayFraction(0.1f))
        assertEquals(0.60f, SplitLayoutConfig.normalizeCarPlayFraction(0.9f))
        assertEquals(
            SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
            SplitLayoutConfig.normalizeCarPlayFraction(Float.NaN),
        )
        assertEquals(
            SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
            SplitLayoutConfig.normalizeCarPlayFraction(Float.POSITIVE_INFINITY),
        )
    }

    @Test
    fun hostIntentActionsMapToSplitAndFullLayoutStates() {
        val full = HostLayoutState()
        val split = CarPlayHostActions.applyLayoutCommand(full, CarPlayHostActions.ENTER_YOUTUBE_SPLIT)

        assertEquals(HostLayoutMode.CARPLAY_YOUTUBE_SPLIT, split.mode)
        assertEquals(
            HostLayoutMode.CARPLAY_FULL,
            CarPlayHostActions.applyLayoutCommand(split, CarPlayHostActions.EXIT_YOUTUBE_SPLIT).mode,
        )
        assertEquals(full, CarPlayHostActions.applyLayoutCommand(full, null))
    }
}
