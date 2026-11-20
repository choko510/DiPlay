// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.youtube.HostLayoutMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the real USB auto-launch dispatcher against a fake attached iPhone and asserts which
 * Activity the process actually asks Android to bring to the front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IphoneUsbAutoLaunchFocusTest {
    private lateinit var app: Application
    private lateinit var context: Context
    private var device: UsbDevice? = null
    private var previousAutoLaunch = false

    @Before
    fun enableAutoLaunch() {
        app = RuntimeEnvironment.getApplication()
        context = app
        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resetForTesting()
        CarPlayUsbAttachDispatcher.clearForTesting()
        CarPlayBackgroundSession.clear()
        previousAutoLaunch = AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context)
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(context, true))
    }

    @After
    fun clearState() {
        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resetForTesting()
        CarPlayUsbAttachDispatcher.clearForTesting()
        CarPlayBackgroundSession.clear()
        device?.let { TestIphoneUsbDevice.detach(context, it) }
        device = null
        assertTrue(IphoneUsbAutoLaunchSettings.setEnabled(context, previousAutoLaunch))
    }

    @Test
    fun wiredSessionIsNotBroughtToFrontByAnAutomaticForegroundRequestFromSettings() {
        val attached = attachIphone()
        storeWiredSession()

        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)
        val accepted = IphoneUsbAutoLaunchDispatcher.dispatch(
            context,
            attached,
            IphoneUsbAutoLaunchSource.FOREGROUND,
        )

        assertTrue("the existing session is still adopted", accepted)
        assertNull("no Activity may be brought forward", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun explicitSettingsRequestStillBringsTheWiredSessionToTheFront() {
        val attached = attachIphone()
        storeWiredSession()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)

        val accepted = IphoneUsbAutoLaunchDispatcher.dispatch(
            context,
            attached,
            IphoneUsbAutoLaunchSource.SETTINGS,
        )

        assertTrue(accepted)
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the explicit request opens CarPlay", started)
        assertEquals(CarPlayHostActivity::class.java.name, started?.component?.className)
        assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(started!!))
        assertTrue(
            "the existing host Activity is reused instead of recreated",
            started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0,
        )
    }

    @Test
    fun ordinaryForegroundRequestFromTheDiPlayHomeScreenStillOpensCarPlay() {
        val attached = attachIphone()
        storeWiredSession()
        DiPlayUiFocus.resumed(this, DiPlaySurface.HOME)

        assertTrue(
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.FOREGROUND),
        )

        val started = shadowOf(app).nextStartedActivity
        assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(started!!))
    }

    @Test
    fun attachWhileSettingsAreOpenIsHeldUntilTheUserLeavesTheSettingsScreen() {
        val attached = attachIphone()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)

        val accepted = IphoneUsbAutoLaunchDispatcher.dispatch(
            context,
            attached,
            IphoneUsbAutoLaunchSource.ATTACH,
        )

        assertFalse("the request is deferred, not accepted", accepted)
        assertNull("the settings screen keeps the display", shadowOf(app).nextStartedActivity)

        IphoneUsbAutoLaunchDispatcher.resumeDeferred(context)
        assertNull("replaying while settings is visible is still held", shadowOf(app).nextStartedActivity)

        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(context)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the held USB request is replayed once the user leaves settings", started)
        assertEquals(CarPlayHostActivity::class.java.name, started?.component?.className)
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    @Test
    fun attachWhileCarPlayIsForegroundStartsTheHostImmediately() {
        val attached = attachIphone()
        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)

        assertTrue(
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.ATTACH),
        )

        val started = shadowOf(app).nextStartedActivity
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
        assertNull("a duplicate attach must not stack another host launch", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun repeatedAttachEventsDoNotStackHostLaunches() {
        val attached = attachIphone()
        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)

        repeat(5) {
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.ATTACH)
        }

        assertNotNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun announcedSettingsNavigationProtectsTheTransitionGap() {
        val attached = attachIphone()
        storeWiredSession()

        // The 3-finger gesture announces settings, then Android pauses the host before the
        // settings Activity resumes.
        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        DiPlayUiFocus.navigatingTo(this, DiPlaySurface.SETTINGS)
        DiPlayUiFocus.paused(this)

        assertTrue(
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.FOREGROUND),
        )
        assertNull("the announced settings screen is not replaced", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun heldRequestForADeviceThatWasUnpluggedIsNotReplayedLater() {
        val attached = attachIphone()
        DiPlayUiFocus.resumed(this, DiPlaySurface.SETTINGS)
        assertFalse(
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.ATTACH),
        )

        TestIphoneUsbDevice.detach(context, device!!)
        device = null
        DiPlayUiFocus.reset()
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(context)

        assertNull("a stale device must not open CarPlay", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun wirelessSessionKeepsRunningWhileTheUsbRequestWaitsForSettingsToClose() {
        val attached = attachIphone()
        storeSession(CarPlayTransport.WIRELESS)
        DiPlayUiFocus.resumed(this, DiPlaySurface.DSP_SETTINGS)

        assertFalse(
            IphoneUsbAutoLaunchDispatcher.dispatch(context, attached, IphoneUsbAutoLaunchSource.ATTACH),
        )
        assertNull(shadowOf(app).nextStartedActivity)
        assertNotNull("the wireless controller is still the active session", CarPlayBackgroundSession.snapshot())

        DiPlayUiFocus.resumed(this, DiPlaySurface.CARPLAY_HOST)
        IphoneUsbAutoLaunchDispatcher.resumeDeferred(context)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
            CarPlayHostActions.consumeInternalAction(started!!),
        )
    }

    private fun attachIphone(): UsbAttachDeviceIdentity {
        val attached = TestIphoneUsbDevice.attach(context)
        device = attached
        return attached.toUsbAttachDeviceIdentity()
    }

    private fun storeWiredSession() = storeSession(CarPlayTransport.WIRED)

    private fun storeSession(transport: CarPlayTransport) {
        CarPlayBackgroundSession.store(
            TestCarPlayPlaceholders.controller(transport),
            AndroidMediaSink(),
            800,
            480,
            owner = this,
            stop = { done -> done() },
        )
    }

    @Test
    fun aLaterRequestUpdatesTheHeldLaunchStateInsteadOfBeingDropped() {
        val queue = IphoneUsbAutoLaunchRequestQueue()
        val identity = UsbAttachDeviceIdentity(1, "/dev/bus/usb/001/002", 0x05ac, 0x12a8)
        val state = CarPlayHostLaunchState(HostLayoutMode.CARPLAY_YOUTUBE_SPLIT, 0.42f, null)

        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH))
        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH, state))

        assertEquals(
            "the held request adopts the split layout the newer one knows",
            state,
            queue.peekDeferred()?.hostLaunchState,
        )
    }

    @Test
    fun aRequestWithoutLaunchStateKeepsTheKnownLayout() {
        val queue = IphoneUsbAutoLaunchRequestQueue()
        val identity = UsbAttachDeviceIdentity(1, "/dev/bus/usb/001/002", 0x05ac, 0x12a8)
        val state = CarPlayHostLaunchState(HostLayoutMode.CARPLAY_DSP_SPLIT, 0.55f, null)

        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH, state))
        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH))
        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH))
        queue.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH, state))

        assertEquals("a repeated attach never queues twice", state, queue.peekDeferred()?.hostLaunchState)
        assertNull(
            "a different device still replaces the request",
            IphoneUsbAutoLaunchRequestQueue().let { other ->
                other.defer(DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH, state))
                other.defer(
                    DeferredIphoneUsbAutoLaunch(
                        UsbAttachDeviceIdentity(2, "/dev/bus/usb/001/003", 0x05ac, 0x12a8),
                        IphoneUsbAutoLaunchSource.ATTACH,
                    ),
                )
                other.peekDeferred()?.hostLaunchState
            },
        )
    }
}
