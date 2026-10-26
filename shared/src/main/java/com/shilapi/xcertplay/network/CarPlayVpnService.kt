package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.DiagnosticRedactor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.WiredPathDiagnostics
import com.shilapi.xcertplay.transport.WiredPathEvent
import com.shilapi.xcertplay.transport.WiredStartupTiming
import com.shilapi.xcertplay.transport.WiredStartupTimingEvent
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal class AirPlayServerStartupHooks(
    val createServerSocket: () -> ServerSocket = { ServerSocket() },
    val prepareListener: (WiredPathDiagnostics?, Long, ServerSocket) -> Unit = { diagnostics, generation, _ ->
        diagnostics?.record(WiredPathEvent.LISTENER_BOUND, generation)
    },
    val startAcceptThread: (Thread) -> Unit = { it.start() },
)

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val wiredPathDiagnostics: WiredPathDiagnostics?,
        val usesVpn: Boolean,
        val wiredStartupTiming: WiredStartupTiming?,
    )

    private data class VpnRevocationOwner(
        val generation: Int,
        val attachment: AirPlayAttachment,
        val serverSocket: ServerSocket?,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    private val controlSessionPermits = Semaphore(MAX_CONTROL_CONNECTIONS, true)
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var attachGeneration = 0
    internal var serverStartupHooks = AirPlayServerStartupHooks()

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == VpnService.SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onRevoke() {
        val revokedOwner = synchronized(this) {
            val current = attachment ?: return
            if (!active.get() || !current.usesVpn) return
            VpnRevocationOwner(attachGeneration, current, serverSocket)
        }
        Thread(
            { releaseRevokedGeneration(revokedOwner) },
            "carplay-vpn-revoke",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseRevokedGeneration(owner: VpnRevocationOwner) {
        synchronized(this) {
            if (
                owner.generation != attachGeneration || attachment !== owner.attachment ||
                serverSocket !== owner.serverSocket || !active.get() || !owner.attachment.usesVpn
            ) {
                return
            }
            releaseLocked()
            val releasedGeneration = attachGeneration
            val message = "CarPlay VPN permission was revoked"
            try {
                owner.attachment.listener.onTransportStopping(message)
            } catch (error: Exception) {
                Log.w(TAG, "VPN revoke stopping callback failed error=${error.javaClass.simpleName}")
            }
            if (attachGeneration != releasedGeneration || active.get()) return
            try {
                owner.attachment.listener.onTransportError(message)
            } catch (error: Exception) {
                Log.w(TAG, "VPN revoke error callback failed error=${error.javaClass.simpleName}")
            }
            if (attachGeneration == releasedGeneration && !active.get()) {
                stopSelf()
            }
        }
    }

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        wiredPathDiagnostics: WiredPathDiagnostics? = null,
        wiredStartupTiming: WiredStartupTiming? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        wiredPathDiagnostics?.recordAttachGeneration(generation.toLong())
        wiredStartupTiming?.recordAttachGeneration(generation.toLong())
        ncm.setWiredPathAttachGeneration(generation.toLong())
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            wiredPathDiagnostics?.record(WiredPathEvent.VPN_ESTABLISHED, generation.toLong())

            val ipv6Bridge = Ipv6NcmBridge(
                ncm = ncm,
                tun = tunFd,
                hostMac = hostMac,
                wiredPathDiagnostics = wiredPathDiagnostics,
                wiredStartupTiming = wiredStartupTiming,
                expectedAirPlayAddress = address.address,
                airPlayPort = config.port,
                attachGeneration = generation.toLong(),
                onError = { error -> onTransportError(generation, listener, error) },
                onBridgeStarted = {
                    // This marks worker startup, not a live NCM link.
                    listener.onConnectionTrace(ConnectionTraceStage.NCM_BRIDGE_STARTED)
                },
            )
            bridge = ipv6Bridge
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    address,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                    wiredPathDiagnostics,
                    usesVpn = true,
                    wiredStartupTiming = wiredStartupTiming,
                ),
            )
            ipv6Bridge.start()
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    bindAddress,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                    null,
                    usesVpn = false,
                    wiredStartupTiming = null,
                ),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val server = serverStartupHooks.createServerSocket()
        var ownershipTransferred = false
        try {
            server.bind(InetSocketAddress(replacement.address, replacement.config.port))
            serverStartupHooks.prepareListener(replacement.wiredPathDiagnostics, generation.toLong(), server)
            val acceptThread = Thread(
                { acceptLoop(generation, server) },
                "airplay-accept",
            ).apply { isDaemon = true }
            attachment = replacement
            serverSocket = server
            ownershipTransferred = true
            serverStartupHooks.startAcceptThread(acceptThread)
        } finally {
            if (!ownershipTransferred) {
                runCatching { server.close() }
                    .onFailure { error ->
                        Log.w(TAG, "unowned AirPlay server close failed error=${error.javaClass.simpleName}")
                    }
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val current = synchronized(this) {
                    if (!active.get() || generation != attachGeneration || server !== serverSocket) {
                        runCatching { socket.close() }
                        return
                    }
                    attachment ?: run {
                        runCatching { socket.close() }
                        return
                    }
                }
                current.wiredStartupTiming?.record(
                    WiredStartupTimingEvent.LISTENER_ACCEPT,
                    generation.toLong(),
                )
                if (!controlSessionPermits.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                val permitReleased = AtomicBoolean(false)
                fun releasePermit() {
                    if (permitReleased.compareAndSet(false, true)) controlSessionPermits.release()
                }
                var permitTransferredToSession = false
                var session: AirPlaySession? = null
                try {
                    Log.i(
                        TAG,
                        DiagnosticRedactor.redactForLogcat(
                            "airplay connection accepted from ${socket.remoteSocketAddress}",
                        ),
                    )
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.setSoLinger(true, 0)
                    current.wiredPathDiagnostics?.record(
                        WiredPathEvent.LISTENER_ACCEPTED,
                        generation.toLong(),
                    )
                    current.listener.onConnectionTrace(ConnectionTraceStage.AIRPLAY_CONTROL_ACCEPTED)
                    session = AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                releasePermit()
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                        wiredPathDiagnostics = current.wiredPathDiagnostics,
                        wiredStartupTiming = current.wiredStartupTiming,
                        attachGeneration = generation.toLong(),
                    )
                    val accepted = startAcceptedSessionIfCurrent(
                        generation = generation,
                        server = server,
                        expectedAttachment = current,
                        session = session,
                        startSession = AirPlaySession::start,
                    )
                    if (!accepted) {
                        session.close()
                        return
                    }
                    permitTransferredToSession = true
                } catch (error: Exception) {
                    session?.let { acceptedSession ->
                        removeSession(acceptedSession)
                        runCatching { acceptedSession.close() }
                    } ?: runCatching { socket.close() }
                    onTransportError(generation, current.listener, error)
                    return
                } finally {
                    if (!permitTransferredToSession) releasePermit()
                }
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun startAcceptedSessionIfCurrent(
        generation: Int,
        server: ServerSocket,
        expectedAttachment: AirPlayAttachment,
        session: AirPlaySession,
        startSession: (AirPlaySession) -> Unit,
    ): Boolean = synchronized(this) {
        if (
            !active.get() || generation != attachGeneration || server !== serverSocket ||
            attachment !== expectedAttachment
        ) {
            false
        } else {
            addSession(session)
            startSession(session)
            true
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed error=${error.javaClass.simpleName}")
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(
            TAG,
            "CarPlay transport stopped: ${DiagnosticRedactor.redactForLogcat(message)} " +
                "error=${error.javaClass.simpleName}",
        )
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration || attachment?.listener !== listener || !active.get()) {
                        return@synchronized
                    }
                    runCatching { listener.onTransportStopping(message) }
                    if (
                        generation != attachGeneration || attachment?.listener !== listener || !active.get()
                    ) {
                        return@synchronized
                    }
                    releaseLocked()
                    try {
                        listener.onTransportError(message)
                    } catch (error: Exception) {
                        Log.w(TAG, "AirPlay transport error callback failed error=${error.javaClass.simpleName}")
                    }
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.let { server ->
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "AirPlay server teardown failed", it) }
        }
        serverSocket = null
        closeSessionsLocked()
        bridge?.let { activeBridge ->
            runCatching { activeBridge.close() }
                .onFailure { Log.w(TAG, "NCM/VPN bridge teardown failed", it) }
        }
        bridge = null
        tun?.let { activeTun ->
            runCatching { activeTun.close() }
                .onFailure { Log.w(TAG, "VPN descriptor teardown failed", it) }
        }
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        internal const val MAX_CONTROL_CONNECTIONS = 4
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = EthernetIpv6Codec.IPV6_MTU_BYTES

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
