package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
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
        val content = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val resize = CarPlayHostActivity::class.java.getDeclaredMethod(
            "applyDisplaySizeNow",
            DisplaySize::class.java,
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
            setField(activity, "carPlayContent", content)
            setField(controller, "activeSession", session)

            assertTrue(surface.isValid)
            assertTrue(CarPlayBackgroundSession.isOwner(activity))
            repeat(20) {
                resize.invoke(activity, splitViewport)
                assertEquals(remote, field(activity, "activeDisplaySize").get(activity))
                assertEquals(1056, (content.layoutParams as FrameLayout.LayoutParams).width)
                assertEquals(594, (content.layoutParams as FrameLayout.LayoutParams).height)
                assertSame(controller, field(activity, "controller").get(activity))
                assertSame(session, field(activity, "activeAirPlaySession").get(activity))
                assertSame(session, controller.currentAirPlaySession())
                assertSame(surface, field(activity, "currentSurface").get(activity))
                assertTrue(CarPlayBackgroundSession.isOwner(activity))

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
