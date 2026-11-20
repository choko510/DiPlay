package com.shilapi.xcertplay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayActivityAutoConnectTest {
    private lateinit var app: Application
    private var bootstrapReady = false

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        val ready = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        bootstrapReady = ready.getBoolean(DiPlayBootstrap)
        ready.setBoolean(DiPlayBootstrap, true)
        AutoConnectCoordinator.shared.resetForTests()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        clearPreferences()
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        AutoConnectCoordinator.shared.resetForTests()
        clearPreferences()
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply {
            isAccessible = true
            setBoolean(DiPlayBootstrap, bootstrapReady)
        }
    }

    @Test
    fun ac01_autoConnectOnOpensTheHostOnAppLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("auto-connect did not open CarPlayHostActivity", started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac02_autoConnectOffDoesNotOpenTheHost() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, false)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac05_recreatedHomeActivityDoesNotStartASecondConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedActivity)

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac06_repeatedOnResumeDoesNotStartASecondConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedActivity)

        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac07_newIntentOnTheHomeActivityCanStartTheConnection() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)

        controller.newIntent(Intent(app, DiPlayActivity::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac08_existingSessionIsNotReplacedByAutoConnect() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val stopAction = setFakeSession()

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertSame(stopAction, sessionStopAction())
        controller.pause().stop().destroy()
    }

    @Test
    fun ac23_resumingTheSettingsPageDoesNotConnect() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)

        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac25_intentionalDisconnectSuppressesTheNextAutomaticLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        setFakeSession()
        CarPlayBackgroundSession.active = true

        val first = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        findButton(first.get(), first.get().getString(R.string.ui_disconnect))?.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AutoConnectState.SUPPRESSED, AutoConnectCoordinator.shared.currentState())
        first.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.active = false
        shadowOf(app).clearNextStartedActivities()

        val second = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("auto-connect ignored the explicit disconnect", shadowOf(app).nextStartedActivity)
        second.pause().stop().destroy()
    }

    @Test
    fun ac26_aNewAppLaunchGetsANewRetryBudgetAfterExhaustion() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        exhaustCoordinator()
        assertEquals(AutoConnectState.EXHAUSTED, AutoConnectCoordinator.shared.currentState())
        shadowOf(app).clearNextStartedActivities()

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("a new launch stayed blocked by the previous retry budget", started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac27_aNewAppLaunchRetriesAfterPermissionWasGrantedInSettings() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("previous-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRED),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        AutoConnectCoordinator.shared.postponeForPermission(start.generation)
        AutoConnectCoordinator.shared.onPermissionResult(start.generation, granted = false)
        assertEquals(AutoConnectState.BLOCKED, AutoConnectCoordinator.shared.currentState())
        shadowOf(app).clearNextStartedActivities()

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("a previous permission denial permanently blocked auto-connect", shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac28_pendingDialogStateSurvivesActivityRecreation() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val first = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        setPrivateField(first.get(), "notificationTransport", false)
        setPrivateField(first.get(), "pendingWireless", true)
        setPrivateField(first.get(), "pendingWirelessAutoGeneration", 77L)
        val state = Bundle()
        first.saveInstanceState(state)
        first.pause().stop().destroy()

        val recreated = Robolectric.buildActivity(DiPlayActivity::class.java).create(state).get()

        assertEquals(false, privateField(recreated, "notificationTransport"))
        assertEquals(true, privateField(recreated, "pendingWireless"))
        assertEquals(77L, privateField(recreated, "pendingWirelessAutoGeneration"))
    }

    @Test
    fun ac29_phoneSelectionContinuesAnAutoRequestAndStartsTheHost() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        Shadows.shadowOf(device).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("wireless-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)

        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("phone selection dropped the pending auto-connect request", started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        assertEquals("AA:BB:CC:DD:EE:01", DiPlayPreferences.phoneAddress(app))
        controller.pause().stop().destroy()
    }

    @Test
    fun ac31_newIntentAfterExhaustionStartsAFreshRequest() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        exhaustCoordinator()
        assertEquals(AutoConnectState.EXHAUSTED, AutoConnectCoordinator.shared.currentState())
        shadowOf(app).clearNextStartedActivities()

        controller.newIntent(launcherIntent())
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("a new Intent did not renew the exhausted launch key", shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac31_internalSettingsIntentDoesNotRenewTheLaunchKey() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        exhaustCoordinator()
        shadowOf(app).clearNextStartedActivities()

        controller.newIntent(
            Intent(app, DiPlayActivity::class.java)
                .putExtra("page", "settings")
                .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(AutoConnectState.EXHAUSTED, AutoConnectCoordinator.shared.currentState())
        controller.pause().stop().destroy()
    }

    @Test
    fun ac32_newIntentAfterPermissionBlockRetries() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("blocked-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRED),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        AutoConnectCoordinator.shared.postponeForPermission(start.generation)
        AutoConnectCoordinator.shared.onPermissionResult(start.generation, granted = false)
        assertEquals(AutoConnectState.BLOCKED, AutoConnectCoordinator.shared.currentState())
        shadowOf(app).clearNextStartedActivities()

        controller.newIntent(launcherIntent())
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("a new Intent stayed blocked by the previous permission denial", shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac34_newIntentWhilePhoneDialogIsOpenDoesNotOpenAnotherDialog() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:03")
        Shadows.shadowOf(device).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("wireless-dialog", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()

        controller.newIntent(Intent(app, DiPlayActivity::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(firstDialog, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(AutoConnectState.WAITING_FOR_USER, AutoConnectCoordinator.shared.currentState())
        controller.pause().stop().destroy()
    }

    @Test
    fun ac38_cancelledSelectionDoesNotReopenForTheSameKeyRedelivery() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:08")
        Shadows.shadowOf(device).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("same-key-delivery", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "autoConnectLaunchKey", "same-key-delivery")
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        val cancelledDialog = ShadowAlertDialog.getLatestAlertDialog()
        cancelledDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AutoConnectCoordinator.shared.isCancelled("same-key-delivery"))

        controller.newIntent(Intent(app, DiPlayActivity::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AutoConnectState.IDLE, AutoConnectCoordinator.shared.currentState())
        assertNull(shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac39_cancelledSelectionCanRestartFromANewUserLaunch() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:04")
        Shadows.shadowOf(device).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("cancelled-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "autoConnectLaunchKey", "cancelled-launch")
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        val cancelledDialog = ShadowAlertDialog.getLatestAlertDialog()
        cancelledDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AutoConnectState.IDLE, AutoConnectCoordinator.shared.currentState())
        assertTrue(AutoConnectCoordinator.shared.isCancelled("cancelled-launch"))

        controller.newIntent(launcherIntent())
        shadowOf(Looper.getMainLooper()).idle()

        val restartedDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame("cancelled request was not restarted with a new launch key", cancelledDialog, restartedDialog)
        assertEquals(listOf("iPhone"), Shadows.shadowOf(restartedDialog).items?.toList())
        controller.pause().stop().destroy()
    }

    @Test
    fun ac40_pairingAnotherIphoneReturnsToTheSelectionDialog() {
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val firstDevice = adapter.getRemoteDevice("AA:BB:CC:DD:EE:05")
        Shadows.shadowOf(firstDevice).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(firstDevice))
        val settings = Intent(app, DiPlayActivity::class.java)
            .putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, settings).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("pair-another-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()
        firstDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AutoConnectState.WAITING_FOR_USER, AutoConnectCoordinator.shared.currentState())

        val secondDevice = adapter.getRemoteDevice("AA:BB:CC:DD:EE:06")
        Shadows.shadowOf(secondDevice).setName("iPhone 2")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(firstDevice, secondDevice))
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val restoredDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(firstDialog, restoredDialog)
        assertEquals(
            listOf("iPhone", "iPhone 2"),
            Shadows.shadowOf(restoredDialog).items?.toList(),
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun ac42_processRecreationRecoversAStalePermissionWait() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:07", "iPhone")
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        DiPlayPreferences.saveAutoConnect(app, true)
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("stale-permission", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "pendingBluetoothAutoGeneration", start.generation)
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        val state = Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()
        AutoConnectCoordinator.shared.resetForTests()
        shadowOf(app).clearNextStartedActivities()

        val recreated = Robolectric.buildActivity(DiPlayActivity::class.java).setup(state)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("process recreation did not start a fresh auto-connect request", shadowOf(app).nextStartedActivity)
        recreated.pause().stop().destroy()
    }

    @Test
    fun ac33_phoneSelectionDialogIsRestoredAfterActivityRecreation() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:02")
        Shadows.shadowOf(device).setName("iPhone")
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("wireless-recreate", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRELESS),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        setPrivateField(controller.get(), "pendingWireless", true)
        setPrivateField(controller.get(), "pendingWirelessAutoGeneration", start.generation)
        controller.get().javaClass.getDeclaredMethod("choosePhone", Long::class.javaObjectType).apply {
            isAccessible = true
            invoke(controller.get(), start.generation)
        }
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(AutoConnectState.WAITING_FOR_USER, AutoConnectCoordinator.shared.currentState())

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val restoredDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame("the phone selection dialog was not restored", firstDialog, restoredDialog)
        Shadows.shadowOf(restoredDialog).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("the restored phone dialog did not continue the connection", shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun ac30_staleAutoRequestCannotStartAfterAutoConnectOff() {
        DiPlayPreferences.saveAutoConnect(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val start = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("wired-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRED),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        AutoConnectCoordinator.shared.onAutoConnectDisabled()

        controller.get().javaClass.getDeclaredMethod(
            "connectWithAuto",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaObjectType,
        ).apply {
            isAccessible = true
            invoke(controller.get(), false, true, start.generation)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("a stale permission callback started the host", shadowOf(app).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    private fun exhaustCoordinator() {
        var outcome = AutoConnectCoordinator.shared.request(
            AutoConnectRequest("old-launch", AutoConnectSource.APP_LAUNCH, AutoConnectTransport.WIRED),
            AutoConnectConditions(
                enabled = true,
                setupReady = true,
                session = AutoConnectSessionState.NONE,
            ),
        ) as AutoConnectOutcome.Start
        repeat(3) {
            val next = AutoConnectCoordinator.shared.onHostLaunchFailed(
                outcome.generation,
                AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE,
            )
            if (it < 2) {
                outcome = AutoConnectCoordinator.shared.retryDue(
                    AutoConnectConditions(
                        enabled = true,
                        setupReady = true,
                        session = AutoConnectSessionState.NONE,
                    ),
                    nowMillis = Long.MAX_VALUE,
                ) as AutoConnectOutcome.Start
            } else {
                assertTrue(next is AutoConnectOutcome.Exhausted)
            }
        }
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun launcherIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(app, DiPlayActivity::class.java)

    private fun setFakeSession(): (() -> Unit) -> Unit {
        val field = CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply {
            isAccessible = true
        }
        val action: (() -> Unit) -> Unit = { completion -> completion() }
        field.set(CarPlayBackgroundSession, action)
        return action
    }

    private fun sessionStopAction(): Any? =
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction").apply {
            isAccessible = true
        }.get(CarPlayBackgroundSession)

    private fun clearPreferences() {
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun findButton(activity: android.app.Activity, text: String): Button? =
        findButtonIn(activity.window.decorView, text)

    private fun findButtonIn(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButtonIn(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
