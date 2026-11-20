// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.youtube.HostLayoutMode
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
 * Verifies that a CarPlay OEM (`requestUI`) request opens DiPlay Menu only while the CarPlay host
 * really owns the screen, and never twice for the same request burst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostUiRequestTest {
    private lateinit var app: Application
    private var bootstrapReadyField: Field? = null
    private var previousBootstrapReady = false
    private var device: android.hardware.usb.UsbDevice? = null
    private var previousAutoLaunch = false

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        DiPlayUiFocus.reset()
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
        CarPlayBackgroundSession.clear()
        device?.let { TestIphoneUsbDevice.detach(app, it) }
        device = null
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(app, previousAutoLaunch))
        bootstrapReadyField?.setBoolean(DiPlayBootstrap, previousBootstrapReady)
    }

    @Test
    fun oemRequestOpensTheMenuOnceWhileCarPlayIsVisible() {
        val host = launchHost()
        val session = presentSession(host)

        requestHostUi(host, session)

        val opened = menuIntents(host)
        assertEquals(1, opened.size)
        assertTrue(
            "the existing menu Activity is reused",
            opened.single().flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0,
        )

        requestHostUi(host, session)
        assertEquals("a repeated request must not start another menu", 0, menuIntents(host).size)
    }

    @Test
    fun oemRequestIsIgnoredWhileSettingsOwnTheScreen() {
        val host = launchHost()
        val session = presentSession(host)
        DiPlayUiFocus.resumed(Any(), DiPlaySurface.SETTINGS)

        repeat(3) { requestHostUi(host, session) }

        assertEquals("settings keep the screen", 0, menuIntents(host).size)
    }

    @Test
    fun oemRequestIsIgnoredWhileTheHostIsPaused() {
        val host = launchHost()
        val session = presentSession(host)
        invokeLifecycle(host, "onPause")

        requestHostUi(host, session)

        assertEquals(0, menuIntents(host).size)
    }

    @Test
    fun oemRequestFromAnOldSessionIsIgnored() {
        val host = launchHost()
        val current = presentSession(host)
        val stale = TestCarPlayPlaceholders.session()

        requestHostUi(host, stale)

        assertEquals(0, menuIntents(host).size)
        requestHostUi(host, current)
        assertEquals(1, menuIntents(host).size)
    }

    @Test
    fun oemRequestFromAClosedSessionIsIgnored() {
        val host = launchHost()
        val session = presentSession(host, closed = true)

        requestHostUi(host, session)

        assertEquals(0, menuIntents(host).size)
    }

    @Test
    fun oemRequestDuringTheMenuTransitionIsIgnoredEvenAfterTheDuplicateWindow() {
        val host = launchHost()
        val session = presentSession(host)
        requestHostUi(host, session)
        assertEquals(1, menuIntents(host).size)

        // The menu has not resumed yet (the host has not paused), so the time window has expired.
        val gate = TestCarPlayPlaceholders.field(host, "hostUiRequestGate").get(host) as HostUiRequestGate
        gate.reset()
        requestHostUi(host, session)

        assertEquals(
            "the announced menu screen blocks a second launch regardless of timing",
            0,
            menuIntents(host).size,
        )
    }

    @Test
    fun returningToCarPlayReArmsTheOemRequest() {
        val host = launchHost()
        val session = presentSession(host)
        requestHostUi(host, session)
        assertEquals(1, menuIntents(host).size)

        // The menu took the screen; the user returns to CarPlay and taps the OEM icon again.
        invokeLifecycle(host, "onPause")
        invokeLifecycle(host, "onResume")
        requestHostUi(host, session)

        assertEquals(1, menuIntents(host).size)
    }

    @Test
    fun aHandoverRestartDoesNotTakeTheSettingsScreen() {
        val host = launchHost()
        val attached = TestIphoneUsbDevice.attach(app)
        device = attached
        val identity = attached.toUsbAttachDeviceIdentity()
        DiPlayUiFocus.resumed(Any(), DiPlaySurface.SETTINGS)

        host.restartHostAfterShutdown(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            identity,
            CarPlayHostLaunchState(
                HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
                0.42f,
                HostLayoutMode.CARPLAY_DSP_SPLIT,
            ),
        )

        assertEquals("settings keep the screen", 0, startedHostIntents(host).size)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())

        // The handover already closed the old connection; only the display was deferred.
        CarPlayBackgroundSession.clear()
        // The request is kept and replayed once CarPlay owns the display again.
        DiPlayUiFocus.resumed(host, DiPlaySurface.CARPLAY_HOST)
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(host)

        val replayed = startedHostIntents(host)
        assertEquals(1, replayed.size)
        val request = CarPlayHostActions.consumeInternalRequest(replayed.single())
        assertEquals(CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED, request?.action)
        assertEquals(
            "the split layout of the interrupted host must survive the hold",
            CarPlayHostLaunchState(HostLayoutMode.CARPLAY_YOUTUBE_SPLIT, 0.42f, HostLayoutMode.CARPLAY_DSP_SPLIT),
            request?.hostLaunchState,
        )
    }

    @Test
    fun aHandoverRestartStillRunsWhileCarPlayOwnsTheDisplay() {
        val host = launchHost()
        val identity = UsbAttachDeviceIdentity(1, "/dev/bus/usb/001/002", 0x05ac, 0x12a8)

        host.restartHostAfterShutdown(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            identity,
            CarPlayHostLaunchState(HostLayoutMode.CARPLAY_FULL, 0.5f, null),
        )

        val started = startedHostIntents(host)
        assertEquals(1, started.size)
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started.single()),
        )
    }

    @Test
    fun aCh341HandoverRestartIsHeldAndResumedLater() {
        val host = launchHost()
        val attached = TestIphoneUsbDevice.attach(
            app,
            name = "/dev/bus/usb/001/077",
            vendorId = 0x1a86,
            productId = 0x5512,
            carPlayInterfaces = false,
        )
        device = attached
        DiPlayUiFocus.resumed(Any(), DiPlaySurface.SETTINGS)

        // The CH341 resume has no iPhone identity, so it must not fall back to a notification only.
        host.restartHostAfterShutdown(CarPlayHostActions.OPEN_CARPLAY)

        assertEquals("settings keep the screen", 0, startedHostIntents(host).size)

        DiPlayUiFocus.resumed(host, DiPlaySurface.CARPLAY_HOST)
        CarPlayUsbAttachDispatcher.resume(host)

        val replayed = startedHostIntents(host)
        assertEquals(1, replayed.size)
        assertEquals(
            CarPlayHostActions.USB_DEVICE_ATTACHED,
            CarPlayHostActions.consumeInternalAction(replayed.single()),
        )
    }

    private fun startedHostIntents(host: CarPlayHostActivity): List<Intent> {
        val started = mutableListOf<Intent>()
        while (true) {
            val intent = shadowOf(host).nextStartedActivity ?: break
            if (intent.component?.className == CarPlayHostActivity::class.java.name) started += intent
        }
        return started
    }

    private fun launchHost(): CarPlayHostActivity {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get().also { host ->
            CarPlayBackgroundSession.store(
                TestCarPlayPlaceholders.controller(CarPlayTransport.WIRED),
                AndroidMediaSink(),
                800,
                480,
                owner = host,
                stop = { done -> done() },
            )
        }
    }

    private fun presentSession(host: CarPlayHostActivity, closed: Boolean = false): AirPlaySession =
        TestCarPlayPlaceholders.session(closed).also { session ->
            TestCarPlayPlaceholders.field(host, "activeAirPlaySession").set(host, session)
        }

    private fun menuIntents(host: CarPlayHostActivity): List<Intent> {
        val opened = mutableListOf<Intent>()
        while (true) {
            val started = shadowOf(host).nextStartedActivity ?: break
            if (started.component?.className == DiPlayMenuActivity::class.java.name) opened += started
        }
        return opened
    }

    private fun requestHostUi(host: CarPlayHostActivity, session: AirPlaySession) {
        val generation = TestCarPlayPlaceholders.field(host, "restartGeneration").get(host) as Int
        val epoch = TestCarPlayPlaceholders.field(host, "connectionEpoch").get(host) as Long
        val listener = host.javaClass.getDeclaredMethod(
            "createSessionListener",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(host, generation, epoch) as AirPlaySessionListener
        listener.onHostUiRequested(session)
    }

    private fun invokeLifecycle(host: CarPlayHostActivity, name: String) {
        android.app.Activity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(host)
    }
}
