package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.Ntb16Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

internal data class UsbEndpointFixture(
    val address: Int,
    val direction: Int,
    val transferType: Int,
    val maxPacketSize: Int,
) {
    init {
        require(address in 0..255)
        require(direction == 0x00 || direction == 0x80)
        require((address and 0x80) == direction)
        require(transferType in 0..3)
        require(maxPacketSize > 0)
    }
}

internal sealed interface RequestWaitOutcome {
    data class Complete(val bytes: ByteArray) : RequestWaitOutcome
    data object Timeout : RequestWaitOutcome
    data object NullResult : RequestWaitOutcome
    data object UnexpectedCompletion : RequestWaitOutcome
    data object BlockUntilConnectionClose : RequestWaitOutcome
}

internal sealed interface UsbQueueOutcome {
    data object Queued : UsbQueueOutcome
    data object Rejected : UsbQueueOutcome
}

internal sealed interface BulkTransferOutcome {
    data object FullTransfer : BulkTransferOutcome
    data object NotReady : BulkTransferOutcome
    data class Partial(val bytes: Int) : BulkTransferOutcome
    data class Failure(val message: String) : BulkTransferOutcome
}

internal enum class TunWriteOutcome {
    WRITTEN,
    NOT_READY,
    FAILED,
}

internal class SimulatedTunFixture(
    writeOutcomes: List<TunWriteOutcome>,
    kernelReplies: List<ByteArray?>,
) {
    private val writes = ArrayDeque(writeOutcomes)
    private val replies = ArrayDeque<KernelReply>().apply {
        kernelReplies.forEach { addLast(KernelReply(it?.copyOf())) }
    }
    private val writtenPackets = mutableListOf<ByteArray>()

    fun writeToKernel(packet: ByteArray): TunWriteOutcome {
        check(writes.isNotEmpty()) { "TUN write outcome must be configured explicitly" }
        val outcome = writes.removeFirst()
        if (outcome == TunWriteOutcome.WRITTEN) writtenPackets += packet.copyOf()
        return outcome
    }

    fun readKernelReply(): ByteArray? {
        check(replies.isNotEmpty()) { "kernel reply or explicit no-reply must be configured" }
        return replies.removeFirst().packet?.copyOf()
    }

    fun packetsWrittenToKernel(): List<ByteArray> = writtenPackets.map(ByteArray::copyOf)

    private data class KernelReply(val packet: ByteArray?)
}

internal class VirtualClock(startAtMillis: Long = 0L) {
    var nowMillis: Long = startAtMillis
        private set

    fun advanceTo(timeMillis: Long) {
        require(timeMillis >= nowMillis)
        nowMillis = timeMillis
    }
}

internal class VirtualScheduler(val clock: VirtualClock) {
    private data class Task(
        val deadlineMillis: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.deadlineMillis }, { it.sequence }))
    private var nextSequence = 0L

    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        require(delayMillis >= 0L)
        val task = Task(clock.nowMillis + delayMillis, nextSequence++, action)
        tasks += task
        return { task.cancelled = true }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0L)
        val targetMillis = clock.nowMillis + deltaMillis
        while (tasks.peek()?.deadlineMillis?.let { it <= targetMillis } == true) {
            val task = tasks.remove()
            clock.advanceTo(task.deadlineMillis)
            if (!task.cancelled) task.action()
        }
        clock.advanceTo(targetMillis)
    }
}

internal enum class WiredStartupEventType {
    START_SESSION_SENT,
    IPV4_SYN_TO_AIRPLAY,
    IPV6_SYN_TO_AIRPLAY,
    TUN_WRITE_SUCCEEDED,
    SYN_ACK_OBSERVED,
    USB_OUT_NOT_READY,
    TCP_ACCEPTED,
    RTSP_ACTIVE,
    MAIN_SETUP,
    MAIN_FIRST_FRAME,
    ALT_SETUP,
    SESSION_RECOVERED,
    SECOND_LOSS,
}

internal data class WiredStartupEvent(val elapsedMillis: Long, val type: WiredStartupEventType)

internal data class WiredStartupFixture(val name: String, val events: List<WiredStartupEvent>)

internal data class MicrophoneRequestFixture(
    val name: String,
    val messageType: String,
    val isInput: Boolean,
    val dataPort: Int?,
    val permissionGranted: Boolean,
)

