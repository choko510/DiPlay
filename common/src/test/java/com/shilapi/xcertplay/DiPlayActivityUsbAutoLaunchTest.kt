// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the reported bug: while CarPlay is connected the user opens DiPlay settings and the USB
 * auto-launch path must not send the CarPlay host back to the front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiPlayActivityUsbAutoLaunchTest {
    private lateinit var app: Application
    private var device: UsbDevice? = null
    private var previousAutoLaunch = false
    private var bootstrapReadyField: Field? = null
    private var previousBootstrapReady = false

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resetForTesting()
        CarPlayUsbAttachDispatcher.clearForTesting()
        CarPlayBackgroundSession.clear()
        bootstrapReadyField = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        previousBootstrapReady = bootstrapReadyField!!.getBoolean(DiPlayBootstrap)
        bootstrapReadyField!!.setBoolean(DiPlayBootstrap, true)
        previousAutoLaunch = AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(app)
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(app, true))
    }

    @After
    fun release() {
        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resetForTesting()
        CarPlayUsbAttachDispatcher.clearForTesting()
        CarPlayBackgroundSession.clear()
        device?.let { TestIphoneUsbDevice.detach(app, it) }
        device = null
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(app, previousAutoLaunch))
        bootstrapReadyField?.setBoolean(DiPlayBootstrap, previousBootstrapReady)
    }

    @Test
    fun settingsOpenedFromCarPlayKeepsTheScreenInsteadOfReturningToCarPlay() {
        attachIphone()
        storeWiredSession()

        val activity = launchSettings()

        assertNull("the CarPlay host must not be brought forward", shadowOf(activity).nextStartedActivity)
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    @Test
    fun settingsOpenedFromTheCarPlayMenuKeepsTheScreen() {
        attachIphone()

        val activity = launchSettings()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun dspSettingsCanBeOpenedFromSettingsWithoutStartingCarPlay() {
        attachIphone()
        storeWiredSession()
        val activity = launchSettings()

        findButton(activity, activity.getString(R.string.dsp_settings_entry_button)).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(DspSettingsActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun plainAppStartStillAdoptsAnExistingWiredSession() {
        attachIphone()
        storeWiredSession()

        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("a plain app start keeps the auto-connect behaviour", started)
        assertEquals(CarPlayHostActivity::class.java.name, started?.component?.className)
        assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(started!!))
    }

    @Test
    fun plainAppStartLaunchesTheAttachedIphoneOnUsb() {
        attachIphone()

        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        assertNull("the launch is dispatched once", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun disabledUsbAutoLaunchLeavesTheSettingsScreenQuiet() {
        attachIphone()
        storeWiredSession()
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(app, false))

        val activity = launchSettings()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
    }

    @Test
    fun explicitNotificationRequestStillOpensCarPlayFromSettings() {
        attachIphone()
        storeWiredSession()
        val intent = settingsIntent()
            .putExtra(IphoneUsbAutoLaunchNotification.EXTRA_OPEN_CONNECTED_IPHONE, true)

        val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(started!!))
    }

    @Test
    fun recreatedSettingsActivityRestoresTheSettingsDestinationWithoutStartingCarPlay() {
        attachIphone()
        storeWiredSession()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        val original = controller.get()
        assertNull(shadowOf(original).nextStartedActivity)

        controller.recreate()

        val recreated = controller.get()
        assertNotSame("recreate() must build a new Activity instance", original, recreated)
        assertNull(
            "the recreated Activity must not take the user back to CarPlay",
            shadowOf(recreated).nextStartedActivity,
        )
        assertTrue(hasText(recreated, recreated.getString(R.string.ui_settings_title)))
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    @Test
    fun repeatedSettingsNavigationReusesTheRunningActivity() {
        attachIphone()
        storeWiredSession()
        val activity = launchSettings()

        deliverNewIntent(activity, settingsIntent())

        assertNull("re-delivering settings does not start anything", shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
    }

    @Test
    fun threeFingerSettingsWithoutReturnToMenuIsProtected() {
        attachIphone()
        storeWiredSession()

        // CarPlayHostActivity.showDiPlayHome("settings") does not set EXTRA_RETURN_TO_MENU.
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    @Test
    fun openingSettingsInsideTheAppUpdatesTheFocusAndKeepsTheScreen() {
        // Start on the home page without a connected iPhone, then attach one from settings.
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        assertEquals(DiPlaySurface.HOME, DiPlayUiFocus.current())
        val attached = attachIphone()

        findButton(activity, activity.getString(R.string.ui_settings)).performClick()

        assertEquals("the in-Activity page change must update the focus", DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
        assertFalse(
            "an automatic USB request is held now that settings is visible",
            IphoneUsbAutoLaunchDispatcher.dispatch(
                activity,
                attached.toUsbAttachDeviceIdentity(),
                IphoneUsbAutoLaunchSource.ATTACH,
            ),
        )
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun newIntentPageChangeUpdatesTheFocus() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        assertEquals(DiPlaySurface.HOME, DiPlayUiFocus.current())

        deliverNewIntent(activity, settingsIntent())

        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
    }

    @Test
    fun returningFromThreeFingerSettingsToTheHomePageReplaysTheHeldRequest() {
        // 3-finger settings: page=settings without EXTRA_RETURN_TO_MENU, so Back shows the home page.
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
        val attached = attachIphone()

        assertFalse(
            IphoneUsbAutoLaunchDispatcher.dispatch(
                activity,
                attached.toUsbAttachDeviceIdentity(),
                IphoneUsbAutoLaunchSource.ATTACH,
            ),
        )
        assertNull(shadowOf(activity).nextStartedActivity)

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(hasText(activity, activity.getString(R.string.ui_home_title)))
        assertEquals(DiPlaySurface.HOME, DiPlayUiFocus.current())
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the held USB request resumes on the home page", started)
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    @Test
    fun closingMenuSettingsHandsTheProtectionBackToTheMenu() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        val activity = controller.get()
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())

        // The menu's settings screen returns with finish(), so the menu never resumes first.
        activity.finish()
        controller.pause()

        assertEquals("finish() must announce the menu", DiPlaySurface.MENU, DiPlayUiFocus.current())
        assertTrue(DiPlayUiFocus.isUserNavigationTarget())
    }

    private fun launchSettings(): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup().get()

    private fun deliverNewIntent(activity: DiPlayActivity, intent: Intent) {
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }
            .invoke(activity, intent)
    }

    private fun settingsIntent(): Intent = Intent(app, DiPlayActivity::class.java)
        .putExtra("page", "settings")
        .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)

    private fun attachIphone(): UsbDevice {
        val attached = TestIphoneUsbDevice.attach(app)
        device = attached
        return attached
    }

    private fun storeWiredSession() {
        CarPlayBackgroundSession.store(
            TestCarPlayPlaceholders.controller(CarPlayTransport.WIRED),
            AndroidMediaSink(),
            800,
            480,
            owner = Any(),
            stop = { done -> done() },
        )
    }

    private fun findButton(activity: DiPlayActivity, text: String): Button {
        val button = findButtonIn(activity.window.decorView, text)
        assertNotNull("Button '$text' was not found", button)
        return button!!
    }

    private fun findButtonIn(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonIn(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun hasText(activity: DiPlayActivity, text: String): Boolean =
        hasTextIn(activity.window.decorView, text)

    private fun hasTextIn(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasTextIn(view.getChildAt(index), text)) return true
            }
        }
        return false
    }
}
