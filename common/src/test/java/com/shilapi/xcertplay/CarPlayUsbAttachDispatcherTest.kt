// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The CH341 adapter attach path used to start the CarPlay host regardless of the visible screen.
 * A verified attach is now held while DiPlay settings or the menu is foreground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayUsbAttachDispatcherTest {
    private lateinit var app: Application
    private var device: UsbDevice? = null

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        DiPlayUiFocus.reset()
        CarPlayUsbAttachDispatcher.clearForTesting()
    }

    @After
    fun release() {
        DiPlayUiFocus.reset()
        CarPlayUsbAttachDispatcher.clearForTesting()
        device?.let { TestIphoneUsbDevice.detach(app, it) }
        device = null
    }

    @Test
    fun verifiedCh341AttachStartsTheHostImmediately() {
        val attached = attachCh341()

        CarPlayUsbAttachDispatcher.dispatchOrHold(app, attached)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        assertTrue("the host is started outside an Activity", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun verifiedAttachWhileSettingsOwnTheScreenIsHeldUntilTheUserLeavesSettings() {
        val attached = attachCh341()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)

        CarPlayUsbAttachDispatcher.dispatchOrHold(app, attached)
        assertNull("settings keep the screen", shadowOf(app).nextStartedActivity)

        CarPlayUsbAttachDispatcher.resume(app)
        assertNull("replaying while settings is visible is still held", shadowOf(app).nextStartedActivity)

        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        CarPlayUsbAttachDispatcher.resume(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        CarPlayUsbAttachDispatcher.resume(app)
        assertNull("the held request is delivered once", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun heldAttachForAnUnpluggedAdapterIsDropped() {
        val attached = attachCh341()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)
        CarPlayUsbAttachDispatcher.dispatchOrHold(app, attached)

        TestIphoneUsbDevice.detach(app, device!!)
        device = null
        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        CarPlayUsbAttachDispatcher.resume(app)

        assertNull("a stale attach must not start the host", shadowOf(app).nextStartedActivity)
        CarPlayUsbAttachDispatcher.resume(app)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun heldAttachWithoutUsbPermissionIsDropped() {
        val attached = attachCh341(permissionGranted = false)
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)
        CarPlayUsbAttachDispatcher.dispatchOrHold(app, attached)

        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        CarPlayUsbAttachDispatcher.resume(app)

        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun attachActivityDoesNotFrontCarPlayWhileSettingsAreOpen() {
        val attached = attachCh341()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)

        val attachActivity = Robolectric.buildActivity(
            CarPlayUsbAttachActivity::class.java,
            Intent(app, CarPlayUsbAttachActivity::class.java)
                .setAction(CarPlayHostActions.USB_DEVICE_ATTACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, device),
        ).setup().get()

        assertTrue(attachActivity.isFinishing)
        assertNull(shadowOf(attachActivity).nextStartedActivity)

        DiPlayUiFocus.resumed(this, DiPlaySurface.HOME)
        CarPlayUsbAttachDispatcher.resume(attachActivity)

        val started = shadowOf(attachActivity).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    @Test
    fun holdVerifiedCh341KeepsTheAdapterForACarPlayResume() {
        val attached = attachCh341()

        assertTrue(CarPlayUsbAttachDispatcher.holdVerifiedCh341(app))
        assertNull(shadowOf(app).nextStartedActivity)

        CarPlayUsbAttachDispatcher.resume(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        assertEquals(attached.deviceName, "/dev/bus/usb/001/077")
    }

    @Test
    fun holdVerifiedCh341WithoutAnAdapterHoldsNothing() {
        assertFalse(CarPlayUsbAttachDispatcher.holdVerifiedCh341(app))

        CarPlayUsbAttachDispatcher.resume(app)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun theNotificationFallbackStaysQuietWithoutNotificationPermission() {
        // The fallback is only used when nothing can be held; it must not throw when the user has
        // not granted notifications.
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(IphoneUsbAutoLaunchNotification.show(app))
    }

    @Test
    fun aFailedLaunchKeepsTheHeldAttachForTheNextAttempt() {
        val attached = attachCh341()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)
        CarPlayUsbAttachDispatcher.dispatchOrHold(app, attached)

        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        CarPlayUsbAttachDispatcher.resume(ThrowingContext(app))
        assertNull("nothing could be started yet", shadowOf(app).nextStartedActivity)

        CarPlayUsbAttachDispatcher.resume(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the request survives a failed launch", started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    @Test
    fun aFailedImmediateLaunchIsHeldInsteadOfDropped() {
        val attached = attachCh341()

        CarPlayUsbAttachDispatcher.dispatchOrHold(ThrowingContext(app), attached)
        assertNull(shadowOf(app).nextStartedActivity)

        CarPlayUsbAttachDispatcher.resume(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    /** A context whose `startActivity` fails, as it does while the system cannot resolve it. */
    private class ThrowingContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            throw IllegalStateException("Activity not found")
        }
    }

    private fun attachCh341(permissionGranted: Boolean = true): UsbAttachDeviceIdentity {
        val attached = TestIphoneUsbDevice.attach(
            app,
            name = "/dev/bus/usb/001/077",
            vendorId = CH341_VENDOR_ID,
            productId = CH341_PRODUCT_ID,
            carPlayInterfaces = false,
            permissionGranted = permissionGranted,
        )
        device = attached
        return UsbAttachDeviceIdentity(
            deviceId = attached.deviceId,
            deviceName = attached.deviceName,
            vendorId = attached.vendorId,
            productId = attached.productId,
        )
    }

    private companion object {
        const val CH341_VENDOR_ID = 0x1a86
        const val CH341_PRODUCT_ID = 0x5512
    }
}
