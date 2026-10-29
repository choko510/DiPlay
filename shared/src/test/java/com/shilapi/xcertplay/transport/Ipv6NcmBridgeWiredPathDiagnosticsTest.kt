package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import com.shilapi.xcertplay.step4.UsbRequestWaitFixture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    shadows = [ScriptedUsbEndpointShadow::class, ScriptedUsbDeviceConnectionShadow::class, ScriptedUsbRequestShadow::class],
)
class Ipv6NcmBridgeWiredPathDiagnosticsTest {
    @Test
    fun closeBeforeStartClosesTheOwnedNcmAndTunExactlyOnce() {
        val (ncm, _) = newBridge(newDiagnostics())
        val tunFd = ParcelFileDescriptor.createPipe().also { it[1].close() }[0]
        var tunCloseCount = 0
        val bridge = Ipv6NcmBridge(ncm, tunFd, HOST_MAC, onError = {})
        bridge.closeTunForTest = {
            tunCloseCount++
            tunFd.close()
        }

        bridge.close()
        bridge.close()

        assertTrue(bridge.isClosedForTest)
        assertFalse(bridge.isRunningForTest)
        assertEquals(1, tunCloseCount)
        assertTrue(ReflectionHelpers.getField<Boolean>(ncm, "closed"))
    }

