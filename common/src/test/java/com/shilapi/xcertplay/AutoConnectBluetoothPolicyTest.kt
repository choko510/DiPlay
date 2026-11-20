package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the intentional product rule: DiPlay does not monitor Bluetooth devices
 * and does not auto-connect to a head unit merely because a Bluetooth device connected. Manual
 * iPhone selection and the RFCOMM link used by an explicitly started wireless session stay intact.
 */
class AutoConnectBluetoothPolicyTest {
    @Test
    fun autoLaunchPathContainsNoBluetoothMonitoringOrAutoPairing() {
        val sources = listOf(
            "AutoConnectCoordinator.kt",
            "BootAutoStart.kt",
            "BootReceiver.kt",
            "DiPlaySessionService.kt",
        ).map(::mainSource)

        val forbidden = listOf(
            "BluetoothAdapter",
            "BluetoothDevice",
            "BluetoothManager",
            "ACTION_ACL_CONNECTED",
            "ACTION_ACL_DISCONNECTED",
            "startDiscovery",
            "bondDevice",
            "createRfcommSocket",
            "registerReceiver",
        )
        for (source in sources) {
            for (token in forbidden) {
                assertFalse(
                    "${source.name} must not add Bluetooth monitoring/pairing: $token",
                    source.readText().contains(token),
                )
            }
        }
    }

    @Test
    fun homeActivityStillHasOnlyTheManualBluetoothSelectionPath() {
        val source = mainSource("DiPlayActivity.kt").readText()

        assertTrue(source.contains("bondedDevices"))
        assertFalse(source.contains("ACTION_ACL_CONNECTED"))
        assertFalse(source.contains("ACTION_ACL_DISCONNECTED"))
        assertFalse(source.contains("startDiscovery"))
        assertFalse(source.contains("bondDevice"))
    }

    private fun mainSource(name: String): File {
        val candidates = listOf(
            File("src/main/java/com/shilapi/xcertplay/$name"),
            File("common/src/main/java/com/shilapi/xcertplay/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Source not found: $name (cwd=${File(".").canonicalPath})")
    }
}
