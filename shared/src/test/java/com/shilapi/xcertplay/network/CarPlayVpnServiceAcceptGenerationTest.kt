package com.shilapi.xcertplay.network

import android.content.Intent
import android.net.VpnService
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayVpnServiceAcceptGenerationTest {
    @Test
    fun systemVpnBindingUsesPlatformBinderAndLocalBindingKeepsLocalBinder() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val localBinder = service.onBind(Intent(service, CarPlayVpnService::class.java))
        val systemBinder = service.onBind(Intent(VpnService.SERVICE_INTERFACE))

        assertTrue(localBinder is CarPlayVpnService.LocalBinder)
        assertNotNull(systemBinder)
        assertNotSame(localBinder, systemBinder)
        assertFalse(systemBinder is CarPlayVpnService.LocalBinder)
    }

    @Test
    fun socketAcceptedByOldServerIsClosedAfterTheAttachmentChanges() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val acceptedSocket = connectedSocket()
        val oldListener = CountingListener()
        val newListener = CountingListener()
        val oldServer = GatedServerSocket(acceptedSocket)
        val newServer = ServerSocket()
        val invocationFailure = AtomicReference<Throwable?>()
        val oldAttachment = createAttachment(oldListener)
        val newAttachment = createAttachment(newListener)
        val acceptLoop = CarPlayVpnService::class.java.getDeclaredMethod(
            "acceptLoop",
            Int::class.javaPrimitiveType,
            ServerSocket::class.java,
        ).apply { isAccessible = true }

        try {
            synchronized(service) {
                field(service, "active").set(service, AtomicBoolean(true))
                field(service, "attachGeneration").setInt(service, 1)
                field(service, "attachment").set(service, oldAttachment)
                field(service, "serverSocket").set(service, oldServer)
            }
            val acceptThread = Thread {
                try {
                    acceptLoop.invoke(service, 1, oldServer)
                } catch (error: Throwable) {
                    invocationFailure.set(error)
                }
            }.apply {
                name = "test-old-airplay-accept"
                isDaemon = true
                start()
            }

            assertTrue(oldServer.awaitAccept())
            synchronized(service) {
                field(service, "attachGeneration").setInt(service, 2)
                field(service, "attachment").set(service, newAttachment)
                field(service, "serverSocket").set(service, newServer)
            }
            oldServer.releaseAccept()
            acceptThread.join(2_000)

            assertFalse(acceptThread.isAlive)
            assertTrue(acceptedSocket.isClosed)
            assertNull(invocationFailure.get())
            assertTrue(oldListener.stages.isEmpty())
            assertTrue(newListener.stages.isEmpty())
            assertEquals(0, sessionCount(service))
        } finally {
            oldServer.releaseAccept()
            runCatching { oldServer.close() }
            runCatching { newServer.close() }
            runCatching { acceptedSocket.close() }
        }
    }

    @Test
    fun traceCallbackFailureClosesAcceptedSocketAndFailsTheCurrentAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val acceptedSocket = connectedSocket()
        val listener = CountingListener(throwOnStage = ConnectionTraceStage.AIRPLAY_CONTROL_ACCEPTED)
        val server = GatedServerSocket(acceptedSocket)
        val invocationFailure = AtomicReference<Throwable?>()
        val attachment = createAttachment(listener)
        val acceptLoop = CarPlayVpnService::class.java.getDeclaredMethod(
            "acceptLoop",
            Int::class.javaPrimitiveType,
            ServerSocket::class.java,
        ).apply { isAccessible = true }

        synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 1)
            field(service, "attachment").set(service, attachment)
            field(service, "serverSocket").set(service, server)
        }
        val acceptThread = Thread {
            try {
                acceptLoop.invoke(service, 1, server)
            } catch (error: Throwable) {
                invocationFailure.set(error)
            }
        }.apply {
            name = "test-airplay-accept-callback-failure"
            isDaemon = true
            start()
        }

        try {
            assertTrue(server.awaitAccept())
            server.releaseAccept()
            acceptThread.join(2_000)
            assertFalse(acceptThread.isAlive)
            assertTrue(acceptedSocket.isClosed)
            assertNull(invocationFailure.get())
            assertTrue(listener.transportErrorDelivered.await(2, TimeUnit.SECONDS))
            assertFalse((field(service, "active").get(service) as AtomicBoolean).get())
        } finally {
            server.releaseAccept()
            runCatching { server.close() }
            runCatching { acceptedSocket.close() }
        }
    }

    @Test
    fun revokeCleanupDoesNotBlockCallbackOrTearDownANewerAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val active = AtomicBoolean(true)
        field(service, "active").set(service, active)
        field(service, "attachGeneration").setInt(service, 5)
        val callbackReturned = CountDownLatch(1)
        val callbackThread = Thread {
            service.onRevoke()
            callbackReturned.countDown()
        }

        val cleanupThread = synchronized(service) {
            callbackThread.start()
            assertTrue(callbackReturned.await(1, TimeUnit.SECONDS))
            val worker = awaitThread("carplay-vpn-revoke")
            field(service, "attachGeneration").setInt(service, 6)
            worker
        }
        cleanupThread.join(2_000)

        assertFalse(cleanupThread.isAlive)
        assertEquals(6, field(service, "attachGeneration").getInt(service))
        assertTrue(active.get())
    }

    @Test
    fun revokeStopsAndNotifiesTheCurrentAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val active = AtomicBoolean(true)
        val listener = CountingListener()
        field(service, "active").set(service, active)
        field(service, "attachGeneration").setInt(service, 7)
        field(service, "attachment").set(service, createAttachment(listener))

        service.onRevoke()

        assertTrue(listener.transportErrorDelivered.await(2, TimeUnit.SECONDS))
        assertFalse(active.get())
        assertEquals(8, field(service, "attachGeneration").getInt(service))
    }

    private fun createAttachment(listener: AirPlaySessionListener): Any {
        val attachmentClass = CarPlayVpnService::class.java.declaredClasses
            .single { it.simpleName == "AirPlayAttachment" }
        val constructor = attachmentClass.declaredConstructors.single().apply { isAccessible = true }
        val address = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        )
        return constructor.newInstance(
            address,
            config,
            AirPlayIdentity.generate(),
            PairingStore(),
            null,
            listener,
            object : AirPlayMediaHandler {},
            null,
            true,
        )
    }

    private fun connectedSocket(): Socket {
        val listener = ServerSocket(0)
        return try {
            Socket("127.0.0.1", listener.localPort).use {
                listener.accept()
            }
        } finally {
            listener.close()
        }
    }

    private fun sessionCount(service: CarPlayVpnService): Int =
        (field(service, "sessions").get(service) as Set<*>).size

    private fun field(instance: Any, name: String) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun awaitThread(name: String): Thread {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            Thread.getAllStackTraces().keys.firstOrNull { it.name == name }?.let { return it }
            Thread.yield()
        }
        throw AssertionError("Thread $name did not start")
    }

    private class GatedServerSocket(private val accepted: Socket) : ServerSocket() {
        private val acceptEntered = CountDownLatch(1)
        private val acceptReleased = CountDownLatch(1)

        override fun accept(): Socket {
            acceptEntered.countDown()
            if (!acceptReleased.await(2, TimeUnit.SECONDS)) {
                throw SocketException("test accept timed out")
            }
            return accepted
        }

        fun awaitAccept(): Boolean = acceptEntered.await(2, TimeUnit.SECONDS)

        fun releaseAccept() {
            acceptReleased.countDown()
        }
    }

    private class CountingListener(
        private val throwOnStage: ConnectionTraceStage? = null,
    ) : AirPlaySessionListener {
        val stages = mutableListOf<ConnectionTraceStage>()
        val transportErrorDelivered = CountDownLatch(1)

        override fun onConnectionTrace(stage: ConnectionTraceStage) {
            stages += stage
            if (stage == throwOnStage) throw IllegalStateException("test callback failure")
        }

        override fun onTransportError(message: String) {
            transportErrorDelivered.countDown()
        }
    }
}
