package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.youtube.DisplaySize
import java.lang.reflect.Field
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

    private fun field(instance: Any, name: String): Field = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun invokeStatusReporter(reporter: Any?, status: CarPlayStatus) {
        requireNotNull(reporter).javaClass.getMethod("invoke", Any::class.java).invoke(reporter, status)
    }
}
