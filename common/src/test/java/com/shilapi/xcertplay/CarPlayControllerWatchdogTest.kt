package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ConnectionTraceEvent
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun controllerResolvesUsbManagerFromApplicationContext() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val applicationContext = activity.applicationContext
        val context = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = applicationContext

            override fun getSystemService(name: String): Any? =
                if (name == Context.USB_SERVICE) null else super.getSystemService(name)
        }
        val controller = createController(context, CopyOnWriteArrayList())

        try {
            val expected = applicationContext.getSystemService(UsbManager::class.java)
            val actual = field(controller, "usbManager").get(controller)
            assertSame(expected, actual)
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

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

    @Test
    fun mainAndAltSetupThenPreRecordCloseRequestWiredRecovery() {
        for ((index, streamType) in listOf(110, 111).withIndex()) {
            val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            val events = CopyOnWriteArrayList<ConnectionTraceEvent>()
            val controller = createController(activity, events)
            val handler = field(controller, "mainHandler").get(controller) as Handler
            val attempt = 43 + index
            val generation = field(controller, "activeWiredStartupAttempt").get(controller) as AtomicInteger
            val listenerMethod = controller.javaClass.getDeclaredMethod(
                "wiredSessionListener",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val sessionPair = connectedSocketPair()
            val sessionListener = listenerMethod.invoke(controller, attempt) as AirPlaySessionListener
            val session = AirPlaySession(
                socket = sessionPair.first,
                config = airPlayConfig(),
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = null,
                listener = sessionListener,
                media = object : AirPlayMediaHandler {
                    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int =
                        13_000 + type
                },
            )
            val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }

            try {
                beginTrace(controller)
                generation.set(attempt)
                armWatchdog(controller, attempt)
                val setupBody = BplistCodec.encode(
                    linkedMapOf("streams" to listOf(linkedMapOf("type" to streamType.toLong()))),
                )
                handle.invoke(
                    session,
                    RtspMessage.Request(
                        method = "SETUP",
                        path = "/setup",
                        protocol = "RTSP/1.0",
                        headers = emptyMap(),
                        body = setupBody,
                    ),
                )

                assertTrue(
                    (field(controller, "wiredStartupComplete").get(controller) as java.util.concurrent.atomic.AtomicBoolean)
                        .get(),
                )
                shadowOf(Looper.getMainLooper()).idleFor(15_000, TimeUnit.MILLISECONDS)
                assertEquals(0, atomicValue(controller, "wiredStartupTimeoutAttempt"))

                session.close()

                assertEquals(attempt, atomicValue(controller, "wiredTransportFailureAttempt"))
                assertTrue(
                    (field(controller, "wiredRecoveryPending").get(controller) as java.util.concurrent.atomic.AtomicBoolean)
                        .get(),
                )
            } finally {
                session.close()
                runCatching { sessionPair.second.close() }
                handler.removeCallbacksAndMessages(null)
                controller.close()
                assertTrue(controller.awaitClosed(2_000))
            }
        }
    }

    @Test
    fun postRecordCloseEndsTheActiveSessionWithoutStartupRecovery() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = createController(activity, CopyOnWriteArrayList())
        val attempt = 45
        val generation = field(controller, "activeWiredStartupAttempt").get(controller) as AtomicInteger
        val listener = controller.javaClass.getDeclaredMethod(
            "wiredSessionListener",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(controller, attempt) as AirPlaySessionListener
        val sessionPair = connectedSocketPair()
        val session = AirPlaySession(
            socket = sessionPair.first,
            config = airPlayConfig(),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = listener,
            media = object : AirPlayMediaHandler {},
        )
        val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }

        try {
            generation.set(attempt)
            handle.invoke(
                session,
                RtspMessage.Request(
                    method = "RECORD",
                    path = "/stream",
                    protocol = "RTSP/1.0",
                    headers = emptyMap(),
                    body = ByteArray(0),
                ),
            )
            assertTrue(field(controller, "activeSession").get(controller) === session)

            session.close()

            assertEquals(0, atomicValue(controller, "wiredTransportFailureAttempt"))
            assertFalse(
                (field(controller, "wiredRecoveryPending").get(controller) as java.util.concurrent.atomic.AtomicBoolean)
                    .get(),
            )
            assertNull(field(controller, "activeSession").get(controller))
        } finally {
            session.close()
            runCatching { sessionPair.second.close() }
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun createController(
        context: Context,
        events: MutableList<ConnectionTraceEvent>,
    ): CarPlayController = CarPlayController(
        context = context,
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
        airPlayConfig = airPlayConfig(),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
        reportStatus = {},
        connectionTraceEnabled = true,
        onConnectionTrace = events::add,
    )

    private fun airPlayConfig() = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

    private fun connectedSocketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0)
        return try {
            val client = Socket("127.0.0.1", server.localPort)
            server.accept() to client
        } finally {
            server.close()
        }
    }

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
