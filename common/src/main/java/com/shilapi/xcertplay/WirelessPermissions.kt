package com.shilapi.xcertplay

import android.Manifest
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

internal object WirelessPermissions {
    @StringRes
    fun refusalMessage(missing: Collection<String>): Int = when {
        Manifest.permission.ACCESS_FINE_LOCATION in missing || Manifest.permission.ACCESS_COARSE_LOCATION in missing ->
            R.string.host_stage_precise_location
        Manifest.permission.BLUETOOTH_CONNECT in missing || Manifest.permission.NEARBY_WIFI_DEVICES in missing ->
            R.string.host_stage_nearby_devices
        else -> R.string.host_stage_could_not_start
    }
}
