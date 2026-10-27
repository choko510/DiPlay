package com.shilapi.xcertplay

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayActivityNavigationTest {
    @Test
    fun systemBackFromAboutReturnsToSettingsThenMenu() {
        val activity = launchMenuOriginSettings()

        findButton(activity, activity.getString(R.string.ui_about_diplay)).performClick()
        assertTrue(hasText(activity, activity.getString(R.string.ui_about_tagline)))
        assertFalse(hasText(activity, activity.getString(R.string.ui_settings_title)))

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun toolbarBackFromAboutReturnsToSettingsThenMenu() {
        val activity = launchMenuOriginSettings()

        findButton(activity, activity.getString(R.string.ui_about_diplay)).performClick()
        findButton(activity, activity.getString(R.string.ui_back)).performClick()
        assertTrue(hasText(activity, activity.getString(R.string.ui_settings_title)))
        assertFalse(activity.isFinishing)

        findButton(activity, activity.getString(R.string.ui_back)).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun iphoneUsbAutoLaunchSettingIsVisibleAndDefaultsOff() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val component = ComponentName(application, IphoneUsbAutoAttachActivity::class.java)
        val hadSavedValue = preferences.contains("auto_launch_on_iphone_usb_attach")
        val savedValue = preferences.getBoolean("auto_launch_on_iphone_usb_attach", false)
        val previousComponentState = application.packageManager.getComponentEnabledSetting(component)

        try {
            preferences.edit().remove("auto_launch_on_iphone_usb_attach").commit()
            application.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
            val activity = launchMenuOriginSettings()
            val control = findSwitch(activity, activity.getString(R.string.ui_iphone_usb_autolaunch_title))

            assertTrue(hasText(activity, activity.getString(R.string.ui_iphone_usb_autolaunch_description)))
            assertNotNull(control)
            assertFalse(control!!.isChecked)
            activity.finish()
        } finally {
            val editor = preferences.edit()
            if (hadSavedValue) editor.putBoolean("auto_launch_on_iphone_usb_attach", savedValue)
            else editor.remove("auto_launch_on_iphone_usb_attach")
            editor.commit()
            application.packageManager.setComponentEnabledSetting(
                component,
                previousComponentState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun reconnectFromMenuOriginSettingsFinishesAfterOpeningCarPlayHost() {
        val activity = launchMenuOriginSettings()
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("xcertplay_airplay", 0)
        val hadWirelessValue = preferences.contains("wireless_enabled")
        val wirelessValue = preferences.getBoolean("wireless_enabled", true)
        val backgroundSession = CarPlayBackgroundSession
        val stopActionField = backgroundSession.javaClass.getDeclaredField("stopAction").apply {
            isAccessible = true
        }
        val previousStopAction = stopActionField.get(backgroundSession)
        val wasActive = CarPlayBackgroundSession.active

        try {
            stopActionField.set(backgroundSession, { completion: () -> Unit -> completion() })
            CarPlayBackgroundSession.active = true
            DiPlayActivity::class.java.getDeclaredField("setupError").apply {
                isAccessible = true
                set(activity, null)
            }
            DiPlayActivity::class.java.getDeclaredMethod("connect", Boolean::class.javaPrimitiveType!!).apply {
                isAccessible = true
                invoke(activity, false)
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activity.isFinishing)
            val startedIntent = shadowOf(activity).nextStartedActivity
            assertNotNull(startedIntent)
            assertEquals(CarPlayHostActivity::class.java.name, startedIntent.component?.className)
        } finally {
            val editor = preferences.edit()
            if (hadWirelessValue) editor.putBoolean("wireless_enabled", wirelessValue)
            else editor.remove("wireless_enabled")
            editor.commit()
            stopActionField.set(backgroundSession, previousStopAction)
            CarPlayBackgroundSession.active = wasActive
        }
    }

    private fun launchMenuOriginSettings(): DiPlayActivity {
        val application = RuntimeEnvironment.getApplication()
        val intent = Intent(application, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    private fun findButton(activity: DiPlayActivity, text: String): Button =
        findButtonIn(activity.window.decorView, text).also { assertNotNull("Button '$text' was not found", it) }!!

    private fun findSwitch(activity: DiPlayActivity, title: String): Switch? =
        findSwitchIn(activity.window.decorView, title)

    private fun findSwitchIn(view: View, title: String): Switch? {
        if (view is Switch && view.contentDescription == title) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSwitchIn(view.getChildAt(index), title)?.let { return it }
            }
        }
        return null
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
