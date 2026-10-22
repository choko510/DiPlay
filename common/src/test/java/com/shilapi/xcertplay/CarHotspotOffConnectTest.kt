package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class CarHotspotOffConnectTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private var previousSessionActive = false

    @Before
    fun setUp() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveWirelessHotspotMode(context, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "example-pass-123")
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Test iPhone")
        previousSessionActive = CarPlayBackgroundSession.active
        CarPlayBackgroundSession.active = false
        assertEquals(false, com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(context))
    }

    @After
    fun tearDown() {
        activity?.finish()
        activity = null
        CarPlayBackgroundSession.active = previousSessionActive
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun explicitConnectFromTheOffDialogDoesNotReopenTheSameDialog() {
        activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home"),
        ).setup().get()
        DiPlayActivity::class.java.getDeclaredField("setupError").apply {
            isAccessible = true
            set(activity, null)
        }

        findButton(activity!!, activity!!.getString(R.string.ui_connect_phone)).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(com.shilapi.xcertplay.network.CarHotspotStatusOverride.isReportedOff(context, false))
        val started = shadowOf(activity!!).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
    }

    private fun findButton(activity: DiPlayActivity, text: String): Button =
        findButtonIn(activity.window.decorView, text).also { assertNotNull("Button '$text' was not found", it) }!!

    private fun findButtonIn(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonIn(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
