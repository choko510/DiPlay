package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/** In-app GeckoView split is not Android's system multiwindow mode. */
class Step4SplitBaselineTest {
    @Test fun enteringAndLeavingSplitKeepsUserSelectedFraction() {
        val initial = HostLayoutState(carPlayFraction = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION)
        val split = initial.enterYoutubeSplit()
        assertEquals(HostLayoutMode.CARPLAY_YOUTUBE_SPLIT, split.mode)
        assertEquals(0.55f, split.carPlayFraction, 0.0001f)
        assertEquals(HostLayoutMode.CARPLAY_FULL, split.returnToCarPlay().mode)
    }

    @Test fun fractionIsClampedToSupportedRange() {
        assertEquals(0.5f, SplitLayoutConfig.normalizeCarPlayFraction(-1f), 0.0001f)
        assertEquals(0.6f, SplitLayoutConfig.normalizeCarPlayFraction(1f), 0.0001f)
    }
}
