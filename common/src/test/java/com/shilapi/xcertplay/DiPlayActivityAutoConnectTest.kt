package com.shilapi.xcertplay

import android.app.Application
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayActivityAutoConnectTest {
    private lateinit var app: Application
    private var bootstrapReady = false

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        val ready = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        bootstrapReady = ready.getBoolean(DiPlayBootstrap)
        ready.setBoolean(DiPlayBootstrap, true)
        AutoConnectCoordinator.shared.resetForTests()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        clearPreferences()
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        AutoConnectCoordinator.shared.resetForTests()
        clearPreferences()
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply {
            isAccessible = true
            setBoolean(DiPlayBootstrap, bootstrapReady)
        }
    }

    @Test
    fun ac01_autoConnectOnOpensTheHostOnAppLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("auto-connect did not open CarPlayHostActivity", started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac02_autoConnectOffDoesNotOpenTheHost() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, false)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac05_recreatedHomeActivityDoesNotStartASecondConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedActivity)

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac06_repeatedOnResumeDoesNotStartASecondConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedActivity)

        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac07_newIntentOnTheHomeActivityCanStartTheConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)

        controller.newIntent(Intent(app, DiPlayActivity::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac08_existingSessionIsNotReplacedByAutoConnect() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val stopAction = setFakeSession()

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertSame(stopAction, sessionStopAction())
        controller.pause().stop().destroy()
    }

    @Test
    fun ac23_resumingTheSettingsPageDoesNotConnect() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)

        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac25_intentionalDisconnectSuppressesTheNextAutomaticLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        setFakeSession()
        CarPlayBackgroundSession.active = true

        val first = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        findButton(first.get(), first.get().getString(R.string.ui_disconnect))?.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AutoConnectState.SUPPRESSED, AutoConnectCoordinator.shared.currentState())
        first.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        shadowOf(app).clearNextStartedActivities()

        val second = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("auto-connect ignored the explicit disconnect", shadowOf(app).nextStartedActivity)
        second.pause().stop().destroy()
    }

    private fun setFakeSession(): (() -> Unit) -> Unit {
        val field = CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply {
            isAccessible = true
        }
        val action: (() -> Unit) -> Unit = { completion -> completion() }
        field.set(CarPlayBackgroundSession, action)
        return action
    }

    private fun sessionStopAction(): Any? =
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply {
            isAccessible = true
        }.get(CarPlayBackgroundSession)

    private fun clearPreferences() {
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun findButton(activity: android.app.Activity, text: String): Button? =
        findButtonIn(activity.window.decorView, text)

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
