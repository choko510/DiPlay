package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.diagnostics.WiredPathDiagnostics
import com.shilapi.xcertplay.diagnostics.WiredPathEvent
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmLinkPhase
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

internal fun writeNcmFrameToTun(
    frame: ByteArray,
    output: OutputStream,
    diagnostics: WiredPathDiagnostics?,
    onPeerMacLearned: (ByteArray) -> Unit = {},
) {
    val observation = diagnostics?.observeEthernetFrame(frame, frame.size)
        ?: WiredTcpPacketClassification.EMPTY
    val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: return
    onPeerMacLearned(ipv6.sourceMac)
    try {
        output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
        diagnostics?.recordTunWriteResult(observation, succeeded = true)
    } catch (error: Exception) {
        diagnostics?.recordTunWriteResult(observation, succeeded = false)
        throw error
    }
}

internal fun recordSynAckNcmResult(
    observation: WiredTcpPacketClassification,
    diagnostics: WiredPathDiagnostics?,
    result: NcmSendResult,
) {
    if (diagnostics == null || !observation.isAirPlaySynAck(diagnostics.listenerPort)) return
    val event = when (result) {
        NcmSendResult.Sent -> WiredPathEvent.SYN_ACK_NCM_SENT
        NcmSendResult.NotReady -> WiredPathEvent.SYN_ACK_NCM_NOT_READY
        is NcmSendResult.Failed -> WiredPathEvent.SYN_ACK_NCM_FAILED
    }
    diagnostics.record(event)
}

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
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            start()
        }
        onBridgeStarted()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try {
            ncm.close()
        } finally {
            try {
                tun.close()
            } finally {
                join(ncmToTunThread)
                join(tunToNcmThread)
            }
        }
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        val onPeerMacLearned: (ByteArray) -> Unit = { mac ->
            peerMac = mac
            ncm.recordPeerMacLearned()
        }
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                writeNcmFrameToTun(frame, output, wiredPathDiagnostics, onPeerMacLearned)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
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
                val diagnostics = wiredPathDiagnostics
                val observation = if (diagnostics == null) {
                    WiredTcpPacketClassification.EMPTY
                } else {
                    diagnostics.observeTunPacket(buffer, length)
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    diagnostics?.record(WiredPathEvent.PEER_MAC_MISSING_DROP)
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
                recordSynAckNcmResult(observation, diagnostics, result)
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

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
