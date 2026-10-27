package com.shilapi.xcertplay

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IphoneUsbAutoLaunchSettingsTest {
    @Test
    fun settingDefaultsOffAndTogglesTheAttachComponent() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val component = ComponentName(context, IphoneUsbAutoAttachActivity::class.java)
        val previousPreference = preferences.getBoolean("auto_launch_on_iphone_usb_attach", false)
        val previousComponentState = context.packageManager.getComponentEnabledSetting(component)

        try {
            preferences.edit().remove("auto_launch_on_iphone_usb_attach").commit()
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )

            assertFalse(AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context))
            assertFalse(IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context))
            assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(context, true))
            assertTrue(IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context))
            assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(context, false))
            assertFalse(AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context))
            assertFalse(IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context))
        } finally {
            preferences.edit().putBoolean("auto_launch_on_iphone_usb_attach", previousPreference).commit()
            context.packageManager.setComponentEnabledSetting(
                component,
                previousComponentState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun reconcileRestoresTheManifestComponentToTheSavedPreference() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val component = ComponentName(context, IphoneUsbAutoAttachActivity::class.java)
        val previousPreference = preferences.getBoolean("auto_launch_on_iphone_usb_attach", false)
        val previousComponentState = context.packageManager.getComponentEnabledSetting(component)

        try {
            preferences.edit().putBoolean("auto_launch_on_iphone_usb_attach", false).commit()
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )

            assertTrue(IphoneUsbAutoLaunchSettings.reconcile(context))
            assertFalse(IphoneUsbAutoLaunchSettings.isComponentEnabled(context))
        } finally {
            preferences.edit().putBoolean("auto_launch_on_iphone_usb_attach", previousPreference).commit()
            context.packageManager.setComponentEnabledSetting(
                component,
                previousComponentState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
