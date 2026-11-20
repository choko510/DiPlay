package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens DiPlay after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AutoConnectLog.event("event=BOOT_RECEIVED autoStart=${AirPlayPersistence.loadAutoStartOnBoot(context)}")
        IphoneUsbBootReconciler.reconcile(context)
        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return
        BootAutoStart.launch(context)
    }
}
