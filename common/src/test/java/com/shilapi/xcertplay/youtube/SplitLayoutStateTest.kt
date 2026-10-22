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
    fun dspAndYoutubeSplitsAreExclusiveAndKeepTheSameCarPlayRatio() {
        val full = HostLayoutState()
        val youtube = full.enterYoutubeSplit()
        val dsp = youtube.enterDspSplit()

        assertEquals(HostLayoutMode.CARPLAY_DSP_SPLIT, dsp.mode)
        assertEquals(SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION, dsp.carPlayFraction)
        assertEquals(HostLayoutMode.CARPLAY_FULL, dsp.returnToCarPlay().mode)
    }

    @Test
    fun restoresNewModesAndFallsBackFromUnknownOrLegacyBundleValues() {
        assertEquals(
            HostLayoutMode.CARPLAY_DSP_SPLIT,
            HostLayoutState.restoreMode(HostLayoutMode.CARPLAY_DSP_SPLIT.name, legacyYoutubeSplit = false),
        )
        assertEquals(
            HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
            HostLayoutState.restoreMode(null, legacyYoutubeSplit = true),
        )
        assertEquals(
            HostLayoutMode.CARPLAY_FULL,
            HostLayoutState.restoreMode("FUTURE_MODE", legacyYoutubeSplit = true),
        )
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

        val dsp = CarPlayHostActions.applyLayoutCommand(full, CarPlayHostActions.ENTER_DSP_SPLIT)
        assertEquals(HostLayoutMode.CARPLAY_DSP_SPLIT, dsp.mode)
        assertEquals(
            HostLayoutMode.CARPLAY_FULL,
            CarPlayHostActions.applyLayoutCommand(dsp, CarPlayHostActions.EXIT_YOUTUBE_SPLIT).mode,
        )
    }
}
