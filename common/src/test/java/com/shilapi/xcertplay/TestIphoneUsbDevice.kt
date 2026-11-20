// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.lang.reflect.Field
import org.robolectric.Shadows.shadowOf

/**
 * Builds an iPhone-shaped USB device (USBMUX + NCM + Apple Ethernet) that the production USB
 * inventory code accepts, so auto-launch tests exercise the real dispatch path instead of a stub.
 */
internal object TestIphoneUsbDevice {
    const val APPLE_VENDOR_ID = 0x05ac
    const val APPLE_PRODUCT_ID = 0x12a8

    fun attach(
        context: Context,
        name: String = "/dev/bus/usb/001/002",
        vendorId: Int = APPLE_VENDOR_ID,
        productId: Int = APPLE_PRODUCT_ID,
        carPlayInterfaces: Boolean = true,
        permissionGranted: Boolean = true,
    ): UsbDevice {
        val interfaces = if (carPlayInterfaces) {
            arrayOf(
                usbInterface(id = 4, clazz = 0xff, subclass = 0xfe, protocol = 0x02),
                usbInterface(id = 5, clazz = 0x02, subclass = 0x0d, protocol = 0x00),
                usbInterface(id = 6, clazz = 0xff, subclass = 0xfd, protocol = 0x01),
            )
        } else {
            arrayOf(usbInterface(id = 1, clazz = 0x08, subclass = 0x06, protocol = 0x50))
        }
        val configuration = allocate(UsbConfiguration::class.java).also { config ->
            set(config, "mId", 1)
            set(config, "mName", "CarPlay")
            set(config, "mInterfaces", interfaces)
        }
        val device = allocate(UsbDevice::class.java).also { usbDevice ->
            set(usbDevice, "mName", name)
            set(usbDevice, "mVendorId", vendorId)
            set(usbDevice, "mProductId", productId)
            set(usbDevice, "mClass", 0)
            set(usbDevice, "mSubclass", 0)
            set(usbDevice, "mProtocol", 0)
            set(usbDevice, "mConfigurations", arrayOf(configuration))
            set(usbDevice, "mInterfaces", interfaces)
        }
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, permissionGranted)
        return device
    }

    fun detach(context: Context, device: UsbDevice) {
        shadowOf(context.getSystemService(UsbManager::class.java)).removeUsbDevice(device)
    }

    private fun usbInterface(id: Int, clazz: Int, subclass: Int, protocol: Int): UsbInterface =
        allocate(UsbInterface::class.java).also { usbInterface ->
            set(usbInterface, "mId", id)
            set(usbInterface, "mAlternateSetting", 0)
            set(usbInterface, "mName", null)
            set(usbInterface, "mClass", clazz)
            set(usbInterface, "mSubclass", subclass)
            set(usbInterface, "mProtocol", protocol)
            set(usbInterface, "mEndpoints", emptyArray<android.hardware.usb.UsbEndpoint>())
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun set(instance: Any, name: String, value: Any?) {
        val field = declaredField(instance.javaClass, name) ?: error("No field $name on ${instance.javaClass}")
        field.isAccessible = true
        field.set(instance, value)
    }

    private fun declaredField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }
}
