package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

internal data class Step4UsbEndpointDescriptor(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int = 0,
)

internal data class Step4UsbEndpointFixture(
    val descriptor: Step4UsbEndpointDescriptor,
    val endpoint: UsbEndpoint,
)

internal data class Step4NcmEndpointFixtures(
    val bulkIn: Step4UsbEndpointFixture,
    val bulkOut: Step4UsbEndpointFixture,
    val statusIn: Step4UsbEndpointFixture,
)

internal data class Step4NcmBridgeFixture(
    val bridge: NcmUsbBridge,
    val connection: Step4ScriptedUsbConnection,
    val endpoints: Step4NcmEndpointFixtures,
)

internal enum class Step4WiredMilestone {
    START_SESSION_SENT,
    IPV4_SYN_RECEIVED,
    IPV6_SYN_RECEIVED,
    TUN_WRITE_SUCCEEDED,
    KERNEL_SYN_ACK_OBSERVED,
    NCM_OUT_NOT_READY,
    TCP_ACCEPTED,
    RTSP_ACTIVE,
    MAIN_SETUP,
    ALT_SETUP,
    ATTEMPT_FAILED,
    RECONNECT_PENDING,
    SESSION_ACTIVE,
    SECOND_LOSS,
}

internal data class Step4SyntheticWiredAttempt(
    val milestones: List<Step4WiredMilestone>,
    val receivedEthernetFrames: List<ByteArray> = emptyList(),
    val tunReplies: List<ByteArray> = emptyList(),
    val ncmSendResults: List<NcmSendResult> = emptyList(),
)

internal data class Step4PcmInput(
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Int,
    val pcm16Le: ByteArray,
)

internal data class Step4VideoFrameInput(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val encodedPayload: ByteArray,
)

internal class Step4VirtualClock(initialMillis: Long = 0L) {
    var nowMillis: Long = initialMillis
        private set

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0)
        nowMillis = Math.addExact(nowMillis, deltaMillis)
    }
}

internal class Step4ManualScheduler(private val clock: Step4VirtualClock) {
    private data class Task(
        val dueMillis: Long,
        val order: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private var nextOrder = 0L
    private val tasks = PriorityQueue(compareBy<Task> { it.dueMillis }.thenBy { it.order })

    fun schedule(delayMillis: Long, action: () -> Unit): Step4ScheduledHandle {
        require(delayMillis >= 0)
        val task = Task(Math.addExact(clock.nowMillis, delayMillis), nextOrder++, action)
        tasks.add(task)
        return object : Step4ScheduledHandle {
            override val isCancelled: Boolean get() = task.cancelled

            override fun cancel(): Boolean {
                if (task.cancelled) return false
                task.cancelled = true
                return true
            }
        }
    }

    fun advanceBy(deltaMillis: Long): Int {
        clock.advanceBy(deltaMillis)
        var completed = 0
        while (tasks.peek()?.dueMillis?.let { it <= clock.nowMillis } == true) {
            val task = tasks.remove()
            if (!task.cancelled) {
                task.action()
                completed++
            }
        }
        return completed
    }

    fun pendingTaskCount(): Int = tasks.count { !it.cancelled }
}

internal interface Step4ScheduledHandle {
    val isCancelled: Boolean
    fun cancel(): Boolean
}

internal class Step4SimulatedTun : Closeable {
    private val fromPeer = PipedInputStream(4_096)
    private val toPeer = PipedInputStream(4_096)
    private val peerOutput = PipedOutputStream(fromPeer)
    private val peerInput = PipedOutputStream(toPeer)
    private val bridgeInput = DataInputStream(fromPeer)

    fun peerWrite(packet: ByteArray) {
        peerOutput.write(packet)
        peerOutput.flush()
    }

    fun bridgeRead(packetBytes: Int): ByteArray = ByteArray(packetBytes).also(bridgeInput::readFully)

    fun bridgeWrite(packet: ByteArray) {
        peerInput.write(packet)
        peerInput.flush()
    }

    fun peerRead(packetBytes: Int): ByteArray = ByteArray(packetBytes).also {
        DataInputStream(toPeer).readFully(it)
    }

