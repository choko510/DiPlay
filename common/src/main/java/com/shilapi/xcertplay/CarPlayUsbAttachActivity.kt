package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle

internal data class UsbAttachDeviceIdentity(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
)

internal object UsbAttachIntentPolicy {
    private const val CH341_VENDOR_ID = 0x1a86
    private const val CH341_PRODUCT_ID = 0x5512

    fun isVerifiedCh341(
        supplied: UsbAttachDeviceIdentity?,
        currentlyAttached: Collection<UsbAttachDeviceIdentity>,
        permissionGranted: Boolean,
    ): Boolean = permissionGranted && supplied?.let { device ->
        device.vendorId == CH341_VENDOR_ID && device.productId == CH341_PRODUCT_ID &&
            currentlyAttached.any { current ->
                current.deviceId == device.deviceId && current.deviceName == device.deviceName &&
                    current.vendorId == CH341_VENDOR_ID && current.productId == CH341_PRODUCT_ID
            }
    } == true
}

class CarPlayUsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val attachedDevice = usbDeviceFromIntent(intent)
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        if (intent.action == CarPlayHostActions.USB_DEVICE_ATTACHED &&
            attachedDevice != null && usbManager?.hasPermission(attachedDevice) == true &&
            UsbAttachIntentPolicy.isVerifiedCh341(
                attachedDevice.identity(),
                usbManager.deviceList.values
                    .map { it.identity() },
                permissionGranted = true,
            )
        ) {
            startActivity(CarPlayHostActions.createInternalIntent(this, CarPlayHostActions.USB_DEVICE_ATTACHED))
        }
        finish()
    }

    private fun UsbDevice.identity(): UsbAttachDeviceIdentity = UsbAttachDeviceIdentity(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
    )

    @Suppress("DEPRECATION")
    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

}
