package com.shilapi.xcertplay.network

import android.content.Intent
import android.net.VpnService
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    fun attachmentChangedAfterAcceptSnapshotClosesSocketBeforeAddingSession() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val acceptedSocket = connectedSocket()
        val snapshotTaken = CountDownLatch(1)
        val continueAccept = CountDownLatch(1)
        val oldListener = CountingListener(onStage = { stage ->
            if (stage == ConnectionTraceStage.AIRPLAY_CONTROL_ACCEPTED) {
                snapshotTaken.countDown()
                assertTrue(continueAccept.await(2, TimeUnit.SECONDS))
            }
        })
        val newListener = CountingListener()
        val oldServer = GatedServerSocket(acceptedSocket)
        val newServer = ServerSocket()
        val oldAttachment = createAttachment(oldListener)
        val newAttachment = createAttachment(newListener)
        val acceptLoop = CarPlayVpnService::class.java.getDeclaredMethod(
            "acceptLoop",
            Int::class.javaPrimitiveType,
            ServerSocket::class.java,
        ).apply { isAccessible = true }
        val invocationFailure = AtomicReference<Throwable?>()

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
            name = "test-airplay-snapshot-replacement"
            isDaemon = true
            start()
        }

        try {
            assertTrue(oldServer.awaitAccept())
            oldServer.releaseAccept()
            assertTrue(snapshotTaken.await(2, TimeUnit.SECONDS))
            synchronized(service) {
                field(service, "attachGeneration").setInt(service, 2)
                field(service, "attachment").set(service, newAttachment)
                field(service, "serverSocket").set(service, newServer)
            }
            continueAccept.countDown()
            acceptThread.join(2_000)

            assertFalse(acceptThread.isAlive)
            assertTrue(acceptedSocket.isClosed)
            assertNull(invocationFailure.get())
            assertEquals(0, sessionCount(service))
            assertTrue(newListener.stages.isEmpty())
        } finally {
            continueAccept.countDown()
            oldServer.releaseAccept()
            runCatching { oldServer.close() }
            runCatching { newServer.close() }
            runCatching { acceptedSocket.close() }
        }
    }

    @Test
    fun sessionStartIsSerializedWithAttachmentOwnershipValidation() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val listener = CountingListener()
        val attachment = createAttachment(listener)
        val oldServer = ServerSocket()
        val newServer = ServerSocket()
        val socket = connectedSocket()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val session = createSession(
            socket = socket,
            attachment = attachment,
            media = object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    order += "closed"
                }
            },
        )
        val startEntered = CountDownLatch(1)
        val allowStartToFinish = CountDownLatch(1)
        val swapStarted = CountDownLatch(1)
        val swapFinished = CountDownLatch(1)
        val invocationFailure = AtomicReference<Throwable?>()
        val startAccepted = CarPlayVpnService::class.java.declaredMethods
            .single { it.name == "startAcceptedSessionIfCurrent" }
            .apply { isAccessible = true }

        synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 1)
            field(service, "attachment").set(service, attachment)
            field(service, "serverSocket").set(service, oldServer)
        }
        val acceptThread = Thread {
            try {
                val starter = { _: AirPlaySession ->
                    assertTrue(Thread.holdsLock(service))
                    order += "started"
                    startEntered.countDown()
                    assertTrue(allowStartToFinish.await(2, TimeUnit.SECONDS))
                }
                startAccepted.invoke(service, 1, oldServer, attachment, session, starter)
            } catch (error: Throwable) {
                invocationFailure.set(error)
            }
        }.apply {
            name = "test-airplay-session-start-owner-lock"
            isDaemon = true
            start()
        }
        val swapThread = Thread {
            swapStarted.countDown()
            synchronized(service) {
                service.detach()
                field(service, "active").set(service, AtomicBoolean(true))
                field(service, "attachGeneration").setInt(service, 3)
                field(service, "attachment").set(service, createAttachment(CountingListener()))
                field(service, "serverSocket").set(service, newServer)
                order += "replaced"
            }
            swapFinished.countDown()
        }.apply {
            name = "test-airplay-session-owner-swap"
            isDaemon = true
        }

        try {
            assertTrue(startEntered.await(2, TimeUnit.SECONDS))
            swapThread.start()
            assertTrue(swapStarted.await(2, TimeUnit.SECONDS))
            assertFalse(swapFinished.await(100, TimeUnit.MILLISECONDS))
            allowStartToFinish.countDown()
            acceptThread.join(2_000)
            assertTrue(swapFinished.await(2, TimeUnit.SECONDS))
            swapThread.join(2_000)

            assertFalse(acceptThread.isAlive)
            assertFalse(swapThread.isAlive)
            assertNull(invocationFailure.get())
            assertTrue(session.isClosed)
            assertTrue(order.indexOf("started") < order.indexOf("closed"))
            assertTrue(order.indexOf("closed") < order.indexOf("replaced"))
        } finally {
            allowStartToFinish.countDown()
            runCatching { service.detach() }
            runCatching { oldServer.close() }
            runCatching { newServer.close() }
            runCatching { socket.close() }
            acceptThread.join(2_000)
            swapThread.join(2_000)
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
    fun excessControlConnectionsAreClosedAndReleasedPermitsAllowLaterConnections() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val listener = CountingListener()
        val server = QueuedServerSocket()
        val pairs = List(CarPlayVpnService.MAX_CONTROL_CONNECTIONS + 2) { connectedSocketPair() }
        val permits = field(service, "controlSessionPermits").get(service) as java.util.concurrent.Semaphore
        val acceptLoop = CarPlayVpnService::class.java.getDeclaredMethod(
            "acceptLoop",
            Int::class.javaPrimitiveType,
            ServerSocket::class.java,
        ).apply { isAccessible = true }
        val invocationFailure = AtomicReference<Throwable?>()

        synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 1)
            field(service, "attachment").set(service, createAttachment(listener))
            field(service, "serverSocket").set(service, server)
        }
        val acceptThread = Thread {
            try {
                acceptLoop.invoke(service, 1, server)
            } catch (error: Throwable) {
                invocationFailure.set(error)
            }
        }.apply {
            name = "test-airplay-connection-limit"
            isDaemon = true
            start()
        }

        try {
            pairs.take(CarPlayVpnService.MAX_CONTROL_CONNECTIONS + 1)
                .forEach { (accepted, _) -> server.enqueue(accepted) }
            awaitCondition {
                sessionCount(service) == CarPlayVpnService.MAX_CONTROL_CONNECTIONS &&
                    pairs[CarPlayVpnService.MAX_CONTROL_CONNECTIONS].first.isClosed
            }

            assertEquals(CarPlayVpnService.MAX_CONTROL_CONNECTIONS, sessionCount(service))
            assertEquals(0, permits.availablePermits())
            assertTrue(pairs[CarPlayVpnService.MAX_CONTROL_CONNECTIONS].first.isClosed)
            assertNull(invocationFailure.get())

            sessionSnapshot(service).first().close()
            awaitCondition {
                sessionCount(service) == CarPlayVpnService.MAX_CONTROL_CONNECTIONS - 1 &&
                    permits.availablePermits() == 1
            }
            server.enqueue(pairs.last().first)
            awaitCondition {
                sessionCount(service) == CarPlayVpnService.MAX_CONTROL_CONNECTIONS &&
                    !pairs.last().first.isClosed && permits.availablePermits() == 0
            }

            assertTrue(pairs[CarPlayVpnService.MAX_CONTROL_CONNECTIONS].first.isClosed)
            assertFalse(pairs.last().first.isClosed)
            assertNull(invocationFailure.get())

            service.detach()
            awaitCondition {
                sessionCount(service) == 0 &&
                    permits.availablePermits() == CarPlayVpnService.MAX_CONTROL_CONNECTIONS
            }
        } finally {
            service.detach()
            pairs.forEach { (accepted, client) ->
                runCatching { accepted.close() }
                runCatching { client.close() }
            }
            runCatching { server.close() }
            acceptThread.join(2_000)
        }
    }

    @Test
    fun revokeCleanupDoesNotBlockCallbackOrTearDownANewerAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val active = AtomicBoolean(true)
        val oldListener = CountingListener()
        val newListener = CountingListener()
        val oldServer = ServerSocket()
        val newServer = ServerSocket()
        field(service, "active").set(service, active)
        field(service, "attachGeneration").setInt(service, 5)
        field(service, "attachment").set(service, createAttachment(oldListener))
        field(service, "serverSocket").set(service, oldServer)

        val cleanupThread = synchronized(service) {
            service.onRevoke()
            val worker = awaitThread("carplay-vpn-revoke")
            field(service, "attachGeneration").setInt(service, 6)
            field(service, "attachment").set(service, createAttachment(newListener))
            field(service, "serverSocket").set(service, newServer)
            worker
        }
        cleanupThread.join(2_000)

        assertFalse(cleanupThread.isAlive)
        assertEquals(6, field(service, "attachGeneration").getInt(service))
        assertTrue(active.get())
        assertTrue(service.isAttached())
        assertEquals(0, oldListener.stoppingCalls.get())
        assertEquals(0, oldListener.errorCalls.get())
        assertEquals(0, newListener.stoppingCalls.get())
        assertEquals(0, newListener.errorCalls.get())
        service.detach()
    }

    @Test
    fun staleVpnRevocationsInAnyOrderCannotChangeTheCurrentOwner() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val currentListener = CountingListener()
        val currentAttachment = createAttachment(currentListener)
        val currentServer = ServerSocket()
        val ownerClass = CarPlayVpnService::class.java.declaredClasses
            .single { it.simpleName == "VpnRevocationOwner" }
        val ownerConstructor = ownerClass.declaredConstructors.single().apply { isAccessible = true }
        val releaseRevokedGeneration = CarPlayVpnService::class.java
            .getDeclaredMethod("releaseRevokedGeneration", ownerClass)
            .apply { isAccessible = true }
        val staleOwners = (0 until 1_000).map { generation ->
            ownerConstructor.newInstance(generation, currentAttachment, currentServer)
        }.shuffled(kotlin.random.Random(45))

        synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 1_001)
            field(service, "attachment").set(service, currentAttachment)
            field(service, "serverSocket").set(service, currentServer)
        }
        staleOwners.forEach { owner -> releaseRevokedGeneration.invoke(service, owner) }

        assertTrue(service.isAttached())
        assertEquals(1_001, field(service, "attachGeneration").getInt(service))
        assertEquals(0, currentListener.stoppingCalls.get())
        assertEquals(0, currentListener.errorCalls.get())
        service.detach()
    }

    @Test
    fun oldVpnRevokeCannotCloseTheNewWirelessOwner() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val oldListener = CountingListener()
        val newListener = CountingListener()
        val oldAttachment = createAttachment(oldListener)
        val oldServer = ServerSocket()
        val newAttachment = createAttachment(newListener, usesVpn = false)
        val newServer = ServerSocket()
        val ownerClass = CarPlayVpnService::class.java.declaredClasses
            .single { it.simpleName == "VpnRevocationOwner" }
        val owner = ownerClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(11, oldAttachment, oldServer)
        val releaseRevokedGeneration = CarPlayVpnService::class.java
            .getDeclaredMethod("releaseRevokedGeneration", ownerClass)
            .apply { isAccessible = true }

        synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 12)
            field(service, "attachment").set(service, newAttachment)
            field(service, "serverSocket").set(service, newServer)
        }
        releaseRevokedGeneration.invoke(service, owner)
        service.onRevoke()

        assertTrue(service.isAttached())
        assertEquals(12, field(service, "attachGeneration").getInt(service))
        assertFalse(oldServer.isClosed)
        assertFalse(newServer.isClosed)
        assertEquals(0, oldListener.stoppingCalls.get())
        assertEquals(0, oldListener.errorCalls.get())
        assertEquals(0, newListener.stoppingCalls.get())
        assertEquals(0, newListener.errorCalls.get())
        service.detach()
        oldServer.close()
    }

    @Test
    fun oldTransportErrorCannotNotifyTheNewAttachmentAfterItsWorkerIsQueued() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val oldListener = CountingListener()
        val newListener = CountingListener()
        val oldAttachment = createAttachment(oldListener)
        val newAttachment = createAttachment(newListener)
        val oldServer = ServerSocket()
        val newServer = ServerSocket()
        val onTransportError = CarPlayVpnService::class.java.getDeclaredMethod(
            "onTransportError",
            Int::class.javaPrimitiveType,
            AirPlaySessionListener::class.java,
            Throwable::class.java,
        ).apply { isAccessible = true }
        val teardownThread = synchronized(service) {
            field(service, "active").set(service, AtomicBoolean(true))
            field(service, "attachGeneration").setInt(service, 20)
            field(service, "attachment").set(service, oldAttachment)
            field(service, "serverSocket").set(service, oldServer)
            onTransportError.invoke(service, 20, oldListener, IOException("stale transport error"))
            val worker = awaitThread("airplay-teardown")
            field(service, "attachGeneration").setInt(service, 21)
            field(service, "attachment").set(service, newAttachment)
            field(service, "serverSocket").set(service, newServer)
            worker
        }
        teardownThread.join(2_000)

        assertFalse(teardownThread.isAlive)
        assertTrue(service.isAttached())
        assertEquals(21, field(service, "attachGeneration").getInt(service))
        assertEquals(0, oldListener.stoppingCalls.get())
        assertEquals(0, oldListener.errorCalls.get())
        assertEquals(0, newListener.stoppingCalls.get())
        assertEquals(0, newListener.errorCalls.get())
        service.detach()
        oldServer.close()
    }

    @Test
    fun revokeStopsAndNotifiesTheCurrentAttachment() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val active = AtomicBoolean(true)
        val listener = CountingListener()
        val server = ServerSocket()
        field(service, "active").set(service, active)
        field(service, "attachGeneration").setInt(service, 7)
        field(service, "attachment").set(service, createAttachment(listener))
        field(service, "serverSocket").set(service, server)

        service.onRevoke()

        assertTrue(listener.transportErrorDelivered.await(2, TimeUnit.SECONDS))
        assertFalse(active.get())
        assertEquals(8, field(service, "attachGeneration").getInt(service))
        assertTrue(server.isClosed)
        assertEquals(1, listener.stoppingCalls.get())
        assertEquals(1, listener.errorCalls.get())
    }

    @Test
    fun revokeDoesNotDeliverAnOldErrorAfterItsStoppingCallbackInstallsANewOwner() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val active = AtomicBoolean(true)
        val newServer = ServerSocket()
        val newListener = CountingListener()
        val newAttachment = createAttachment(newListener, usesVpn = false)
        val oldListener = CountingListener(onStopping = {
            synchronized(service) {
                field(service, "attachGeneration").setInt(service, 9)
                active.set(true)
                field(service, "attachment").set(service, newAttachment)
                field(service, "serverSocket").set(service, newServer)
            }
        })
        val oldServer = ServerSocket()
        field(service, "active").set(service, active)
        field(service, "attachGeneration").setInt(service, 8)
        field(service, "attachment").set(service, createAttachment(oldListener))
        field(service, "serverSocket").set(service, oldServer)

        service.onRevoke()

        assertTrue(oldListener.stoppingDelivered.await(2, TimeUnit.SECONDS))
        awaitCondition { !awaitThreadExists("carplay-vpn-revoke") }
        assertTrue(service.isAttached())
        assertEquals(9, field(service, "attachGeneration").getInt(service))
        assertEquals(1, oldListener.stoppingCalls.get())
        assertEquals(0, oldListener.errorCalls.get())
        assertEquals(0, newListener.stoppingCalls.get())
        assertEquals(0, newListener.errorCalls.get())
        service.detach()
    }

    @Test
    fun serverBindFailureClosesTheLocallyOwnedSocket() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val server = TrackingServerSocket(bindFailure = IOException("injected bind failure"))
        service.serverStartupHooks = AirPlayServerStartupHooks(createServerSocket = { server })

        val result = attachWireless(service)

        assertTrue(result is CarPlayVpnService.AttachResult.Failed)
        assertEquals(1, server.closeCalls.get())
        assertFalse(service.isAttached())
        assertNull(field(service, "attachment").get(service))
        assertNull(field(service, "serverSocket").get(service))
    }

    @Test
    fun listenerPreparationFailureClosesTheLocallyOwnedSocket() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val server = TrackingServerSocket()
        service.serverStartupHooks = AirPlayServerStartupHooks(
            createServerSocket = { server },
            prepareListener = { _, _, _ -> throw IOException("injected listener diagnostics failure") },
        )

        val result = attachWireless(service)

        assertTrue(result is CarPlayVpnService.AttachResult.Failed)
        assertEquals(1, server.closeCalls.get())
        assertFalse(service.isAttached())
        assertNull(field(service, "attachment").get(service))
        assertNull(field(service, "serverSocket").get(service))
    }

    @Test
    fun acceptThreadStartFailureClosesTheServiceOwnedSocketExactlyOnce() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val server = TrackingServerSocket()
        service.serverStartupHooks = AirPlayServerStartupHooks(
            createServerSocket = { server },
            startAcceptThread = { throw IllegalStateException("injected accept start failure") },
        )

        val result = attachWireless(service)

        assertTrue(result is CarPlayVpnService.AttachResult.Failed)
        assertEquals(1, server.closeCalls.get())
        assertFalse(service.isAttached())
        assertNull(field(service, "attachment").get(service))
        assertNull(field(service, "serverSocket").get(service))
    }

    @Test
    fun serviceDestroyClosesItsListenerAndSessionsExactlyOnce() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val server = TrackingServerSocket()
        val acceptThread = AtomicReference<Thread?>()
        val sessionClosed = AtomicInteger()
        service.serverStartupHooks = AirPlayServerStartupHooks(
            createServerSocket = { server },
            startAcceptThread = { thread ->
                acceptThread.set(thread)
                thread.start()
            },
        )

        val result = attachWireless(service)
        assertTrue(result is CarPlayVpnService.AttachResult.Started)
        val attachment = field(service, "attachment").get(service) ?: error("attachment not installed")
        val session = createSession(
            socket = connectedSocket(),
            attachment = attachment,
            media = object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    sessionClosed.incrementAndGet()
                }
            },
        )
        @Suppress("UNCHECKED_CAST")
        (field(service, "sessions").get(service) as MutableSet<AirPlaySession>).add(session)

        service.onDestroy()
        acceptThread.get()?.join(2_000)

        assertFalse(acceptThread.get()?.isAlive ?: true)
        assertTrue(server.isClosed)
        assertEquals(1, server.closeCalls.get())
        assertTrue(session.isClosed)
        assertEquals(1, sessionClosed.get())
        assertFalse(service.isAttached())
        assertNull(field(service, "attachment").get(service))
        assertNull(field(service, "serverSocket").get(service))

        service.detach()
        assertEquals(1, server.closeCalls.get())
        assertEquals(1, sessionClosed.get())
    }

    private fun createAttachment(listener: AirPlaySessionListener, usesVpn: Boolean = true): Any {
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
            usesVpn,
        )
    }

    private fun createSession(
        socket: Socket,
        attachment: Any,
        media: AirPlayMediaHandler,
    ): AirPlaySession = AirPlaySession(
        socket = socket,
        config = field(attachment, "config").get(attachment) as AirPlayConfig,
        identity = field(attachment, "identity").get(attachment) as AirPlayIdentity,
        pairings = field(attachment, "pairings").get(attachment) as PairingStore,
        mfi = null,
        listener = field(attachment, "listener").get(attachment) as AirPlaySessionListener,
        media = media,
        wiredPathDiagnostics = null,
        attachGeneration = 1L,
    )

    private fun attachWireless(service: CarPlayVpnService): CarPlayVpnService.AttachResult = service.attachWireless(
        bindAddress = InetAddress.getByName("127.0.0.1"),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            port = 0,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = CountingListener(),
        media = object : AirPlayMediaHandler {},
    )

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

    private fun connectedSocketPair(): Pair<Socket, Socket> {
        val listener = ServerSocket(0)
        return try {
            val client = Socket("127.0.0.1", listener.localPort)
            listener.accept() to client
        } finally {
            listener.close()
        }
    }

    private fun sessionCount(service: CarPlayVpnService): Int = sessionSnapshot(service).size

    private fun sessionSnapshot(service: CarPlayVpnService): List<AirPlaySession> {
        val lock = field(service, "sessionsLock").get(service) ?: error("sessionsLock was null")
        return synchronized(lock) {
            (field(service, "sessions").get(service) as Set<*>).filterIsInstance<AirPlaySession>()
        }
    }

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

    private fun awaitThreadExists(name: String): Boolean = Thread.getAllStackTraces().keys.any { it.name == name }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Condition did not become true")
    }

    private class QueuedServerSocket : ServerSocket() {
        private val queued = LinkedBlockingQueue<Socket>()
        @Volatile private var accepting = true

        override fun accept(): Socket {
            while (accepting) {
                try {
                    queued.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SocketException("test accept interrupted")
                }
            }
            throw SocketException("test accept closed")
        }

        fun enqueue(socket: Socket) {
            queued.add(socket)
        }

        override fun close() {
            accepting = false
            super.close()
        }
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

    private class TrackingServerSocket(
        private val bindFailure: IOException? = null,
    ) : ServerSocket() {
        val closeCalls = AtomicInteger()

        override fun bind(endpoint: SocketAddress?) {
            bindFailure?.let { throw it }
            super.bind(endpoint)
        }

        override fun close() {
            closeCalls.incrementAndGet()
            super.close()
        }
    }

    private class CountingListener(
        private val throwOnStage: ConnectionTraceStage? = null,
        private val onStage: (ConnectionTraceStage) -> Unit = {},
        private val onStopping: () -> Unit = {},
    ) : AirPlaySessionListener {
        val stages = mutableListOf<ConnectionTraceStage>()
        val stoppingCalls = AtomicInteger()
        val errorCalls = AtomicInteger()
        val stoppingDelivered = CountDownLatch(1)
        val transportErrorDelivered = CountDownLatch(1)

        override fun onConnectionTrace(stage: ConnectionTraceStage) {
            stages += stage
            onStage(stage)
            if (stage == throwOnStage) throw IllegalStateException("test callback failure")
        }

        override fun onTransportStopping(message: String) {
            stoppingCalls.incrementAndGet()
            stoppingDelivered.countDown()
            onStopping()
        }

        override fun onTransportError(message: String) {
            errorCalls.incrementAndGet()
            transportErrorDelivered.countDown()
        }
    }
}
