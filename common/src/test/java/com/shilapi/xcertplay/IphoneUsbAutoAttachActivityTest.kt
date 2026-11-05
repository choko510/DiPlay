package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IphoneUsbAutoAttachActivityTest {
    @Test
    fun rejectsExplicitLaunchesWithoutUsbAttachActionOrDevice() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(
            IphoneUsbAutoAttachActivity::class.java,
            Intent(context, IphoneUsbAutoAttachActivity::class.java).setAction("com.example.NOT_USB_ATTACH"),
        ).setup().get()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun rejectsUsbActionWithoutADeviceEvenWhenTheFeatureIsEnabled() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val previousPreference = preferences.getBoolean("auto_launch_on_iphone_usb_attach", false)
        val component = android.content.ComponentName(context, IphoneUsbAutoAttachActivity::class.java)
        val previousComponentState = context.packageManager.getComponentEnabledSetting(component)
        try {
            assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(context, true))
            val activity = Robolectric.buildActivity(
                IphoneUsbAutoAttachActivity::class.java,
                Intent(context, IphoneUsbAutoAttachActivity::class.java)
                    .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ).setup().get()

            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
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
