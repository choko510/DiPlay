package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmLinkPhase
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac
                ncm.recordPeerMacLearned()
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
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
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) continue
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