internal data class SplitGeometryFixture(
    val widthPixels: Int,
    val heightPixels: Int,
    val carPlayFraction: Float,
)

internal data class PcmInputFixture(
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    val bytes: ByteArray,
)

internal data class VideoFrameInputFixture(
    val codec: String,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val payload: ByteArray,
)

internal object Step4TestFixtures {
    const val USB_PACKET_SIZE = 512
    private val HOST_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 1)
    private val PEER_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 2)

    val endpointDescriptors = listOf(
        UsbEndpointFixture(address = 0x81, direction = 0x80, transferType = 2, maxPacketSize = USB_PACKET_SIZE),
        UsbEndpointFixture(address = 0x02, direction = 0x00, transferType = 2, maxPacketSize = USB_PACKET_SIZE),
        UsbEndpointFixture(address = 0x83, direction = 0x80, transferType = 3, maxPacketSize = 16),
    )

    val readWaitOutcomes = listOf(
        RequestWaitOutcome.Complete(byteArrayOf(0x01, 0x02)),
        RequestWaitOutcome.Timeout,
        RequestWaitOutcome.NullResult,
        RequestWaitOutcome.UnexpectedCompletion,
        RequestWaitOutcome.BlockUntilConnectionClose,
    )

    val microphoneRequests = listOf(
        MicrophoneRequestFixture("SIRI", "speechRecognition", true, 49_152, true),
        MicrophoneRequestFixture("PHONE", "telephony", true, 49_153, true),
        MicrophoneRequestFixture("VOICE_MESSAGE", "default", true, 49_154, true),
        MicrophoneRequestFixture("EXPLICIT_FALSE", "default", false, 49_155, true),
        MicrophoneRequestFixture("MUSIC", "media", false, null, false),
    )

    val localSplit55 = SplitGeometryFixture(widthPixels = 1920, heightPixels = 1080, carPlayFraction = 0.55f)

    val wiredStartupScenarios = listOf(
        WiredStartupFixture("start-session-no-syn", listOf(event(0, WiredStartupEventType.START_SESSION_SENT))),
        WiredStartupFixture(
            "ipv4-syn-only",
            listOf(
                event(0, WiredStartupEventType.START_SESSION_SENT),
                event(140, WiredStartupEventType.IPV4_SYN_TO_AIRPLAY),
            ),
        ),
        WiredStartupFixture(
            "ipv6-syn-tun-no-reply",
            listOf(
                event(0, WiredStartupEventType.START_SESSION_SENT),
                event(110, WiredStartupEventType.IPV6_SYN_TO_AIRPLAY),
                event(112, WiredStartupEventType.TUN_WRITE_SUCCEEDED),
            ),
        ),
        WiredStartupFixture(
            "syn-ack-not-ready",
            listOf(
                event(0, WiredStartupEventType.START_SESSION_SENT),
                event(110, WiredStartupEventType.IPV6_SYN_TO_AIRPLAY),
                event(112, WiredStartupEventType.TUN_WRITE_SUCCEEDED),
                event(118, WiredStartupEventType.SYN_ACK_OBSERVED),
                event(121, WiredStartupEventType.USB_OUT_NOT_READY),
            ),
        ),
        WiredStartupFixture(
            "tcp-accept-only",
            listOf(event(0, WiredStartupEventType.START_SESSION_SENT), event(35, WiredStartupEventType.TCP_ACCEPTED)),
        ),
        WiredStartupFixture(
            "rtsp-active-only",
            listOf(
                event(0, WiredStartupEventType.START_SESSION_SENT),
                event(35, WiredStartupEventType.TCP_ACCEPTED),
                event(50, WiredStartupEventType.RTSP_ACTIVE),
            ),
        ),
        WiredStartupFixture(
            "main-first-frame",
            listOf(
                event(0, WiredStartupEventType.START_SESSION_SENT),
                event(1_100, WiredStartupEventType.MAIN_SETUP),
                event(1_240, WiredStartupEventType.MAIN_FIRST_FRAME),
            ),
        ),
        WiredStartupFixture(
            "alt-only",
            listOf(event(0, WiredStartupEventType.START_SESSION_SENT), event(800, WiredStartupEventType.ALT_SETUP)),
        ),
        WiredStartupFixture(
            "recovered-then-second-loss",
            listOf(
                event(8_000, WiredStartupEventType.SESSION_RECOVERED),
                event(9_000, WiredStartupEventType.SECOND_LOSS),
            ),
        ),
    )

    fun ethernetFrame(length: Int = 60, seed: Int = 0x20): ByteArray {
        require(length >= 14)
        return ByteArray(length) { index -> (seed + index).toByte() }
    }

    fun ntb16(frame: ByteArray, sequence: Int = 0): ByteArray = Ntb16Codec.build(frame, sequence)

    fun boundaryFrame(): ByteArray = ethernetFrame(length = USB_PACKET_SIZE - 28)

    fun paddedBoundaryNtb16(): ByteArray = ntb16(boundaryFrame())

    fun unpaddedBoundaryNtb16(): ByteArray = paddedBoundaryNtb16().copyOf(USB_PACKET_SIZE)

    fun split(bytes: ByteArray, vararg boundaries: Int): List<ByteArray> {
        val ordered = (listOf(0) + boundaries.toList() + bytes.size).distinct().sorted()
        require(ordered.first() == 0 && ordered.last() == bytes.size)
        require(ordered.all { it in 0..bytes.size })
        return ordered.zipWithNext { start, end -> bytes.copyOfRange(start, end) }
    }

    fun coalesced(vararg blocks: ByteArray): ByteArray {
        val output = ByteArray(blocks.sumOf { it.size })
        var offset = 0
        blocks.forEach { block ->
            block.copyInto(output, offset)
            offset += block.size
        }
        return output
    }

    fun truncatedNtb16Header(block: ByteArray = ntb16(ethernetFrame())): ByteArray =
        block.copyOfRange(0, minOf(7, block.size))

    fun corruptNdpOffset(block: ByteArray): ByteArray = block.copyOf().apply {
        require(size >= 12)
        val invalidOffset = size + 1
        this[10] = invalidOffset.toByte()
        this[11] = (invalidOffset ushr 8).toByte()
    }

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
            HOST_MAC.copyInto(this, 0)
            PEER_MAC.copyInto(this, 6)
            this[12] = 0x08
            this[13] = 0x00
            this[14] = 0x45
            putU16(16, 40)
            this[22] = 64
            this[23] = 6
            this[26] = 192.toByte()
            this[29] = 2
            this[30] = 192.toByte()
            this[33] = 1
            putU16(34, sourcePort)
            putU16(36, destinationPort)
            this[46] = 0x50
            this[47] = 0x02
        }

    fun pcm16(sampleRate: Int, channels: Int, frames: Int): PcmInputFixture {
        require(sampleRate > 0)
        require(channels in 1..2)
        require(frames in 1..16_384)
        val bytes = ByteArray(frames * channels * Short.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { index ->
            val sample = ((index * 257) % (Short.MAX_VALUE - Short.MIN_VALUE + 1) + Short.MIN_VALUE).toShort()
            buffer.putShort(sample)
        }
        return PcmInputFixture(sampleRate, channels, frames, bytes)
    }

    fun videoFrame(
        codec: String = "H264",
        width: Int = 1920,
        height: Int = 1080,
        presentationTimeUs: Long = 33_333L,
    ): VideoFrameInputFixture {
        require(codec.isNotBlank())
        require(width > 0 && height > 0)
        require(presentationTimeUs >= 0L)
        return VideoFrameInputFixture(
            codec = codec,
            width = width,
            height = height,
            presentationTimeUs = presentationTimeUs,
            cropLeft = 0,
            cropTop = 0,
            cropRight = width - 1,
            cropBottom = height - 1,
            payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22),
        )
    }

    private fun event(elapsedMillis: Long, type: WiredStartupEventType): WiredStartupEvent =
        WiredStartupEvent(elapsedMillis, type)

    private fun ipv6TcpFrame(sourcePort: Int, destinationPort: Int, flags: Int): ByteArray =
        ByteArray(14 + 40 + 20).apply {
            PEER_MAC.copyInto(this, 0)
            HOST_MAC.copyInto(this, 6)
            this[12] = 0x86.toByte()
            this[13] = 0xdd.toByte()
            this[14] = 0x60
            putU16(18, 20)
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

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }
}
