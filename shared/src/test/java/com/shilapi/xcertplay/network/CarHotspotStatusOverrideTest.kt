package com.shilapi.xcertplay.network

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotStatusOverrideTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clear() {
        context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun onlyAnExplicitConnectChoiceOverridesAnOffReportAndAnOnReportClearsIt() {
        assertTrue(CarHotspotStatusOverride.isReportedOff(context, false))
        assertFalse(CarHotspotStatusOverride.isReportedOff(context, null))

        CarHotspotStatusOverride.ignoreOffReport(context)
        assertFalse(CarHotspotStatusOverride.isReportedOff(context, false))
        assertFalse(CarHotspotStatusOverride.isReportedOff(context, null))

        assertFalse(CarHotspotStatusOverride.isReportedOff(context, true))
        assertTrue(CarHotspotStatusOverride.isReportedOff(context, false))
    }
}
