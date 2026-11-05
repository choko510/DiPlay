package com.shilapi.xcertplay.network

import android.content.Context
import java.io.IOException
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

    @Test
    fun failedManualStartRestoresTheOffWarningOnlyForAnExplicitOverrideAndAStillOffReport() {
        CarHotspotStatusOverride.ignoreOffReport(context)
        assertTrue(
            CarHotspotStatusOverride.clearAfterFailedManualStart(
                context,
                false,
                ManualHotspotNotReadyException("local hotspot interface was not found"),
            ),
        )
        assertTrue(CarHotspotStatusOverride.isReportedOff(context, false))

        CarHotspotStatusOverride.ignoreOffReport(context)
        assertFalse(
            CarHotspotStatusOverride.clearAfterFailedManualStart(
                context,
                null,
                ManualHotspotNotReadyException("local hotspot interface was not found"),
            ),
        )
        assertFalse(
            CarHotspotStatusOverride.clearAfterFailedManualStart(
                context,
                false,
                IOException("manual hotspot credentials are invalid"),
            ),
        )
        assertFalse(CarHotspotStatusOverride.isReportedOff(context, false))
    }
}
