package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ConnectionTraceEvent
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayControllerWatchdogTest {
    @Test
    fun softAndHardWiredTimeoutsRunAtTheirVirtualDeadlines() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val events = CopyOnWriteArrayList<ConnectionTraceEvent>()
        val controller = createController(activity, events)
        val handler = field(controller, "mainHandler").get(controller) as Handler
        val generation = field(controller, "activeWiredStartupAttempt").get(controller) as AtomicInteger

        try {
            beginTrace(controller)
            generation.set(41)
            armWatchdog(controller, 41)

            shadowOf(Looper.getMainLooper()).idleFor(7_999, TimeUnit.MILLISECONDS)
            assertFalse(events.any { it.stage == ConnectionTraceStage.WIRED_STARTUP_SLOW })
            assertEquals(0, atomicValue(controller, "wiredStartupTimeoutAttempt"))

            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            assertTrue(events.any { it.stage == ConnectionTraceStage.WIRED_STARTUP_SLOW })
            assertEquals(0, atomicValue(controller, "wiredStartupTimeoutAttempt"))

            shadowOf(Looper.getMainLooper()).idleFor(6_999, TimeUnit.MILLISECONDS)
            assertEquals(0, atomicValue(controller, "wiredStartupTimeoutAttempt"))
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(41, atomicValue(controller, "wiredStartupTimeoutAttempt"))
        } finally {
            handler.removeCallbacksAndMessages(null)
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    @Test
    fun screenSetupBeforeHardDeadlineCancelsTheScheduledExpiry() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val events = CopyOnWriteArrayList<ConnectionTraceEvent>()
        val controller = createController(activity, events)
        val handler = field(controller, "mainHandler").get(controller) as Handler
        val generation = field(controller, "activeWiredStartupAttempt").get(controller) as AtomicInteger
        val wiredListener = controller.javaClass.getDeclaredMethod(
            "wiredSessionListener",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        try {
            beginTrace(controller)
            generation.set(42)
            armWatchdog(controller, 42)
            shadowOf(Looper.getMainLooper()).idleFor(8_000, TimeUnit.MILLISECONDS)
            assertTrue(events.any { it.stage == ConnectionTraceStage.WIRED_STARTUP_SLOW })

            (wiredListener.invoke(controller, 42) as AirPlaySessionListener)
                .onConnectionTrace(ConnectionTraceStage.SCREEN_STREAM_OPENED)
            assertTrue(
                (field(controller, "wiredStartupComplete").get(controller) as java.util.concurrent.atomic.AtomicBoolean).get(),
            )

            shadowOf(Looper.getMainLooper()).idleFor(7_000, TimeUnit.MILLISECONDS)

            assertEquals(0, atomicValue(controller, "wiredStartupTimeoutAttempt"))
            assertFalse(events.any { it.stage == ConnectionTraceStage.WIRED_STARTUP_SLOW && it.elapsedMs >= 15_000 })
        } finally {
            handler.removeCallbacksAndMessages(null)
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun createController(
        activity: CarPlayHostActivity,
        events: MutableList<ConnectionTraceEvent>,
    ): CarPlayController = CarPlayController(
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
        airPlayConfig = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
        reportStatus = {},
        connectionTraceEnabled = true,
        onConnectionTrace = events::add,
    )

    private fun beginTrace(controller: CarPlayController) {
        controller.javaClass.getDeclaredMethod("beginConnectionTraceAttempt")
            .apply { isAccessible = true }
            .invoke(controller)
    }

    private fun armWatchdog(controller: CarPlayController, attempt: Int) {
        controller.javaClass.getDeclaredMethod("armWiredStartupWatchdog", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(controller, attempt)
    }

    private fun atomicValue(controller: CarPlayController, name: String): Int =
        (field(controller, name).get(controller) as AtomicInteger).get()

    private fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }
}
