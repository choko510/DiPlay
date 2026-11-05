package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.Ntb16Codec
import java.util.PriorityQueue

internal sealed interface UsbRequestWaitFixture {
    data class Completed(val bytes: ByteArray) : UsbRequestWaitFixture
    data class TimeoutAfter(val delayMillis: Long) : UsbRequestWaitFixture
    data object Timeout : UsbRequestWaitFixture
    data object NullResult : UsbRequestWaitFixture
    data object UnexpectedCompletion : UsbRequestWaitFixture
}

internal data class UsbEndpointFixture(
    val address: Int,
    val direction: Int,
    val maxPacketSize: Int,
    val transferType: Int,
)

internal data class MicrophoneRequestFixture(
    val scenario: String,
    val streamType: Int,
    val audioType: String,
    val inputRequested: Boolean,
    val dataPort: Int?,
)

internal data class WiredStartupEventFixture(
    val elapsedMillis: Long,
    val stage: String,
    val ethernetPacket: ByteArray? = null,
)

internal data class WiredStartupFixture(
    val name: String,
    val events: List<WiredStartupEventFixture>,
)

internal data class ViewAreaFixture(
    val displayWidth: Int,
    val displayHeight: Int,
    val carPlayFraction: Float,
    val mainStreamType: Int = 110,
    val alternateStreamType: Int = 111,
)

