package com.shilapi.xcertplay.network

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

    private class CountingListener : AirPlaySessionListener {
        val stages = mutableListOf<ConnectionTraceStage>()

        override fun onConnectionTrace(stage: ConnectionTraceStage) {
            stages += stage
        }
    }
}
