// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Android starts the transparent USB attach receivers by itself, so the DiPlay settings Activity is
 * paused first and any code in the receiver runs after that. The screen the user chose must survive
 * that interruption: the attach is held, and replayed only once CarPlay or a non-settings screen is
 * back in front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbReceiverFocusTest {
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
    fun iphoneAttachReceiverDoesNotTakeThePausedSettingsScreen() {
        storeWiredSession()
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        device = TestIphoneUsbDevice.attach(app)
        settings.pause()
        assertEquals(
            "pausing clears the focus; the receiver has to restore the covered screen",
            DiPlaySurface.NONE,
            DiPlayUiFocus.current(),
        )

        val receiver = buildIphoneReceiver()

        assertTrue(receiver.isFinishing)
        assertNull("a transparent receiver must not front CarPlay", shadowOf(receiver).nextStartedActivity)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    @Test
    fun ch341AttachReceiverDoesNotTakeThePausedSettingsScreen() {
        storeWiredSession()
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        val attached = TestIphoneUsbDevice.attach(
            app,
            name = "/dev/bus/usb/001/077",
            vendorId = CH341_VENDOR_ID,
            productId = CH341_PRODUCT_ID,
            carPlayInterfaces = false,
        )
        device = attached
        settings.pause()

        val receiver = Robolectric.buildActivity(
            CarPlayUsbAttachActivity::class.java,
            Intent(app, CarPlayUsbAttachActivity::class.java)
                .setAction(CarPlayHostActions.USB_DEVICE_ATTACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, attached),
        ).setup().get()

        assertTrue(receiver.isFinishing)
        assertNull(shadowOf(receiver).nextStartedActivity)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    @Test
    fun theAttachHeldBehindTheReceiverIsReplayedWhenCarPlayComesBack() {
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        device = TestIphoneUsbDevice.attach(app)
        settings.pause()
        val receiver = buildIphoneReceiver()
        assertNull(shadowOf(receiver).nextStartedActivity)

        // The user closes settings and CarPlay owns the screen again.
        DiPlayUiFocus.resumed(settings.get(), DiPlaySurface.CARPLAY_HOST)
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the held attach is delivered once CarPlay is back", started)
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(app)
        assertNull("and only once", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun resumingTheSettingsScreenKeepsTheHeldAttach() {
        storeWiredSession()
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java, settingsIntent()).setup()
        device = TestIphoneUsbDevice.attach(app)
        settings.pause()
        buildIphoneReceiver()

        // The receiver finishes and the settings screen resumes untouched.
        settings.resume()

        assertNull(shadowOf(settings.get()).nextStartedActivity)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
    }

    private fun buildIphoneReceiver(): IphoneUsbAutoAttachActivity =
        Robolectric.buildActivity(
            IphoneUsbAutoAttachActivity::class.java,
            Intent(app, IphoneUsbAutoAttachActivity::class.java)
                .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, device),
        ).setup().get()

    private fun settingsIntent(): Intent = Intent(app, DiPlayActivity::class.java)
        .putExtra("page", "settings")
        .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)

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

    private companion object {
        const val CH341_VENDOR_ID = 0x1a86
        const val CH341_PRODUCT_ID = 0x5512
    }
}
