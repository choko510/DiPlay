package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun refusalMessageNamesOnlyRelevantMissingWirelessAccess() {
        assertEquals(
            R.string.host_stage_precise_location,
            WirelessPermissions.refusalMessage(listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
        )
        assertEquals(
            R.string.host_stage_precise_location,
            WirelessPermissions.refusalMessage(
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
        )
        assertEquals(
            R.string.host_stage_nearby_devices,
            WirelessPermissions.refusalMessage(listOf(Manifest.permission.BLUETOOTH_CONNECT)),
        )
        assertEquals(
            R.string.host_stage_nearby_devices,
            WirelessPermissions.refusalMessage(
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES, "android.permission.ACCESS_LOCAL_NETWORK"),
            ),
        )
        assertEquals(
            R.string.host_stage_could_not_start,
            WirelessPermissions.refusalMessage(listOf("android.permission.ACCESS_LOCAL_NETWORK")),
        )
    }
}
