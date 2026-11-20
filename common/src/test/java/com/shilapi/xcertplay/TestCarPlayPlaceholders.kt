// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Allocates the framework classes the auto-launch and host navigation code inspects without
 * starting a real CarPlay transport, so tests can observe the exact Intents that are issued.
 */
internal object TestCarPlayPlaceholders {
    fun controller(transport: CarPlayTransport = CarPlayTransport.WIRED): CarPlayController =
        allocate(CarPlayController::class.java).also { controller ->
            set(controller, "config", runtimeConfig(transport))
            set(controller, "closed", false)
            set(controller, "wiredTeardownLock", Any())
        }

    fun session(closed: Boolean = false): AirPlaySession =
        allocate(AirPlaySession::class.java).also { session ->
            set(session, "closed", AtomicBoolean(closed))
        }

    fun field(instance: Any, name: String): Field =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun runtimeConfig(transport: CarPlayTransport) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        transport = transport,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 0,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun set(instance: Any, name: String, value: Any?) {
        val field = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.set(instance, value)
    }
}
