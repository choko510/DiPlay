package com.shilapi.xcertplay

import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.hardware.usb.UsbManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import com.shilapi.xcertplay.youtube.HostLayoutState
import com.shilapi.xcertplay.youtube.HostLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostActionsTest {
    @Test
    fun onlyRecognizedHostActionsClearRestartSuppression() {
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.OPEN_CARPLAY))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.ENTER_YOUTUBE_SPLIT))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.ENTER_DSP_SPLIT))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.EXIT_YOUTUBE_SPLIT))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression("android.hardware.usb.action.USB_DEVICE_ATTACHED"))

        assertFalse(CarPlayHostActions.shouldClearRestartSuppression(null))
        assertFalse(CarPlayHostActions.shouldClearRestartSuppression("com.example.unknown"))
    }

    @Test
    fun externalHostActionStringsAreIgnoredAndInternalTokensAreSingleUse() {
        val externalOpen = Intent().setAction(CarPlayHostActions.OPEN_CARPLAY)
        val externalSplit = Intent().setAction(CarPlayHostActions.ENTER_DSP_SPLIT)
        assertNull(CarPlayHostActions.consumeInternalAction(externalOpen))
        assertNull(CarPlayHostActions.consumeInternalAction(externalSplit))

        val internal = CarPlayHostActions.createInternalIntent(
            RuntimeEnvironment.getApplication(),
            CarPlayHostActions.ENTER_DSP_SPLIT,
        )
        assertEquals(CarPlayHostActions.ENTER_DSP_SPLIT, CarPlayHostActions.consumeInternalAction(internal))
        assertNull(CarPlayHostActions.consumeInternalAction(internal))
    }

    @Test
    fun theSameNotificationPendingIntentCanOpenCarPlayTwice() {
        val application = RuntimeEnvironment.getApplication()
        val pendingIntent = PendingIntent.getActivity(
            application,
            0,
            CarPlayHostActions.createNotificationOpenIntent(application),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        repeat(2) {
            pendingIntent.send()
            val launchedIntent = shadowOf(application).nextStartedActivity
            assertNotNull(launchedIntent)
            assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(launchedIntent))
        }
    }

    @Test
    fun openCarPlayMeansFullLayoutWhenRestoringASplit() {
        val split = HostLayoutState().enterDspSplit()

        assertEquals(
            HostLayoutMode.CARPLAY_FULL,
            CarPlayHostActions.applyLayoutCommand(split, CarPlayHostActions.OPEN_CARPLAY).mode,
        )
    }

    @Test
    fun aNewLaunchActionReplacesASavedPendingSplitMode() {
        assertNull(
            CarPlayHostActions.pendingSplitModeAfterLaunch(
                HostLayoutMode.CARPLAY_DSP_SPLIT,
                CarPlayHostActions.ENTER_YOUTUBE_SPLIT,
            ),
        )
        assertNull(
            CarPlayHostActions.pendingSplitModeAfterLaunch(
                HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
                CarPlayHostActions.OPEN_CARPLAY,
            ),
        )
        assertEquals(
            HostLayoutMode.CARPLAY_DSP_SPLIT,
            CarPlayHostActions.pendingSplitModeAfterLaunch(
                HostLayoutMode.CARPLAY_DSP_SPLIT,
                action = null,
            ),
        )
        assertEquals(
            HostLayoutMode.CARPLAY_DSP_SPLIT,
            CarPlayHostActions.pendingSplitModeAfterLaunch(
                HostLayoutMode.CARPLAY_DSP_SPLIT,
                CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            ),
        )
    }

    @Test
    fun usbAutoLaunchCanCarryTransientHostLayoutStateAcrossWirelessHandoff() {
        val application = RuntimeEnvironment.getApplication()
        val identity = UsbAttachDeviceIdentity(
            deviceId = 14,
            deviceName = "test-apple-device",
            vendorId = 0x05ac,
            productId = 0x12a8,
        )
        val launchState = CarPlayHostLaunchState(
            layoutMode = HostLayoutMode.CARPLAY_DSP_SPLIT,
            carPlayFraction = 0.62f,
            pendingSplitMode = HostLayoutMode.CARPLAY_DSP_SPLIT,
        )
        val internalIntent = CarPlayHostActions.createInternalIntent(
            application,
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            identity,
            launchState,
        )

        val request = CarPlayHostActions.consumeInternalRequest(internalIntent)

        assertEquals(CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED, request?.action)
        assertEquals(identity, request?.usbDeviceIdentity)
        assertEquals(launchState, request?.hostLaunchState)
    }

    @Test
    fun autoUsbIntentCanRecoverAfterItsProcessLocalTokenExpires() {
        val application = RuntimeEnvironment.getApplication()
        val identity = UsbAttachDeviceIdentity(
            deviceId = 22,
            deviceName = "test-apple-device",
            vendorId = 0x05ac,
            productId = 0x12a8,
        )
        val autoUsbIntent = CarPlayHostActions.createInternalIntent(
            application,
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            identity,
        )
        repeat(16) {
            CarPlayHostActions.createInternalIntent(application, CarPlayHostActions.OPEN_CARPLAY)
        }

        val recovered = CarPlayHostActions.consumeInternalRequest(autoUsbIntent)

        assertEquals(CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED, recovered?.action)
        assertEquals(identity, recovered?.usbDeviceIdentity)
    }

    @Test
    fun usbAttachTrampolineRequiresTheSameCurrentlyAttachedCh341Device() {
        val device = UsbAttachDeviceIdentity(
            deviceId = 17,
            deviceName = "/dev/bus/usb/001/004",
            vendorId = 0x1a86,
            productId = 0x5512,
        )

        assertTrue(UsbAttachIntentPolicy.isVerifiedCh341(device, listOf(device), permissionGranted = true))
        assertFalse(UsbAttachIntentPolicy.isVerifiedCh341(device, listOf(device), permissionGranted = false))
        assertFalse(UsbAttachIntentPolicy.isVerifiedCh341(device, emptyList(), permissionGranted = true))
        assertFalse(
            UsbAttachIntentPolicy.isVerifiedCh341(
                device.copy(deviceId = 18),
                listOf(device),
                permissionGranted = true,
            ),
        )
        assertFalse(
            UsbAttachIntentPolicy.isVerifiedCh341(
                device.copy(vendorId = 0x1234),
                listOf(device),
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun usbEntryPointsAreExportedWithDifferentTrustPolicies() {
        val application = RuntimeEnvironment.getApplication()
        val packageManager = application.packageManager
        val host = packageManager.getActivityInfo(
            ComponentName(application, CarPlayHostActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val usbTrampoline = packageManager.getActivityInfo(
            ComponentName(application, CarPlayUsbAttachActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val iphoneUsbEntry = packageManager.getActivityInfo(
            ComponentName(application, IphoneUsbAutoAttachActivity::class.java),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        assertFalse(host.exported)
        assertTrue(usbTrampoline.exported)
        assertEquals("android.permission.MANAGE_USB", usbTrampoline.permission)
        assertTrue(iphoneUsbEntry.exported)
        assertFalse(iphoneUsbEntry.enabled)
        assertEquals("android.permission.MANAGE_USB", iphoneUsbEntry.permission)
        assertTrue(
            iphoneUsbEntry.metaData?.getInt(UsbManager.ACTION_USB_DEVICE_ATTACHED, 0) != 0,
        )
    }
}
