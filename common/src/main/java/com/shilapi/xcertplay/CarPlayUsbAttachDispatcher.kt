// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Starts the wired CarPlay host for a verified CH341 attach. When the user is working in a DiPlay
 * settings or menu screen the request is kept, then replayed once a non-settings screen is back in
 * front, so plugging the adapter never replaces the screen the user opened.
 */
internal object CarPlayUsbAttachDispatcher {
    private const val TAG = "xcertplay-usb"
    private const val CH341_VENDOR_ID = 0x1a86
    private const val CH341_PRODUCT_ID = 0x5512

    private var pending: UsbAttachDeviceIdentity? = null

    fun dispatchOrHold(context: Context, identity: UsbAttachDeviceIdentity) {
        if (DiPlayUiFocus.isUserNavigationTarget()) {
            synchronized(this) { pending = identity }
            Log.i(TAG, "event=APPLE_USB_ATTACH source=usb_attach result=held_for_user_navigation")
            return
        }
        launch(context, identity)
    }

    fun resume(context: Context) {
        if (DiPlayUiFocus.isUserNavigationTarget()) return
        val identity = synchronized(this) { pending } ?: return
        if (!isVerifiedCh341(context, identity)) {
            synchronized(this) { if (pending == identity) pending = null }
            Log.i(TAG, "event=APPLE_USB_ATTACH source=usb_attach result=stale_device")
            return
        }
        synchronized(this) {
            if (pending != identity) return
            pending = null
        }
        launch(context, identity)
    }

    internal fun isVerifiedCh341(context: Context, identity: UsbAttachDeviceIdentity): Boolean {
        if (identity.vendorId != CH341_VENDOR_ID || identity.productId != CH341_PRODUCT_ID) return false
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val devices = try {
            manager.deviceList.values.toList()
        } catch (error: RuntimeException) {
            Log.w(TAG, "event=APPLE_USB_ATTACH result=usb_inventory_unavailable error=${error.javaClass.simpleName}")
            return false
        }
        val device = devices.firstOrNull {
            it.deviceId == identity.deviceId && it.deviceName == identity.deviceName &&
                it.vendorId == identity.vendorId && it.productId == identity.productId
        } ?: return false
        return try {
            manager.hasPermission(device)
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun clearForTesting() {
        synchronized(this) { pending = null }
    }

    private fun launch(context: Context, identity: UsbAttachDeviceIdentity) {
        try {
            val intent = CarPlayHostActions.createInternalIntent(context, CarPlayHostActions.USB_DEVICE_ATTACHED)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "event=APPLE_USB_ATTACH source=usb_attach result=host_dispatched")
        } catch (error: RuntimeException) {
            Log.w(TAG, "event=APPLE_USB_ATTACH source=usb_attach result=launch_failed error=${error.javaClass.simpleName}")
        }
    }
}
