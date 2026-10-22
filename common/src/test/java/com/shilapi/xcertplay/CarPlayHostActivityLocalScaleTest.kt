package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.youtube.DisplaySize
import com.shilapi.xcertplay.youtube.HostLayoutMode
import com.shilapi.xcertplay.youtube.HostLayoutState
import java.lang.reflect.Field
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostActivityLocalScaleTest {
    @Test
    fun draggingOutOfTheCarPlayContentCancelsTheTouchSequenceUntilRelease() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val view = View(activity).apply { layout(0, 0, 100, 60) }
        val onHostTouch = CarPlayHostActivity::class.java.getDeclaredMethod(
            "onHostTouch",
            View::class.java,
            MotionEvent::class.java,
        ).apply { isAccessible = true }
        val downTime = android.os.SystemClock.uptimeMillis()

        fun event(action: Int, x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)

        val down = event(MotionEvent.ACTION_DOWN, 50f, 30f)
        val moveOutside = event(MotionEvent.ACTION_MOVE, 100f, 30f)
        val moveInside = event(MotionEvent.ACTION_MOVE, 50f, 30f)
        val up = event(MotionEvent.ACTION_UP, 50f, 30f)
        try {
            assertTrue(onHostTouch.invoke(activity, view, down) as Boolean)
            assertTrue(onHostTouch.invoke(activity, view, moveOutside) as Boolean)
            assertTrue(field(activity, "carPlayTouchSequenceCancelled").getBoolean(activity))
            assertTrue(onHostTouch.invoke(activity, view, moveInside) as Boolean)
            assertTrue(field(activity, "carPlayTouchSequenceCancelled").getBoolean(activity))
            assertTrue(onHostTouch.invoke(activity, view, up) as Boolean)
            assertFalse(field(activity, "carPlayTouchSequenceCancelled").getBoolean(activity))
        } finally {
            down.recycle()
            moveOutside.recycle()
            moveInside.recycle()
            up.recycle()
        }
    }

    @Test
    fun fullAndSplitViewportCyclesKeepTheRemoteControllerSessionAndSurface() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as android.os.Handler
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
        )
        val controller = CarPlayController(
            context = activity,
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(
                    name = "test",
                    modelIdentifier = "test",
                    manufacturer = "test",
                    serialNumber = "test",
                    firmwareVersion = "1.0",
                    hardwareVersion = "1.0",
                    carPlayUsbInterfaceNumber = 0,
                ),
            ),
            airPlayConfig = config,
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
            reportStatus = {},
        )
        val session = testSession()
        val remote = DisplaySize(1920, 1080)
        val splitViewport = DisplaySize(1056, 1080)
        val contentRoot = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }
            .invoke(activity) as android.view.View
        activity.setContentView(contentRoot)
        val content = field(activity, "carPlayContent").get(activity) as FrameLayout
        val carPlayPane = field(activity, "carPlayPane").get(activity) as FrameLayout
        val youtubePane = field(activity, "youtubePane").get(activity) as FrameLayout
        val dspPane = field(activity, "dspPane").get(activity) as FrameLayout
        val updateLayout = CarPlayHostActivity::class.java.getDeclaredMethod("updateHostLayoutInternal")
            .apply { isAccessible = true }
        val resize = CarPlayHostActivity::class.java.getDeclaredMethod(
            "applyDisplaySizeNow",
            DisplaySize::class.java,
        ).apply { isAccessible = true }
        val scheduleResize = CarPlayHostActivity::class.java.getDeclaredMethod(
            "scheduleDisplaySize",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            HostDisplaySizeCause::class.java,
        ).apply { isAccessible = true }

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)
            CarPlayBackgroundSession.active = true
            setField(activity, "controller", controller)
            setField(activity, "activeAirPlaySession", session)
            setField(activity, "activeDisplaySize", remote)
            setField(activity, "localViewportSize", remote)
            setField(activity, "currentSurface", surface)
            setField(activity, "currentSurfaceTexture", texture)
            setField(controller, "activeSession", session)

            assertEquals(2, content.childCount)
            val videoLayer = requireNotNull(content.getChildAt(0))
            val touchLayer = requireNotNull(content.getChildAt(1))
            assertSame(content, videoLayer.parent)
            assertSame(content, touchLayer.parent)
            assertSame(carPlayPane, content.parent)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, videoLayer.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, videoLayer.layoutParams.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, touchLayer.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, touchLayer.layoutParams.height)

            assertTrue(surface.isValid)
            assertTrue(CarPlayBackgroundSession.isOwner(activity))
            setField(activity, "handshakeResetInProgress", true)
            scheduleResize.invoke(
                activity,
                splitViewport.width,
                splitViewport.height,
                HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED,
            )
            assertEquals(remote, field(activity, "activeDisplaySize").get(activity))
            assertNull(field(activity, "pendingDisplaySize").get(activity))
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
            setField(activity, "handshakeResetInProgress", false)
            repeat(20) {
                setField(activity, "hostLayoutState", HostLayoutState().enterDspSplit())
                updateLayout.invoke(activity)
                assertEquals(View.GONE, youtubePane.visibility)
                assertEquals(View.VISIBLE, dspPane.visibility)
                assertEquals(0.55f, (carPlayPane.layoutParams as android.widget.LinearLayout.LayoutParams).weight, 0.001f)
                assertEquals(0.45f, (dspPane.layoutParams as android.widget.LinearLayout.LayoutParams).weight, 0.001f)
                resize.invoke(activity, splitViewport)
                assertEquals(remote, field(activity, "activeDisplaySize").get(activity))
                assertEquals(1056, (content.layoutParams as FrameLayout.LayoutParams).width)
                assertEquals(594, (content.layoutParams as FrameLayout.LayoutParams).height)
                assertSame(controller, field(activity, "controller").get(activity))
                assertSame(session, field(activity, "activeAirPlaySession").get(activity))
                assertSame(session, controller.currentAirPlaySession())
                assertSame(surface, field(activity, "currentSurface").get(activity))
                assertTrue(CarPlayBackgroundSession.isOwner(activity))

                setField(activity, "hostLayoutState", HostLayoutState())
                updateLayout.invoke(activity)
                assertEquals(View.GONE, dspPane.visibility)
                assertEquals(View.GONE, youtubePane.visibility)
                assertEquals(1f, (carPlayPane.layoutParams as android.widget.LinearLayout.LayoutParams).weight, 0.001f)
                resize.invoke(activity, remote)
                assertEquals(remote, field(activity, "activeDisplaySize").get(activity))
                assertEquals(1920, (content.layoutParams as FrameLayout.LayoutParams).width)
                assertEquals(1080, (content.layoutParams as FrameLayout.LayoutParams).height)
                assertSame(controller, field(activity, "controller").get(activity))
                assertSame(session, field(activity, "activeAirPlaySession").get(activity))
                assertSame(session, controller.currentAirPlaySession())
                assertSame(surface, field(activity, "currentSurface").get(activity))
                assertNull(field(activity, "restartHandoffToken").get(activity))
            }
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
            session.close()
            assertNull(controller.currentAirPlaySession())
        } finally {
            handler.removeCallbacksAndMessages(null)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
            controller.close()
            controller.awaitClosed(3_000)
            session.close()
            surface.release()
            texture.release()
        }
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    private fun field(instance: Any, name: String): Field =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(instance: Any, name: String, value: Any?) {
        field(instance, name).set(instance, value)
    }
}
