package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.youtube.DisplaySize
import java.lang.reflect.Field
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostActivityReconnectTest {
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

    private fun field(instance: Any, name: String): Field = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }
}