    override fun close() {
        bridgeInput.close()
        peerOutput.close()
        peerInput.close()
        toPeer.close()
    }
}

internal object Step4TestFixtures {
    private const val TCP_SYN = 0x02
    private const val IPV6_SYN_ACK = 0x12

    fun usbEndpoint(descriptor: Step4UsbEndpointDescriptor): Step4UsbEndpointFixture {
        val endpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java)
        Shadow.extract<Step4UsbEndpointShadow>(endpoint).descriptor = descriptor
        return Step4UsbEndpointFixture(descriptor, endpoint)
    }

    fun ncmEndpoints(): Step4NcmEndpointFixtures = Step4NcmEndpointFixtures(
        bulkIn = usbEndpoint(
            Step4UsbEndpointDescriptor(
                interfaceNumber = 1,
                alternateSetting = 1,
                address = 0x81,
                attributes = UsbConstants.USB_ENDPOINT_XFER_BULK,
                maxPacketSize = 512,
            ),
        ),
        bulkOut = usbEndpoint(
            Step4UsbEndpointDescriptor(
                interfaceNumber = 1,
                alternateSetting = 1,
                address = 0x02,
                attributes = UsbConstants.USB_ENDPOINT_XFER_BULK,
                maxPacketSize = 512,
            ),
        ),
        statusIn = usbEndpoint(
            Step4UsbEndpointDescriptor(
                interfaceNumber = 0,
                alternateSetting = 0,
                address = 0x83,
                attributes = UsbConstants.USB_ENDPOINT_XFER_INT,
                maxPacketSize = 16,
                interval = 8,
            ),
        ),
    )

    fun ncmBridge(profile: NcmDiagnosticProfile = NcmDiagnosticProfile.AUTO): Step4NcmBridgeFixture {
        val connection = Step4ScriptedUsbConnection.create()
        val endpoints = ncmEndpoints()
        val bridge = NcmUsbBridge(
            connection = connection.androidConnection,
            outEndpoint = endpoints.bulkOut.endpoint,
            inEndpoint = endpoints.bulkIn.endpoint,
            statusEndpoint = null,
            descriptorHostMac = byteArrayOf(0x02, 0, 0, 0, 0, 1),
            selectionDiagnostics = "synthetic NCM endpoint fixture",
            onDiagnosticEvent = {},
            diagnosticProfile = profile,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
        )
        return Step4NcmBridgeFixture(bridge, connection, endpoints)
    }

    fun ethernetIpv4Tcp(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        tcpFlags: Int = TCP_SYN,
    ): ByteArray {
        val frame = ethernetHeader(0x0800) + ByteArray(40)
        frame[14] = 0x45
        writeU16(frame, 16, 40)
        frame[22] = 64
        frame[23] = 6
        frame[26] = 192.toByte()
        frame[27] = 0
        frame[28] = 2
        frame[29] = 2
        frame[30] = 192.toByte()
        frame[31] = 0
        frame[32] = 2
        frame[33] = 1
        writeTcpHeader(frame, 34, sourcePort, destinationPort, tcpFlags)
        return frame
    }

    fun ethernetIpv6Tcp(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        tcpFlags: Int = TCP_SYN,
        sourceHost: Int = 2,
        destinationHost: Int = 1,
    ): ByteArray {
        val frame = ethernetHeader(0x86dd) + ByteArray(60)
        frame[14] = 0x60
        writeU16(frame, 18, 20)
        frame[20] = 6
        frame[21] = 64
        writeDocumentationIpv6Address(frame, 22, sourceHost)
        writeDocumentationIpv6Address(frame, 38, destinationHost)
        writeTcpHeader(frame, 54, sourcePort, destinationPort, tcpFlags)
        return frame
    }

    fun ipv6TcpPacket(
        sourcePort: Int = 7_000,
        destinationPort: Int = 49_152,
        tcpFlags: Int = IPV6_SYN_ACK,
        sourceHost: Int = 1,
        destinationHost: Int = 2,
    ): ByteArray = ethernetIpv6Tcp(
        sourcePort = sourcePort,
        destinationPort = destinationPort,
        tcpFlags = tcpFlags,
        sourceHost = sourceHost,
        destinationHost = destinationHost,
    ).copyOfRange(14, 74)

