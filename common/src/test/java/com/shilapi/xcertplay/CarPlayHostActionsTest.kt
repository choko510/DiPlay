package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHostActionsTest {
    @Test
    fun onlyRecognizedHostActionsClearRestartSuppression() {
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.OPEN_CARPLAY))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.ENTER_YOUTUBE_SPLIT))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression(CarPlayHostActions.EXIT_YOUTUBE_SPLIT))
        assertTrue(CarPlayHostActions.shouldClearRestartSuppression("android.hardware.usb.action.USB_DEVICE_ATTACHED"))

        assertFalse(CarPlayHostActions.shouldClearRestartSuppression(null))
        assertFalse(CarPlayHostActions.shouldClearRestartSuppression("com.example.unknown"))
    }
}
