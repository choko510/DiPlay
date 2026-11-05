package com.shilapi.xcertplay

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class WirelessPermissionRefusalTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<CarPlayHostActivity>? = null
    private var previousBootstrapReady = false
    private val wireless = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )
    private val permissions = wireless + Manifest.permission.RECORD_AUDIO
    private var permissionsGrantedBefore = emptySet<String>()

    @Before fun setUp() {
        permissionsGrantedBefore = permissions.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        val bootstrapReady = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        previousBootstrapReady = bootstrapReady.getBoolean(null)
        bootstrapReady.setBoolean(null, true)
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context).denyPermissions(*wireless.toTypedArray())
    }

    @After fun tearDown() {
        controller?.let { host ->
            runCatching { host.pause().stop().destroy() }
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host.get(), field).shutdownNow()
            }
        }
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply {
            isAccessible = true
            setBoolean(null, previousBootstrapReady)
        }
        shadowOf(context).grantPermissions(*permissionsGrantedBefore.toTypedArray())
        shadowOf(context).denyPermissions(*permissions.filterNot(permissionsGrantedBefore::contains).toTypedArray())
    }

    private fun open(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).setup().also { controller = it }.get()

    private fun answer(activity: CarPlayHostActivity, granted: Set<String> = emptySet()): List<String> {
        val request = shadowOf(activity).lastRequestedPermission
        val asked = request.requestedPermissions.toList()
        assertTrue(asked.toString(), asked.any { it in wireless })
        shadowOf(context).grantPermissions(*granted.toTypedArray())
        activity.activityResultRegistry.dispatchResult(
            request.requestCode,
            asked.associateWith { it in granted },
        )
        shadowOf(Looper.getMainLooper()).idle()
        return asked
    }

    private fun stage(activity: CarPlayHostActivity): String =
        ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()

    @Config(sdk = [29])
    @Test fun refusedLocationIsNamedAndStillVisibleAfterResume() {
        val activity = open()
        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            answer(activity),
        )
        val guidance = activity.getString(R.string.host_stage_precise_location)
        assertEquals(guidance, stage(activity))
        requireNotNull(controller).pause().resume()
        assertEquals(guidance, stage(activity))
    }

    @Config(sdk = [33])
    @Test fun refusedNearbyDevicesIsNamed() {
        val activity = open()
        answer(activity)
        assertEquals(activity.getString(R.string.host_stage_nearby_devices), stage(activity))
    }

    @Config(sdk = [32])
    @Test fun guidanceChangesWhenOnlyNearbyPermissionRemainsMissing() {
        val activity = open()
        answer(activity)
        assertEquals(activity.getString(R.string.host_stage_precise_location), stage(activity))
        requireNotNull(controller).pause()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        requireNotNull(controller).resume()
        assertEquals(activity.getString(R.string.host_stage_nearby_devices), stage(activity))
    }

    @Config(sdk = [29])
    @Test fun grantingWirelessPermissionsClearsTheRefusalMessage() {
        val activity = open()
        answer(activity)
        requireNotNull(controller).pause()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        requireNotNull(controller).resume()
        assertEquals(activity.getString(R.string.host_stage_ready), stage(activity))
    }
}