    fun startSessionNoSyn(): Step4SyntheticWiredAttempt = Step4SyntheticWiredAttempt(
        milestones = listOf(Step4WiredMilestone.START_SESSION_SENT),
    )

    fun ipv4SynOnly(): Step4SyntheticWiredAttempt = Step4SyntheticWiredAttempt(
        milestones = listOf(
            Step4WiredMilestone.START_SESSION_SENT,
            Step4WiredMilestone.IPV4_SYN_RECEIVED,
        ),
        receivedEthernetFrames = listOf(ethernetIpv4Tcp()),
    )

    fun ipv6SynAckNotReady(): Step4SyntheticWiredAttempt = Step4SyntheticWiredAttempt(
        milestones = listOf(
            Step4WiredMilestone.START_SESSION_SENT,
            Step4WiredMilestone.IPV6_SYN_RECEIVED,
            Step4WiredMilestone.TUN_WRITE_SUCCEEDED,
            Step4WiredMilestone.KERNEL_SYN_ACK_OBSERVED,
            Step4WiredMilestone.NCM_OUT_NOT_READY,
        ),
        receivedEthernetFrames = listOf(ethernetIpv6Tcp()),
        tunReplies = listOf(ipv6TcpPacket()),
        ncmSendResults = listOf(NcmSendResult.NotReady),
    )

    fun rtspAltWithoutMain(): Step4SyntheticWiredAttempt = Step4SyntheticWiredAttempt(
        milestones = listOf(
            Step4WiredMilestone.TCP_ACCEPTED,
            Step4WiredMilestone.RTSP_ACTIVE,
            Step4WiredMilestone.ALT_SETUP,
        ),
    )

    fun recoveredThenSecondLoss(): Step4SyntheticWiredAttempt = Step4SyntheticWiredAttempt(
        milestones = listOf(
            Step4WiredMilestone.ATTEMPT_FAILED,
            Step4WiredMilestone.RECONNECT_PENDING,
            Step4WiredMilestone.SESSION_ACTIVE,
            Step4WiredMilestone.SECOND_LOSS,
        ),
    )

    fun pcm16(sampleRate: Int, channels: Int, frameCount: Int, sample: Short = 0): Step4PcmInput {
        require(sampleRate > 0)
        require(channels in 1..2)
        require(frameCount > 0)
        val bytes = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(channels, frameCount), 2))
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(channels * frameCount) { bytes.putShort(sample) }
        return Step4PcmInput(sampleRate, channels, frameCount, bytes.array())
    }

    fun videoFrameInput(
        mimeType: String,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        encodedPayload: ByteArray,
    ): Step4VideoFrameInput {
        require(mimeType.startsWith("video/"))
        require(width > 0 && height > 0)
        require(presentationTimeUs >= 0)
        require(encodedPayload.isNotEmpty())
        return Step4VideoFrameInput(mimeType, width, height, presentationTimeUs, encodedPayload.copyOf())
    }

    private fun ethernetHeader(etherType: Int): ByteArray = byteArrayOf(
        0x02, 0, 0, 0, 0, 2,
        0x02, 0, 0, 0, 0, 1,
        (etherType ushr 8).toByte(), etherType.toByte(),
    )

    private fun writeTcpHeader(
        frame: ByteArray,
        offset: Int,
        sourcePort: Int,
        destinationPort: Int,
        flags: Int,
    ) {
        writeU16(frame, offset, sourcePort)
        writeU16(frame, offset + 2, destinationPort)
        frame[offset + 12] = 0x50
        frame[offset + 13] = flags.toByte()
        writeU16(frame, offset + 14, 65_535)
    }

    private fun writeDocumentationIpv6Address(destination: ByteArray, offset: Int, host: Int) {
        destination[offset] = 0x20
        destination[offset + 1] = 0x01
        destination[offset + 2] = 0x0d
        destination[offset + 3] = 0xb8.toByte()
        destination[offset + 15] = host.toByte()
    }

    private fun writeU16(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = (value ushr 8).toByte()
        destination[offset + 1] = value.toByte()
    }
}