    @Test
    fun tunSynAckWithoutPeerMacIsObservedAndDroppedWithoutUsbOut() {
        val diagnostics = newDiagnostics()
        val ncm = newBridge(diagnostics).first
        val tunFd = ParcelFileDescriptor.createPipe().also { it[1].close() }[0]
        val transportError = AtomicReference<Throwable?>(null)
        val bridge = Ipv6NcmBridge(
            ncm = ncm,
            tun = tunFd,
            hostMac = HOST_MAC,
            onError = transportError::set,
            wiredPathDiagnostics = diagnostics,
            expectedAirPlayAddress = LOCAL_ADDRESS,
            airPlayPort = AIRPLAY_PORT,
            attachGeneration = ATTACH_GENERATION,
        )
        val fakeTun = connectFakeTun(bridge, tunFd)

        try {
            bridge.start()
            fakeTun.inputToBridge.write(ipv6Tcp(flags = TCP_SYN or TCP_ACK, sourcePort = AIRPLAY_PORT))
            fakeTun.inputToBridge.flush()

            assertTrue(waitFor { count(diagnostics, WiredPathEvent.PEER_MAC_DROP) == 1L })
            assertEquals(1L, count(diagnostics, WiredPathEvent.TCP_SYN_ACK_FROM_TUN))
            assertEquals(0L, ncm.diagnosticSnapshot().writeAttempts)
            assertEquals(null, transportError.get())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun synAckBulkOutNotReadyIsRecordedAsOneAttempt() {
        val diagnostics = newDiagnostics()
        val (ncm, script) = newBridge(diagnostics)
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Ntb16Codec.build(ethernetIpv6(tcpFlags = TCP_SYN), sequence = 1),
        ))
        script.enqueueBulkOutResult(0)
        val tunFd = ParcelFileDescriptor.createPipe().also { it[1].close() }[0]
        val transportError = AtomicReference<Throwable?>(null)
        val bridge = Ipv6NcmBridge(
            ncm = ncm,
            tun = tunFd,
            hostMac = HOST_MAC,
            onError = transportError::set,
            wiredPathDiagnostics = diagnostics,
            expectedAirPlayAddress = LOCAL_ADDRESS,
            airPlayPort = AIRPLAY_PORT,
            attachGeneration = ATTACH_GENERATION,
        )
        val fakeTun = connectFakeTun(bridge, tunFd)

        try {
            bridge.start()
            assertTrue(waitFor { count(diagnostics, WiredPathEvent.SYN_TUN_WRITE_SUCCESS) == 1L })
            fakeTun.inputToBridge.write(ipv6Tcp(flags = TCP_SYN or TCP_ACK, sourcePort = AIRPLAY_PORT))
            fakeTun.inputToBridge.flush()

            assertTrue(waitFor { count(diagnostics, WiredPathEvent.SYN_ACK_NCM_NOT_READY) == 1L })
            assertEquals(0L, count(diagnostics, WiredPathEvent.SYN_ACK_NCM_SENT))
            assertEquals(1L, ncm.diagnosticSnapshot().writeAttempts)
            assertEquals(null, transportError.get())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun failedTunWriteIsRecordedForTheObservedSyn() {
        val diagnostics = newDiagnostics()
        val (ncm, script) = newBridge(diagnostics)
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Ntb16Codec.build(ethernetIpv6(tcpFlags = TCP_SYN), sequence = 2),
        ))
        val tunFd = ParcelFileDescriptor.createPipe().also { it[1].close() }[0]
        val bridge = Ipv6NcmBridge(
            ncm = ncm,
            tun = tunFd,
            hostMac = HOST_MAC,
            onError = {},
            wiredPathDiagnostics = diagnostics,
            expectedAirPlayAddress = LOCAL_ADDRESS,
            airPlayPort = AIRPLAY_PORT,
            attachGeneration = ATTACH_GENERATION,
        )

        val fakeTun = connectFakeTun(
            bridge,
            tunFd,
            outputStream = object : OutputStream() {
                override fun write(value: Int) = throw IOException("fixture TUN write failed")
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    throw IOException("fixture TUN write failed")
            },
        )
        fakeTun.inputToBridge.close()
        bridge.start()
        try {
            assertTrue(waitFor { count(diagnostics, WiredPathEvent.SYN_TUN_WRITE_FAILURE) == 1L })
            assertEquals(1L, count(diagnostics, WiredPathEvent.V6_SYN_TO_AIRPLAY))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun stage3TimingRunsWithDebugDiagnosticsOffAndPreservesTunPacketBytes() {
        val disabledDiagnostics = WiredPathDiagnostics("debug-off", 2, enabled = false)
        val timing = WiredStartupTiming(attemptId = 2L).also {
            it.recordAttachGeneration(ATTACH_GENERATION)
        }
        val (ncm, script) = newBridge(disabledDiagnostics, timing)
        val frame = ethernetIpv6(tcpFlags = TCP_SYN)
        script.enqueueRead(UsbRequestWaitFixture.Completed(Ntb16Codec.build(frame, sequence = 3)))
        val tunFd = ParcelFileDescriptor.createPipe().also { it[1].close() }[0]
        val tunneledPackets = ByteArrayOutputStream()
        val bridge = Ipv6NcmBridge(
            ncm = ncm,
            tun = tunFd,
            hostMac = HOST_MAC,
            onError = {},
            wiredPathDiagnostics = disabledDiagnostics,
            wiredStartupTiming = timing,
            expectedAirPlayAddress = LOCAL_ADDRESS,
            airPlayPort = AIRPLAY_PORT,
            attachGeneration = ATTACH_GENERATION,
        )
        val fakeTun = connectFakeTun(bridge, tunFd, tunneledPackets)
        fakeTun.inputToBridge.close()

        try {
            bridge.start()
            assertTrue(waitFor { tunneledPackets.size() == frame.size - ETHERNET_HEADER_BYTES })
            val expectedIpPacket = frame.copyOfRange(ETHERNET_HEADER_BYTES, frame.size)
            assertTrue(tunneledPackets.toByteArray().contentEquals(expectedIpPacket))
            assertTrue(timing.snapshot().firstValidAirPlaySynNs != null)
            assertEquals(0L, count(disabledDiagnostics, WiredPathEvent.V6_SYN_TO_AIRPLAY))
        } finally {
            bridge.close()
        }
    }

    private fun newDiagnostics(): WiredPathDiagnostics = WiredPathDiagnostics(
        runId = "run-tun",
        wiredStartupAttempt = 1,
        enabled = true,
    ).also { it.recordAttachGeneration(ATTACH_GENERATION) }

    private fun newBridge(
        diagnostics: WiredPathDiagnostics,
        timing: WiredStartupTiming? = null,
    ): Pair<NcmUsbBridge, ScriptedUsbDeviceConnectionShadow> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
        val script = Shadow.extract<ScriptedUsbDeviceConnectionShadow>(connection)
        val input = endpoint(address = 0x81, direction = 0x80)
        val output = endpoint(address = 0x02, direction = 0x00)
        return NcmUsbBridge(
            connection = connection,
            outEndpoint = output,
            inEndpoint = input,
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "fixture",
            onDiagnosticEvent = {},
            diagnosticProfile = NcmDiagnosticProfile.AUTO,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
            wiredPathDiagnostics = diagnostics,
            wiredStartupTiming = timing,
        ) to script
    }

    private fun endpoint(address: Int, direction: Int): UsbEndpoint =
        ReflectionHelpers.callConstructor(UsbEndpoint::class.java).also { endpoint ->
            Shadow.extract<ScriptedUsbEndpointShadow>(endpoint).apply {
                endpointAddress = address
                endpointMaxPacketSize = 512
                endpointDirection = direction
            }
        }

    private data class FakeTun(
        val inputToBridge: PipedOutputStream,
    )

    private fun connectFakeTun(
        bridge: Ipv6NcmBridge,
        tunFd: ParcelFileDescriptor,
        outputStream: OutputStream = ByteArrayOutputStream(),
    ): FakeTun {
        val input = PipedInputStream(16 * 1024)
        val inputToBridge = PipedOutputStream(input)
        bridge.tunInputStreamForTest = input
        bridge.tunOutputStreamForTest = outputStream
        bridge.closeTunForTest = {
            runCatching { input.close() }
            runCatching { inputToBridge.close() }
            runCatching { tunFd.close() }
        }
        return FakeTun(inputToBridge)
    }

    private fun ethernetIpv6(tcpFlags: Int): ByteArray {
        val ip = ipv6Tcp(flags = tcpFlags)
        val frame = ByteArray(ETHERNET_HEADER_BYTES + ip.size)
        HOST_MAC.copyInto(frame, 0)
        PHONE_MAC.copyInto(frame, 6)
        writeU16(frame, 12, ETHERTYPE_IPV6)
        ip.copyInto(frame, ETHERNET_HEADER_BYTES)
        return frame
    }

    private fun ipv6Tcp(flags: Int, sourcePort: Int = 50_000): ByteArray = ByteArray(IPV6_HEADER_BYTES + TCP_HEADER_BYTES).apply {
        this[0] = 0x60
        writeU16(this, 4, TCP_HEADER_BYTES)
        this[6] = IP_TCP.toByte()
        PHONE_ADDRESS.copyInto(this, IPV6_SOURCE_OFFSET)
        LOCAL_ADDRESS.copyInto(this, IPV6_DESTINATION_OFFSET)
        writeU16(this, IPV6_HEADER_BYTES, sourcePort)
        writeU16(this, IPV6_HEADER_BYTES + 2, if (sourcePort == AIRPLAY_PORT) 50_000 else AIRPLAY_PORT)
        this[IPV6_HEADER_BYTES + 12] = 0x50
        this[IPV6_HEADER_BYTES + 13] = flags.toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun count(diagnostics: WiredPathDiagnostics, event: WiredPathEvent): Long =
        diagnostics.snapshot(WiredPathReportReason.SOFT_8S).counter(event).count

    private fun waitFor(timeoutMillis: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private companion object {
        const val AIRPLAY_PORT = 7_000
        const val ATTACH_GENERATION = 6L
        const val ETHERNET_HEADER_BYTES = 14
        const val IPV6_HEADER_BYTES = 40
        const val IPV6_SOURCE_OFFSET = 8
        const val IPV6_DESTINATION_OFFSET = 24
        const val TCP_HEADER_BYTES = 20
        const val TCP_SYN = 0x02
        const val TCP_ACK = 0x10
        const val IP_TCP = 6
        const val ETHERTYPE_IPV6 = 0x86dd
        val HOST_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 1)
        val PHONE_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 2)
        val LOCAL_ADDRESS = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val PHONE_ADDRESS = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
    }
}
