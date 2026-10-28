package com.shilapi.xcertplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.youtube.DisplaySize
import java.lang.reflect.Field
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostActivityReconnectTest {
    @Test
    fun verifiedUsbAttachReevaluatesStartForAnExistingWiredHost() {
        CarPlayBackgroundSession.clear()
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        val startRequests = mutableListOf<DisplaySize>()
        try {
            field(activity, "wirelessEnabled").setBoolean(activity, false)
            field(activity, "vpnReady").setBoolean(activity, true)
            field(activity, "microphonePermissionResolved").setBoolean(activity, true)
            field(activity, "locationReportingEnabled").setBoolean(activity, false)
            field(activity, "menuOpen").setBoolean(activity, false)
            field(activity, "activeDisplaySize").set(activity, DisplaySize(800, 480))
            activity.startCarPlayForTest = startRequests::add
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
            assertFalse(
                (field(activity, "shuttingDown").get(activity) as java.util.concurrent.atomic.AtomicBoolean).get(),
            )
            assertFalse(CarPlayBackgroundSession.isRestartSuppressed())
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
            assertFalse(CarPlayBackgroundSession.hasSession())
            assertNull(field(activity, "controller").get(activity))

            val attach = CarPlayHostActions.createInternalIntent(
                activity,
                CarPlayHostActions.USB_DEVICE_ATTACHED,
            )
            dispatchOnNewIntent(activity, attach)

            assertEquals(listOf(DisplaySize(800, 480)), startRequests)
            assertNull(CarPlayHostActions.consumeInternalAction(attach))
        } finally {
            activity.startCarPlayForTest = null
            CarPlayBackgroundSession.clear()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun twentyVerifiedWiredAttachesKeepTheExistingControllerAndDoNotStartAnother() {
        CarPlayBackgroundSession.clear()
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        val startRequests = mutableListOf<DisplaySize>()
        val existingController = allocateControllerPlaceholder()
        try {
            field(activity, "wirelessEnabled").setBoolean(activity, false)
            field(activity, "vpnReady").setBoolean(activity, true)
            field(activity, "microphonePermissionResolved").setBoolean(activity, true)
            field(activity, "locationReportingEnabled").setBoolean(activity, false)
            field(activity, "menuOpen").setBoolean(activity, false)
            field(activity, "activeDisplaySize").set(activity, DisplaySize(800, 480))
            field(activity, "controller").set(activity, existingController)
            activity.startCarPlayForTest = { size -> startRequests.add(size) }

            repeat(20) {
                dispatchOnNewIntent(
                    activity,
                    CarPlayHostActions.createInternalIntent(activity, CarPlayHostActions.USB_DEVICE_ATTACHED),
                )
            }

            assertTrue(startRequests.isEmpty())
            assertTrue(field(activity, "controller").get(activity) === existingController)
        } finally {
            activity.startCarPlayForTest = null
            field(activity, "controller").set(activity, null)
            CarPlayBackgroundSession.clear()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun verifiedWiredAttachAdoptsTheExistingBackgroundOwner() {
        CarPlayBackgroundSession.clear()
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        val startRequests = mutableListOf<DisplaySize>()
        val existingController = allocateControllerPlaceholder().apply {
            field(this, "lifecycleLock").set(this, Any())
            field(this, "mainHandler").set(this, Handler(Looper.getMainLooper()))
        }
        val existingSink = com.shilapi.xcertplay.media.AndroidMediaSink()
        val previousOwner = Any()

        try {
            field(activity, "wirelessEnabled").setBoolean(activity, false)
            field(activity, "vpnReady").setBoolean(activity, true)
            field(activity, "microphonePermissionResolved").setBoolean(activity, true)
            field(activity, "locationReportingEnabled").setBoolean(activity, false)
            field(activity, "menuOpen").setBoolean(activity, false)
            activity.startCarPlayForTest = { size -> startRequests.add(size) }
            CarPlayBackgroundSession.store(existingController, existingSink, 800, 480, previousOwner) { done -> done() }

            dispatchOnNewIntent(
                activity,
                CarPlayHostActions.createInternalIntent(activity, CarPlayHostActions.USB_DEVICE_ATTACHED),
            )

            assertTrue(startRequests.isEmpty())
            assertTrue(field(activity, "controller").get(activity) === existingController)
            assertTrue(CarPlayBackgroundSession.isOwner(activity))
        } finally {
            activity.startCarPlayForTest = null
            CarPlayBackgroundSession.clear()
            existingSink.close()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun verifiedUsbAttachWaitsForVpnReadinessBeforeStarting() {
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        val startRequests = mutableListOf<DisplaySize>()
        try {
            field(activity, "wirelessEnabled").setBoolean(activity, false)
            field(activity, "vpnReady").setBoolean(activity, false)
            field(activity, "microphonePermissionResolved").setBoolean(activity, true)
            field(activity, "locationReportingEnabled").setBoolean(activity, false)
            field(activity, "menuOpen").setBoolean(activity, false)
            field(activity, "activeDisplaySize").set(activity, DisplaySize(800, 480))
            activity.startCarPlayForTest = startRequests::add

            dispatchOnNewIntent(
                activity,
                CarPlayHostActions.createInternalIntent(activity, CarPlayHostActions.USB_DEVICE_ATTACHED),
            )
            assertTrue(startRequests.isEmpty())

            field(activity, "vpnReady").setBoolean(activity, true)
            activity.javaClass.getDeclaredMethod("maybeStartCarPlay").apply {
                isAccessible = true
                invoke(activity)
            }
            assertEquals(listOf(DisplaySize(800, 480)), startRequests)
        } finally {
            activity.startCarPlayForTest = null
            CarPlayBackgroundSession.clear()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun wiredUsbAttachDuringShutdownQueuesOneResumeAfterTeardown() {
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        clearStartedActivities(activity)
        try {
            field(activity, "wirelessEnabled").setBoolean(activity, false)
            (field(activity, "shuttingDown").get(activity) as java.util.concurrent.atomic.AtomicBoolean).set(true)
            val firstAttach = CarPlayHostActions.createInternalIntent(
                activity,
                CarPlayHostActions.USB_DEVICE_ATTACHED,
            )
            val secondAttach = CarPlayHostActions.createInternalIntent(
                activity,
                CarPlayHostActions.USB_DEVICE_ATTACHED,
            )
            dispatchOnNewIntent(activity, firstAttach)
            dispatchOnNewIntent(activity, secondAttach)

            val pending = field(activity, "shutdownCompletionCallbacks").get(activity) as List<*>
            assertEquals(1, pending.size)
            assertNull(shadowOf(activity.application).nextStartedActivity)

            activity.javaClass.getDeclaredMethod("completeShutdownCallbacks").apply {
                isAccessible = true
                invoke(activity)
            }
            val resumedHost = shadowOf(activity.application).nextStartedActivity
            assertNotNull(resumedHost)
            assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(resumedHost))
            assertNull(shadowOf(activity.application).nextStartedActivity)
        } finally {
            CarPlayBackgroundSession.clear()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown() {
        val bootstrapReady = field(DiPlayBootstrap, "ready")
        val wasBootstrapReady = bootstrapReady.getBoolean(DiPlayBootstrap)
        bootstrapReady.setBoolean(DiPlayBootstrap, true)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()
        val persistedWireless = AirPlayPersistence.loadWirelessEnabled(activity)
        clearStartedActivities(activity)
        try {
            field(activity, "wirelessEnabled").setBoolean(activity, true)
            AirPlayPersistence.saveWirelessEnabled(activity, true)
            (field(activity, "shuttingDown").get(activity) as java.util.concurrent.atomic.AtomicBoolean).set(true)
            repeat(2) {
                dispatchOnNewIntent(
                    activity,
                    CarPlayHostActions.createInternalIntent(activity, CarPlayHostActions.USB_DEVICE_ATTACHED),
                )
            }

            assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
            assertEquals(1, (field(activity, "shutdownCompletionCallbacks").get(activity) as List<*>).size)
            activity.javaClass.getDeclaredMethod("completeShutdownCallbacks").apply {
                isAccessible = true
                invoke(activity)
            }

            assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
            val resumedHost = shadowOf(activity.application).nextStartedActivity
            assertNotNull(resumedHost)
            assertEquals(CarPlayHostActions.OPEN_CARPLAY, CarPlayHostActions.consumeInternalAction(resumedHost))
            assertNull(shadowOf(activity.application).nextStartedActivity)
        } finally {
            AirPlayPersistence.saveWirelessEnabled(activity, persistedWireless)
            CarPlayBackgroundSession.clear()
            bootstrapReady.setBoolean(DiPlayBootstrap, wasBootstrapReady)
        }
    }

    @Test
    fun altOnlyScreenDoesNotHideTheMainConnectionPanel() {
        assertFalse(mainScreenStreamIsActive(setOf(111), 110))
        assertTrue(mainScreenStreamIsActive(setOf(110), 110))
        assertTrue(mainScreenStreamIsActive(setOf(110, 111), 110))
    }

    @Test
    fun replacementLossTicketDoesNotRunAtTheFirstLossDeadline() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)
            activity.javaClass.getDeclaredMethod("reconnectAfterLoss", String::class.java).apply {
                isAccessible = true
                invoke(activity, "first connection loss")
            }
            val firstTicket = field(activity, "scheduledReconnectTicket").get(activity) as? ReconnectAfterLossTicket
            assertNotNull(firstTicket)

            ownerEpochField.setLong(CarPlayBackgroundSession, requireNotNull(firstTicket).ownerEpoch + 1)
            activity.javaClass.getDeclaredMethod("reconnectAfterLoss", String::class.java).apply {
                isAccessible = true
                invoke(activity, "AirPlay iAP tunnel failed")
            }
            val replacementTicket = field(activity, "scheduledReconnectTicket").get(activity)
            assertNotNull(replacementTicket)
            assertFalse(firstTicket == replacementTicket)

            shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)

            assertEquals(replacementTicket, field(activity, "scheduledReconnectTicket").get(activity))
            assertEquals("AirPlay iAP tunnel failed", field(activity, "scheduledReconnectReason").get(activity))
            assertNull(field(activity, "restartHandoffToken").get(activity))
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
        }
    }

    @Test
    fun staleOwnerEpochTicketIsRejectedByTheActivityDelayRunnable() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable

        try {
            assertNull(CarPlayBackgroundSession.snapshot())
            assertFalse(CarPlayBackgroundSession.hasSession())
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)
            field(activity, "activeDisplaySize").set(activity, DisplaySize(1920, 1080))

            activity.javaClass.getDeclaredMethod("reconnectAfterLoss", String::class.java).apply {
                isAccessible = true
                invoke(activity, "test connection loss")
            }
            val ticket = field(activity, "scheduledReconnectTicket").get(activity) as? ReconnectAfterLossTicket
            assertNotNull(ticket)

            ownerEpochField.setLong(CarPlayBackgroundSession, requireNotNull(ticket).ownerEpoch + 1)
            reconnectRunnable.run()

            assertNull(field(activity, "restartHandoffToken").get(activity))
            assertNull(field(activity, "scheduledReconnectTicket").get(activity))
            assertNull(field(activity, "scheduledReconnectReason").get(activity))
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
            assertTrue(CarPlayBackgroundSession.isOwner(activity))
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
        }
    }

    @Test
    fun staleConnectionEpochTransportCallbackCannotScheduleAHostRetry() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable
        val restartGeneration = field(activity, "restartGeneration").getInt(activity)
        val connectionEpochField = field(activity, "connectionEpoch")
        val connectionEpoch = connectionEpochField.getLong(activity)
        val listener = activity.javaClass.getDeclaredMethod(
            "createSessionListener",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(activity, restartGeneration, connectionEpoch) as AirPlaySessionListener

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)

            val callback = Thread { listener.onTransportError("late old connection error") }.apply {
                isDaemon = true
                start()
            }
            callback.join(1_000)
            assertFalse(callback.isAlive)
            connectionEpochField.setLong(activity, connectionEpoch + 1)
            shadowOf(Looper.getMainLooper()).idle()

            assertNull(field(activity, "scheduledReconnectTicket").get(activity))
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            field(activity, "scheduledReconnectTicket").set(activity, null)
            field(activity, "scheduledReconnectReason").set(activity, null)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
        }
    }

    @Test
    fun staleConnectionEpochStatusCallbackCannotScheduleAHostRetry() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable
        val restartGeneration = field(activity, "restartGeneration").getInt(activity)
        val connectionEpochField = field(activity, "connectionEpoch")
        val connectionEpoch = connectionEpochField.getLong(activity)
        val reporter = activity.javaClass.getDeclaredMethod(
            "createStatusReporter",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(activity, restartGeneration, connectionEpoch)

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)
            connectionEpochField.setLong(activity, connectionEpoch + 1)

            invokeStatusReporter(reporter, CarPlayStatus.Failed("late old connection status"))

            assertNull(field(activity, "scheduledReconnectTicket").get(activity))
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            field(activity, "scheduledReconnectTicket").set(activity, null)
            field(activity, "scheduledReconnectReason").set(activity, null)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
        }
    }

    @Test
    fun queuedOldOwnerTransportCallbackCannotChangeTheNewHostStage() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable
        val restartGeneration = field(activity, "restartGeneration").getInt(activity)
        val connectionEpoch = field(activity, "connectionEpoch").getLong(activity)
        val initialStage = field(activity, "latestStage").get(activity)
        val listener = activity.javaClass.getDeclaredMethod(
            "createSessionListener",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(activity, restartGeneration, connectionEpoch) as AirPlaySessionListener

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)

            val callback = Thread { listener.onTransportError("callback queued before owner handoff") }.apply {
                isDaemon = true
                start()
            }
            callback.join(1_000)
            assertFalse(callback.isAlive)
            ownerField.set(CarPlayBackgroundSession, Any())
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 2)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(initialStage, field(activity, "latestStage").get(activity))
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            field(activity, "scheduledReconnectTicket").set(activity, null)
            field(activity, "scheduledReconnectReason").set(activity, null)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
        }
    }

    @Test
    fun staleOldSessionEndCannotClearOrReconnectTheNewActiveSession() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val ownerField = field(CarPlayBackgroundSession, "owner")
        val ownerEpochField = field(CarPlayBackgroundSession, "ownershipEpoch")
        val previousOwner = ownerField.get(CarPlayBackgroundSession)
        val previousOwnerEpoch = ownerEpochField.getLong(CarPlayBackgroundSession)
        val previousActive = CarPlayBackgroundSession.active
        val activeSessionField = field(activity, "activeAirPlaySession")
        val previousActiveSession = activeSessionField.get(activity)
        val handler = field(activity, "mainHandler").get(activity) as Handler
        val reconnectRunnable = field(activity, "restartAfterReconnectDelay").get(activity) as Runnable
        val restartGeneration = field(activity, "restartGeneration").getInt(activity)
        val connectionEpoch = field(activity, "connectionEpoch").getLong(activity)
        val oldSession = createSession()
        val newSession = createSession()
        val listener = activity.javaClass.getDeclaredMethod(
            "createSessionListener",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(activity, restartGeneration, connectionEpoch) as AirPlaySessionListener

        try {
            CarPlayBackgroundSession.clear()
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch + 1)
            ownerField.set(CarPlayBackgroundSession, activity)
            CarPlayBackgroundSession.active = true
            activeSessionField.set(activity, newSession)

            listener.onSessionEnded(oldSession)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activeSessionField.get(activity) === newSession)
            assertFalse(newSession.isClosed)
            assertTrue(CarPlayBackgroundSession.active)
            assertFalse(CarPlayBackgroundSession.hasPendingRestart())
            assertNull(field(activity, "scheduledReconnectTicket").get(activity))
        } finally {
            handler.removeCallbacks(reconnectRunnable)
            CarPlayBackgroundSession.clear()
            ownerField.set(CarPlayBackgroundSession, previousOwner)
            ownerEpochField.setLong(CarPlayBackgroundSession, previousOwnerEpoch)
            CarPlayBackgroundSession.active = previousActive
            activeSessionField.set(activity, previousActiveSession)
            oldSession.close()
            newSession.close()
        }
    }

    private fun createSession(): AirPlaySession {
        val server = ServerSocket(0)
        val socket = try {
            Socket("127.0.0.1", server.localPort).use { server.accept() }
        } finally {
            server.close()
        }
        return AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
    }

    private fun field(instance: Any, name: String): Field = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun dispatchOnNewIntent(activity: CarPlayHostActivity, intent: Intent) {
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
            isAccessible = true
            invoke(activity, intent)
        }
    }

    private fun clearStartedActivities(activity: CarPlayHostActivity) {
        while (shadowOf(activity.application).nextStartedActivity != null) { }
    }

    private fun allocateControllerPlaceholder(): CarPlayController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val controller = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, CarPlayController::class.java) as CarPlayController
        field(controller, "config").set(
            controller,
            CarPlayRuntimeConfig(
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
        )
        return controller
    }

    private fun invokeStatusReporter(reporter: Any?, status: CarPlayStatus) {
        requireNotNull(reporter).javaClass.getMethod("invoke", Any::class.java).invoke(reporter, status)
    }
}