internal data class Pcm16Fixture(
    val sampleRate: Int,
    val channels: Int,
    val samples: ShortArray,
) {
    fun bytes(): ByteArray = ByteArray(samples.size * Short.SIZE_BYTES).also { output ->
        samples.forEachIndexed { index, sample ->
            output[index * 2] = sample.toByte()
            output[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
}

internal data class EncodedVideoFixture(
    val codec: String,
    val presentationTimeMicros: Long,
    val keyFrame: Boolean,
    val width: Int,
    val height: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val payload: ByteArray,
)

internal class SimulatedTunFixture {
    private val packetsForDevice = ArrayDeque<ByteArray>()
    private val packetsFromDevice = ArrayList<ByteArray>()
    var closed: Boolean = false
        private set

    val writtenPackets: List<ByteArray>
        get() = packetsFromDevice.map(ByteArray::copyOf)

    fun inject(packet: ByteArray) {
        check(!closed)
        packetsForDevice.addLast(packet.copyOf())
    }

    fun read(): ByteArray? {
        check(!closed)
        return packetsForDevice.removeFirstOrNull()
    }

    fun write(packet: ByteArray) {
        check(!closed)
        packetsFromDevice += packet.copyOf()
    }

    fun close() {
        closed = true
    }
}

internal class Step4VirtualScheduler {
    private data class Task(
        val deadlineMillis: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = PriorityQueue<Task>(compareBy<Task> { it.deadlineMillis }.thenBy { it.sequence })
    private var nextSequence = 0L

    var nowMillis: Long = 0L
        private set

    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        require(delayMillis >= 0)
        val task = Task(nowMillis + delayMillis, nextSequence++, action)
        tasks.add(task)
        return { task.cancelled = true }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0)
        advanceTo(nowMillis + deltaMillis)
    }

    fun advanceTo(targetMillis: Long) {
        require(targetMillis >= nowMillis)
        while (tasks.peek()?.deadlineMillis?.let { it <= targetMillis } == true) {
            val task = tasks.remove()
            nowMillis = task.deadlineMillis
            if (!task.cancelled) task.action()
        }
        nowMillis = targetMillis
    }
}

internal object Step4TestFixtures {
    const val USB_PACKET_SIZE = 512
    private val HOST_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 1)
    private val PEER_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 2)

    val readWaitOutcomes = listOf(
        UsbRequestWaitFixture.Completed(byteArrayOf(0x01, 0x02)),
        UsbRequestWaitFixture.Timeout,
        UsbRequestWaitFixture.NullResult,
        UsbRequestWaitFixture.UnexpectedCompletion,
    )

    val endpointDescriptors = listOf(
        UsbEndpointFixture(address = 0x81, direction = 0x80, maxPacketSize = USB_PACKET_SIZE, transferType = 2),
        UsbEndpointFixture(address = 0x02, direction = 0x00, maxPacketSize = USB_PACKET_SIZE, transferType = 2),
        UsbEndpointFixture(address = 0x83, direction = 0x80, maxPacketSize = 16, transferType = 3),
    )

    val microphoneRequests = listOf(
        MicrophoneRequestFixture("SIRI", 100, "speechRecognition", true, 49_152),
        MicrophoneRequestFixture("PHONE", 100, "telephony", true, 49_153),
        MicrophoneRequestFixture("VOICE_MESSAGE", 100, "default", true, 49_154),
        MicrophoneRequestFixture("EXPLICIT_FALSE", 100, "default", false, 49_155),
        MicrophoneRequestFixture("MUSIC", 102, "media", false, null),
    )

    val localSplitView = ViewAreaFixture(1920, 1080, 0.55f)

    val mainOnlyStartup = WiredStartupFixture(
        name = "main-frame",
        events = listOf(
            WiredStartupEventFixture(0, "START_SENT"),
            WiredStartupEventFixture(1_100, "MAIN_SETUP"),
            WiredStartupEventFixture(1_240, "MAIN_FIRST_FRAME"),
        ),
    )
    val alternateOnlyStartup = WiredStartupFixture(
        name = "alternate-only",
        events = listOf(
            WiredStartupEventFixture(0, "START_SENT"),
            WiredStartupEventFixture(800, "ALT_SETUP"),
            WiredStartupEventFixture(900, "RTSP_ACTIVE"),
        ),
    )
    val noSynStartup = WiredStartupFixture(
        name = "start-no-syn",
        events = listOf(
            WiredStartupEventFixture(0, "START_SENT"),
            WiredStartupEventFixture(180, "FIRST_BULK_OUT_SENT"),
            WiredStartupEventFixture(15_000, "NO_SYN"),
        ),
    )
    val ipv4OnlyStartup = WiredStartupFixture(
        name = "ipv4-syn-only",
        events = listOf(
            WiredStartupEventFixture(0, "START_SENT"),
            WiredStartupEventFixture(140, "IPV4_SYN", ipv4TcpSyn()),
            WiredStartupEventFixture(15_000, "NO_IPV6_SYN"),
        ),
    )
    val tunnelReplyMissingStartup = WiredStartupFixture(
        name = "tun-no-reply",
        events = listOf(
            WiredStartupEventFixture(0, "IPV6_SYN", ipv6TcpSyn()),
            WiredStartupEventFixture(8, "TUN_WRITE_SUCCESS"),
            WiredStartupEventFixture(15_000, "NO_KERNEL_SYN_ACK"),
        ),
    )
    val synAckNotReadyStartup = WiredStartupFixture(
        name = "syn-ack-not-ready",
        events = listOf(
            WiredStartupEventFixture(0, "START_SENT"),
            WiredStartupEventFixture(110, "IPV6_SYN", ipv6TcpSyn()),
            WiredStartupEventFixture(112, "TUN_WRITE_SUCCESS"),
            WiredStartupEventFixture(118, "SYN_ACK_OBSERVED", ipv6TcpSynAck()),
            WiredStartupEventFixture(121, "NCM_OUT_NOT_READY"),
        ),
    )
    val tcpAcceptOnlyStartup = WiredStartupFixture(
        name = "tcp-accept-only",
        events = listOf(WiredStartupEventFixture(0, "SYN_ACK_SENT"), WiredStartupEventFixture(35, "TCP_ACCEPTED")),
    )
    val rtspActiveOnlyStartup = WiredStartupFixture(
        name = "rtsp-active-only",
        events = listOf(WiredStartupEventFixture(0, "TCP_ACCEPTED"), WiredStartupEventFixture(50, "RTSP_ACTIVE")),
    )

    fun ethernetFrame(length: Int = 60, seed: Int = 0x20): ByteArray {
        require(length >= 14)
        return ByteArray(length) { index -> (seed + index).toByte() }
    }

    fun ntb16(frame: ByteArray, sequence: Int = 0): ByteArray = Ntb16Codec.build(frame, sequence)

    fun unpadded512ByteNtb(): ByteArray {
        val padded = ntb16(ethernetFrame(length = USB_PACKET_SIZE - 28))
        require(padded.size == USB_PACKET_SIZE + 1)
        return padded.copyOf(USB_PACKET_SIZE)
    }

    fun padded512ByteNtb(): ByteArray =
        ntb16(ethernetFrame(length = USB_PACKET_SIZE - 28)).also { require(it.size == USB_PACKET_SIZE + 1) }

    fun split(bytes: ByteArray, vararg boundaries: Int): List<ByteArray> {
        val ordered = (listOf(0) + boundaries.toList() + bytes.size).distinct().sorted()
        require(ordered.first() == 0 && ordered.last() == bytes.size)
        require(ordered.all { it in 0..bytes.size })
        return ordered.zipWithNext { start, end -> bytes.copyOfRange(start, end) }
    }

    fun coalesced(vararg blocks: ByteArray): ByteArray =
        ByteArray(blocks.sumOf { it.size }).also { output ->
            var offset = 0
            blocks.forEach { block ->
                block.copyInto(output, offset)
                offset += block.size
            }
        }

    fun corruptNdpOffset(block: ByteArray): ByteArray = block.copyOf().apply {
        require(size >= 12)
        val invalidOffset = size + 1
        this[10] = invalidOffset.toByte()
        this[11] = (invalidOffset shr 8).toByte()
    }

    fun truncatedNtb16Block(block: ByteArray = ntb16(ethernetFrame())): ByteArray =
        block.copyOfRange(0, minOf(7, block.size))

    fun ntb32Candidate(): ByteArray = byteArrayOf(
        0x6e, 0x63, 0x6d, 0x68, 0x20, 0x00, 0x00, 0x00,
    ) + ByteArray(24)

    fun oversizedGarbage(size: Int = 1_048_577): ByteArray {
        require(size > USB_PACKET_SIZE)
        return ByteArray(size) { 0x55 }
    }

    fun ipv6TcpSyn(sourcePort: Int = 51_000, destinationPort: Int = 7_000): ByteArray = ipv6TcpFrame(
        sourcePort = sourcePort,
        destinationPort = destinationPort,
        flags = 0x02,
    )

    fun ipv6TcpSynAck(sourcePort: Int = 7_000, destinationPort: Int = 51_000): ByteArray = ipv6TcpFrame(
        sourcePort = sourcePort,
        destinationPort = destinationPort,
        flags = 0x12,
    )

    fun ipv4TcpSyn(sourcePort: Int = 51_000, destinationPort: Int = 7_000): ByteArray =
        ByteArray(14 + 20 + 20).apply {
            putMac(0, PEER_MAC)
            putMac(6, HOST_MAC)
            this[12] = 0x08
            this[13] = 0x00
            this[14] = 0x45
            this[16] = 0
            this[17] = 40
            this[22] = 64
            this[23] = 6
            this[26] = 192.toByte()
            this[27] = 0
            this[28] = 0
            this[29] = 2
            this[30] = 192.toByte()
            this[31] = 0
            this[32] = 0
            this[33] = 1
            putU16(34, sourcePort)
            putU16(36, destinationPort)
            this[46] = 0x50
            this[47] = 0x02
        }

    fun pcm16(
        sampleRate: Int = 48_000,
        channels: Int = 2,
        samples: ShortArray = shortArrayOf(0, 1, -1, 32_767, -32_768),
    ): Pcm16Fixture = Pcm16Fixture(sampleRate, channels, samples.copyOf())

    fun videoFrame(codec: String = "H264", timestampMicros: Long = 33_333L, keyFrame: Boolean = true): EncodedVideoFixture =
        EncodedVideoFixture(
            codec = codec,
            presentationTimeMicros = timestampMicros,
            keyFrame = keyFrame,
            width = 1920,
            height = 1080,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 1919,
            cropBottom = 1079,
            payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22),
        )

    private fun ipv6TcpFrame(sourcePort: Int, destinationPort: Int, flags: Int): ByteArray =
        ByteArray(14 + 40 + 20).apply {
            putMac(0, PEER_MAC)
            putMac(6, HOST_MAC)
            this[12] = 0x86.toByte()
            this[13] = 0xdd.toByte()
            this[14] = 0x60
            this[18] = 0
            this[19] = 20
            this[20] = 6
            this[21] = 64
            this[22] = 0xfe.toByte()
            this[23] = 0x80.toByte()
            this[37] = 2
            this[38] = 0xfe.toByte()
            this[39] = 0x80.toByte()
            this[53] = 1
            putU16(54, sourcePort)
            putU16(56, destinationPort)
            this[66] = 0x50
            this[67] = flags.toByte()
        }

    private fun ByteArray.putMac(offset: Int, mac: ByteArray) {
        mac.copyInto(this, offset)
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value shr 8).toByte()
        this[offset + 1] = value.toByte()
    }

}
