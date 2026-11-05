package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmLinkPhase
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.WiredPathDiagnostics
import com.shilapi.xcertplay.transport.WiredPathEvent
import com.shilapi.xcertplay.transport.WiredPacketClassification
import com.shilapi.xcertplay.transport.WiredStartupTiming
import com.shilapi.xcertplay.transport.WiredStartupTimingEvent
import com.shilapi.xcertplay.transport.WiredTcpPacketClassifier
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onError: (Throwable) -> Unit,
    private val onBridgeStarted: () -> Unit = {},
    private val wiredPathDiagnostics: WiredPathDiagnostics? = null,
    private val wiredStartupTiming: WiredStartupTiming? = null,
    expectedAirPlayAddress: ByteArray? = null,
    private val airPlayPort: Int = -1,
    private val attachGeneration: Long? = null,
) : Closeable {
    private val wiredDiagnostics = wiredPathDiagnostics?.takeIf { it.isEnabled }
    private val expectedAirPlayAddress = expectedAirPlayAddress?.copyOf()
    @Volatile internal var tunInputStreamForTest: InputStream? = null
    @Volatile internal var tunOutputStreamForTest: OutputStream? = null
    @Volatile internal var closeTunForTest: (() -> Unit)? = null

    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var ncmClosed = false
    @Volatile private var tunClosed = false
    @Volatile private var ncmToTunThread: Thread? = null
    @Volatile private var tunToNcmThread: Thread? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "bridge is closed" }
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        try {
            ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
                isDaemon = true
                start()
            }
            tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
                isDaemon = true
                start()
            }
            onBridgeStarted()
        } catch (error: RuntimeException) {
            runCatching { close() }
            throw error
        }
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        running.set(false)
        var closeFailure: Exception? = null
        if (!ncmClosed) {
            try {
                ncm.close()
                ncmClosed = true
            } catch (error: Exception) {
                closeFailure = error
            }
        }
        if (!tunClosed) {
            try {
                closeTunForTest?.invoke() ?: tun.close()
                tunClosed = true
            } catch (error: Exception) {
                if (closeFailure == null) closeFailure = error else closeFailure.addSuppressed(error)
            }
        }
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_JOIN_TIMEOUT_MILLIS)
        val incomingStopped = join(ncmToTunThread, remainingMillis(deadlineNs))
        val outgoingStopped = join(tunToNcmThread, remainingMillis(deadlineNs))
        if (!incomingStopped) ncmToTunThread?.interrupt()
        if (!outgoingStopped) tunToNcmThread?.interrupt()
        peerMac = null
        closeFailure?.let { throw it }
    }

    fun awaitStopped(timeoutMillis: Long): Boolean {
        if (!closed.get() || !ncmClosed || !tunClosed) return false
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
        val incomingStopped = join(ncmToTunThread, remainingMillis(deadlineNs))
        val outgoingStopped = join(tunToNcmThread, remainingMillis(deadlineNs))
        return incomingStopped && outgoingStopped && ncmClosed && tunClosed
    }

    internal val isClosedForTest: Boolean
        get() = closed.get()

    internal val isRunningForTest: Boolean
        get() = running.get()

    val isFullyClosed: Boolean
        get() = isStoppedForTest

    internal val isStoppedForTest: Boolean
        get() = closed.get() && ncmClosed && tunClosed &&
            ncmToTunThread?.isAlive != true && tunToNcmThread?.isAlive != true

    private fun runNcmToTun() {
        val output = tunOutputStreamForTest ?: FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                var synToAirPlay = false
                var synFamily = WiredPacketClassification.FAMILY_UNKNOWN
                if (wiredDiagnostics != null ||
                    wiredStartupTiming?.has(WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN) == false
                ) {
                    val classification = WiredTcpPacketClassifier.classifyEthernet(
                        frame,
                        expectedAddress = expectedAirPlayAddress,
                        expectedPort = airPlayPort,
                    )
                    if (attachGeneration != null) {
                        wiredStartupTiming?.recordAirPlaySyn(classification, attachGeneration)
                    }
                    if (wiredDiagnostics != null) recordPacketClassification(classification)
                    synToAirPlay = classification.tcpSyn && classification.portMatches
                    if (synToAirPlay) {
                        synFamily = classification.family
                        when (classification.family) {
                            WiredPacketClassification.FAMILY_IPV4 ->
                                wiredDiagnostics?.record(WiredPathEvent.V4_SYN_TO_AIRPLAY, attachGeneration)
                            WiredPacketClassification.FAMILY_IPV6 -> {
                                wiredDiagnostics?.record(WiredPathEvent.V6_SYN_TO_AIRPLAY, attachGeneration)
                                val destinationEvent = when {
                                    classification.addressChecked && classification.addressMatches ->
                                        WiredPathEvent.V6_SYN_DEST_MATCH
                                    classification.addressChecked -> WiredPathEvent.V6_SYN_DEST_MISMATCH
                                    else -> WiredPathEvent.V6_SYN_DEST_UNVERIFIED
                                }
                                wiredDiagnostics?.record(destinationEvent, attachGeneration)
                            }
                            else -> Unit
                        }
                    }
                }
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac
                ncm.recordPeerMacLearned()
                try {
                    output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
                    if (synToAirPlay && synFamily == WiredPacketClassification.FAMILY_IPV6) {
                        wiredDiagnostics?.record(WiredPathEvent.SYN_TUN_WRITE_SUCCESS, attachGeneration)
                    }
                } catch (error: Exception) {
                    if (synToAirPlay && synFamily == WiredPacketClassification.FAMILY_IPV6) {
                        wiredDiagnostics?.record(WiredPathEvent.SYN_TUN_WRITE_FAILURE, attachGeneration)
                    }
                    throw error
                }
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = tunInputStreamForTest ?: FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                var synAckFromTun = false
                var rstFromTun = false
                val timingNeedsSynAck = wiredStartupTiming != null &&
                    !wiredStartupTiming.has(WiredStartupTimingEvent.FIRST_SYN_ACK_OUT)
                if (wiredDiagnostics != null || timingNeedsSynAck) {
                    val classification = WiredTcpPacketClassifier.classifyRawIp(
                        buffer,
                        0,
                        length,
                        expectedAddress = expectedAirPlayAddress,
                        matchSourceAddress = true,
                        expectedPort = airPlayPort,
                        matchSourcePort = true,
                    )
                    synAckFromTun = classification.tcpSynAck && classification.portMatches
                    rstFromTun = classification.tcpRst && classification.portMatches
                    if (synAckFromTun && attachGeneration != null) {
                        wiredStartupTiming?.recordSynAckFromTun(classification, attachGeneration)
                    }
                    if (wiredDiagnostics != null) recordPacketClassification(classification)
                    if (synAckFromTun) {
                        wiredDiagnostics?.record(WiredPathEvent.TCP_SYN_ACK_FROM_TUN, attachGeneration)
                    }
                    if (rstFromTun) {
                        wiredDiagnostics?.record(WiredPathEvent.TCP_RST_FROM_TUN, attachGeneration)
                    }
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (synAckFromTun) wiredDiagnostics?.record(WiredPathEvent.PEER_MAC_DROP, attachGeneration)
                    continue
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                val ndp = EthernetIpv6Codec.isNeighborResolution(ipv6)
                val linkPhase = ncm.linkPhase()
                val linkReady = linkPhase == NcmLinkPhase.NCM_LINK_READY
                val result = NcmStartupNdpRetry.send(
                    startupNeighborDiscovery = ndp &&
                        linkPhase != NcmLinkPhase.PRE_CARPLAY_START && !linkReady,
                    linkReady = linkReady,
                    preReadyOutTimeoutMillis = ncm.diagnosticProfile.preReadyOutTimeoutMillis,
                    isActive = running::get,
                    sendOnce = { timeoutMillis -> ncm.send(frame, timeoutMillis) },
                    pause = { delayMillis -> LockSupport.parkNanos(delayMillis * NANOS_PER_MILLISECOND) },
                )
                if (synAckFromTun) {
                    if (attachGeneration != null) {
                        wiredStartupTiming?.recordSynAckOut(
                            sent = result == NcmSendResult.Sent,
                            callbackGeneration = attachGeneration,
                        )
                    }
                    wiredDiagnostics?.record(
                        when (result) {
                            NcmSendResult.Sent -> WiredPathEvent.SYN_ACK_NCM_SENT
                            NcmSendResult.NotReady -> WiredPathEvent.SYN_ACK_NCM_NOT_READY
                            is NcmSendResult.Failed -> WiredPathEvent.SYN_ACK_NCM_FAILED
                        },
                        attachGeneration,
                    )
                }
                if (result is NcmSendResult.Failed) {
                    throw result.error as? Exception ?: IOException("NCM send failed")
                }
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun join(thread: Thread?, timeoutMillis: Long): Boolean {
        if (thread == null) return true
        if (thread === Thread.currentThread()) return false
        if (!thread.isAlive || timeoutMillis <= 0L) return !thread.isAlive
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return !thread.isAlive
    }

    private fun remainingMillis(deadlineNs: Long): Long {
        val remainingNs = deadlineNs - System.nanoTime()
        return if (remainingNs <= 0L) 0L else (remainingNs - 1L) / NANOS_PER_MILLISECOND + 1L
    }

    private fun recordPacketClassification(classification: WiredPacketClassification) {
        val event = when (classification.disposition) {
            WiredPacketClassification.DISPOSITION_INVALID -> WiredPathEvent.INVALID_PACKET
            WiredPacketClassification.DISPOSITION_UNSUPPORTED -> WiredPathEvent.UNSUPPORTED_PACKET
            WiredPacketClassification.DISPOSITION_UNKNOWN -> WiredPathEvent.UNKNOWN_PACKET
            else -> null
        }
        if (event != null) wiredDiagnostics?.record(event, attachGeneration)
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val CLOSE_JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
