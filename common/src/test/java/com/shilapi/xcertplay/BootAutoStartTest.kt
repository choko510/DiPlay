package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BootAutoStartTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .remove("auto_start_on_boot")
            .remove("auto_launch_on_iphone_usb_attach")
            .commit()
        AutoConnectCoordinator.shared.resetForTests()
    }

    @Test
    fun ac02_bootDoesNotLaunchWhenTheStartupSettingIsOff() {
        var started: Intent? = null
        var notified = false

        val result = BootAutoStart.launch(
            context,
            autoStartEnabled = false,
            startActivity = { started = it },
            showNotification = { notified = true; true },
        )

        assertEquals(BootAutoStart.Result.DISABLED, result)
        assertNull(started)
        assertFalse(notified)
    }

    @Test
    fun ac20_bootLaunchTargetsDiPlayAndMarksTheBootSource() {
        var started: Intent? = null

        val result = BootAutoStart.launch(
            context,
            autoStartEnabled = true,
            startActivity = { started = it },
            showNotification = { true },
        )

        assertEquals(BootAutoStart.Result.LAUNCHED, result)
        val intent = requireNotNull(started)
        assertEquals(DiPlayActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(BootAutoStart.EXTRA_AUTO_START_ON_BOOT, false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun ac21_blockedBackgroundActivityLaunchFallsBackToNotification() {
        var notified = false

        val result = BootAutoStart.launch(
            context,
            autoStartEnabled = true,
            startActivity = { throw SecurityException("background activity start denied") },
            showNotification = { notified = true; true },
        )

        assertEquals(BootAutoStart.Result.BLOCKED_NOTIFICATION_SHOWN, result)
        assertTrue(notified)
    }

    @Test
    fun ac21_blockedLaunchWithoutNotificationPermissionIsReported() {
        val result = BootAutoStart.launch(
            context,
            autoStartEnabled = true,
            startActivity = { throw SecurityException("background activity start denied") },
            showNotification = { false },
        )

        assertEquals(BootAutoStart.Result.BLOCKED_NO_NOTIFICATION, result)
    }

    @Test
    fun ac21_notificationIsPostedBeforeTheLaunchAttemptForSilentDenial() {
        val order = mutableListOf<String>()

        BootAutoStart.launch(
            context,
            autoStartEnabled = true,
            startActivity = { order += "launch" },
            showNotification = { order += "notify"; true },
        )

        // Some OEM builds deny the background Activity start without throwing. The notification
        // must already exist so the user still has a legitimate tap target.
        assertEquals(listOf("notify", "launch"), order)
    }

    @Test
    fun bootReceiverLaunchesTheHomeActivityWhenTheSettingIsOn() {
        AirPlayPersistence.saveAutoStartOnBoot(context, true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(DiPlayActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra(BootAutoStart.EXTRA_AUTO_START_ON_BOOT, false))
    }

    @Test
    fun autoStartAndAutoConnectAreIndependentSettings() {
        AirPlayPersistence.saveAutoStartOnBoot(context, true)
        DiPlayPreferences.saveAutoConnect(context, false)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(DiPlayActivity::class.java.name, started.component?.className)
        // The receiver only opens the home Activity; AutoConnectCoordinator decides the connection
        // after reading auto_connect, so opening the app cannot force a connection when it is off.
        assertFalse(DiPlayPreferences.autoConnect(context))
    }
}
