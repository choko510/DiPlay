package com.shilapi.xcertplay

import android.hardware.usb.UsbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbAutoLaunchPolicyTest {
    private val iphone = UsbAttachDeviceIdentity(
        deviceId = 17,
        deviceName = "test-apple-device",
        vendorId = 0x05ac,
        productId = 0x12a8,
    )

    @Test
    fun requiresEnabledSettingUsbAttachActionAndCurrentDevice() {
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = false,
                eventDevice = iphone,
                attachedDevices = listOf(candidate(iphone)),
            ),
            IphoneUsbAutoAttachRejection.DISABLED,
        )
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                "com.example.NOT_USB_ATTACH",
                enabled = true,
                eventDevice = iphone,
                attachedDevices = listOf(candidate(iphone)),
            ),
            IphoneUsbAutoAttachRejection.WRONG_ACTION,
        )
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = null,
                attachedDevices = listOf(candidate(iphone)),
            ),
            IphoneUsbAutoAttachRejection.MISSING_DEVICE,
        )
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = iphone,
                attachedDevices = emptyList(),
            ),
            IphoneUsbAutoAttachRejection.STALE_DEVICE,
        )
    }

    @Test
    fun acceptsCurrentAppleCarPlayConfigurationAcrossProductIds() {
        val otherProduct = iphone.copy(productId = 0x12a9)
        val decision = IphoneUsbAutoLaunchPolicy.evaluate(
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            enabled = true,
            eventDevice = otherProduct,
            attachedDevices = listOf(candidate(otherProduct, hasCarPlayConfiguration = true)),
        )

        assertEquals(IphoneUsbAutoAttachValidation.Accepted(otherProduct), decision)
    }

    @Test
    fun attachEntryDefersOnlyTransientInventoryFailureForAnAppleDevice() {
        assertEquals(
            IphoneUsbAutoAttachEntryDecision.Retry(iphone),
            evaluateIphoneUsbAutoAttachEntry(
                action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = iphone,
                inventoryAvailable = false,
                attachedDevices = emptyList(),
            ),
        )
        assertEquals(
            IphoneUsbAutoAttachEntryDecision.Reject(IphoneUsbAutoAttachRejection.STALE_DEVICE),
            evaluateIphoneUsbAutoAttachEntry(
                action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = iphone,
                inventoryAvailable = true,
                attachedDevices = emptyList(),
            ),
        )
        assertEquals(
            IphoneUsbAutoAttachEntryDecision.Reject(IphoneUsbAutoAttachRejection.NOT_APPLE),
            evaluateIphoneUsbAutoAttachEntry(
                action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = iphone.copy(vendorId = 0x1a86),
                inventoryAvailable = false,
                attachedDevices = emptyList(),
            ),
        )
    }

    @Test
    fun rejectsAppleVendorMatchesWithoutCarPlayConfigurationAndNonAppleDevices() {
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = iphone,
                attachedDevices = listOf(candidate(iphone, hasCarPlayConfiguration = false)),
            ),
            IphoneUsbAutoAttachRejection.NO_CARPLAY_CONFIGURATION,
        )
        val ch341 = iphone.copy(vendorId = 0x1a86, productId = 0x5512)
        assertRejected(
            IphoneUsbAutoLaunchPolicy.evaluate(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                enabled = true,
                eventDevice = ch341,
                attachedDevices = listOf(candidate(ch341, hasCarPlayConfiguration = true)),
            ),
            IphoneUsbAutoAttachRejection.NOT_APPLE,
        )
    }

    @Test
    fun staleDeviceNameOrIdCannotSubstituteForCurrentUsbDevice() {
        val current = iphone.copy(deviceId = 18, deviceName = "new-test-apple-device")
        val decision = IphoneUsbAutoLaunchPolicy.evaluate(
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            enabled = true,
            eventDevice = iphone,
            attachedDevices = listOf(candidate(current)),
        )

        assertRejected(decision, IphoneUsbAutoAttachRejection.STALE_DEVICE)
    }

    private fun candidate(
        identity: UsbAttachDeviceIdentity,
        hasCarPlayConfiguration: Boolean = true,
    ) = IphoneUsbAutoAttachCandidate(identity, hasCarPlayConfiguration)

    private fun assertRejected(
        validation: IphoneUsbAutoAttachValidation,
        reason: IphoneUsbAutoAttachRejection,
    ) {
        assertTrue(validation is IphoneUsbAutoAttachValidation.Rejected)
        assertEquals(reason, (validation as IphoneUsbAutoAttachValidation.Rejected).reason)
    }
}
