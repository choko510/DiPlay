package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.Parcel
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

internal object Step4TestFixtureFactory {
    fun ethernetFrame(marker: Int = 0): ByteArray = byteArrayOf(
        0x02, 0x00, 0x00, 0x00, 0x00, marker.toByte(),
        0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x86.toByte(), 0xdd.toByte(),
        0x60, 0x00, 0x00, marker.toByte(),
    )

    fun ntb16(frame: ByteArray = ethernetFrame(), sequence: Int = 0): ByteArray =
        Ntb16Codec.build(frame, sequence)

    fun ntbFixtures(): NtbFixtures {
        val boundaryWithPad = ntb16(ByteArray(484) { it.toByte() }, sequence = 7)
        val boundaryBlock = boundaryWithPad.copyOf(512)
        val second = ntb16(ethernetFrame(marker = 1), sequence = 8)
        val splitAt = boundaryBlock.size / 3
        val corruptOffset = boundaryBlock.copyOf().apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
        }
        return NtbFixtures(
            boundaryBlock = boundaryBlock,
            optionalZeroShortPad = boundaryWithPad,
            splitReads = listOf(
                boundaryBlock.copyOfRange(0, splitAt),
                boundaryBlock.copyOfRange(splitAt, splitAt * 2),
                boundaryBlock.copyOfRange(splitAt * 2, boundaryBlock.size),
            ),
            coalescedBlocks = boundaryBlock + second,
            truncatedHeader = boundaryBlock.copyOfRange(0, 8),
            corruptNdpOffset = corruptOffset,
        )
    }

    fun oversizedGarbage(): ByteArray = ByteArray(1024 * 1024 + 1) { 0x5a }

    fun bulkInEndpoint(maxPacketSize: Int = 512): UsbEndpointDescriptorFixture =
        UsbEndpointDescriptorFixture(address = 0x81, attributes = 2, maxPacketSize = maxPacketSize)

    fun bulkOutEndpoint(maxPacketSize: Int = 512): UsbEndpointDescriptorFixture =
        UsbEndpointDescriptorFixture(address = 0x02, attributes = 2, maxPacketSize = maxPacketSize)

    fun robolectricUsbInHarness(
        incomingPackets: List<ByteArray>,
        queueOutcomes: List<Boolean>,
        waitOutcomes: List<RequestWaitOutcome>,
        bulkTransferOutcomes: List<Int> = emptyList(),
    ): RobolectricUsbInHarness {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
        val shadow = Shadow.extract<Step4UsbDeviceConnectionShadow>(connection)
        shadow.configure(incomingPackets, queueOutcomes, waitOutcomes, bulkTransferOutcomes)
        return RobolectricUsbInHarness(connection, shadow)
    }

    fun requestWaitDispatcher(outcomes: List<RequestWaitOutcome>): FakeRequestWaitDispatcher =
        FakeRequestWaitDispatcher(outcomes)

    fun tun(writeOutcomes: List<TunWriteOutcome>, kernelReplies: List<ByteArray?> = emptyList()): FakeTun =
        FakeTun(writeOutcomes, kernelReplies)

    fun virtualScheduler(startAtMillis: Long = 0L): VirtualScheduler =
        VirtualScheduler(VirtualClock(startAtMillis))

    fun pcm16(sampleRate: Int, channels: Int, frames: Int): PcmInputFixture {
        require(sampleRate > 0)
        require(channels in 1..2)
        require(frames > 0)
        val bytes = ByteArray(frames * channels * Short.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { sampleIndex ->
            val sample = ((sampleIndex * 257) % (Short.MAX_VALUE - Short.MIN_VALUE + 1) + Short.MIN_VALUE).toShort()
            buffer.putShort(sample)
        }
        return PcmInputFixture(sampleRate, channels, frames, bytes)
    }

    fun videoFrame(
        width: Int = 1280,
        height: Int = 720,
        presentationTimeUs: Long = 0L,
    ): VideoFrameInputFixture {
        require(width > 0 && height > 0)
        require(presentationTimeUs >= 0L)
        return VideoFrameInputFixture(width, height, presentationTimeUs, byteArrayOf(0x53, 0x54, 0x45, 0x50))
    }

    val wiredStartupScenarios = listOf(
        WiredStartupFixture("start-session-no-syn", listOf(WiredStartupEvent.START_SESSION_SENT)),
        WiredStartupFixture(
            "ipv4-syn-only",
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.IPV4_SYN_TO_AIRPLAY),
        ),
        WiredStartupFixture(
            "ipv6-syn-synack-out-not-ready",
            listOf(
                WiredStartupEvent.START_SESSION_SENT,
                WiredStartupEvent.IPV6_SYN_TO_AIRPLAY,
                WiredStartupEvent.TUN_WRITE_SUCCEEDED,
                WiredStartupEvent.SYN_ACK_OBSERVED,
                WiredStartupEvent.USB_OUT_NOT_READY,
            ),
        ),
        WiredStartupFixture(
            "tun-write-with-no-kernel-reply",
            listOf(
                WiredStartupEvent.START_SESSION_SENT,
                WiredStartupEvent.IPV6_SYN_TO_AIRPLAY,
                WiredStartupEvent.TUN_WRITE_SUCCEEDED,
            ),
        ),
        WiredStartupFixture(
            "tcp-accept-only",
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.TCP_ACCEPTED),
        ),
        WiredStartupFixture(
            "rtsp-active-only",
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.TCP_ACCEPTED, WiredStartupEvent.RTSP_ACTIVE),
        ),
        WiredStartupFixture("main-setup", listOf(WiredStartupEvent.MAIN_SETUP)),
        WiredStartupFixture("alt-setup-only", listOf(WiredStartupEvent.ALT_SETUP)),
        WiredStartupFixture(
            "recovered-then-second-loss",
            listOf(WiredStartupEvent.SESSION_RECOVERED, WiredStartupEvent.SECOND_LOSS),
        ),
    )

    val microphoneRequests = listOf(
        MicrophoneRequestFixture("input-false-valid-port", "VOICE", isInput = false, dataPort = 7000, permissionGranted = true),
        MicrophoneRequestFixture("siri", "SIRI", isInput = true, dataPort = 7001, permissionGranted = true),
        MicrophoneRequestFixture("phone", "PHONE", isInput = true, dataPort = 7002, permissionGranted = true),
        MicrophoneRequestFixture("voice-message", "VOICE_MESSAGE", isInput = true, dataPort = 7003, permissionGranted = true),
    )

    val localSplit55 = SplitGeometryFixture(widthPixels = 1920, heightPixels = 1080, carPlayFraction = 0.55f)
}

internal data class NtbFixtures(
    val boundaryBlock: ByteArray,
    val optionalZeroShortPad: ByteArray,
    val splitReads: List<ByteArray>,
    val coalescedBlocks: ByteArray,
    val truncatedHeader: ByteArray,
    val corruptNdpOffset: ByteArray,
)

internal data class UsbEndpointDescriptorFixture(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int = 0,
) {
    init {
        require(address in 0..255)
        require(attributes in 0..255)
        require(maxPacketSize > 0)
        require(interval in 0..255)
    }

    fun materialize(): UsbEndpoint {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(address)
            parcel.writeInt(attributes)
            parcel.writeInt(maxPacketSize)
            parcel.writeInt(interval)
            parcel.setDataPosition(0)
            UsbEndpoint.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}

/** This harness covers scripted USB bulk-IN request dispatch only; it is not device evidence. */
internal class RobolectricUsbInHarness internal constructor(
    val connection: UsbDeviceConnection,
    private val shadow: Step4UsbDeviceConnectionShadow,
) {
    fun queuedBufferCapacities(): List<Int> = shadow.queuedBufferCapacities()
    fun incomingPacketCount(): Int = shadow.incomingPacketCount()
}

internal enum class RequestWaitOutcome {
    TIMEOUT,
    NULL_RESULT,
    COMPLETE_CURRENT,
    COMPLETE_UNEXPECTED,
}

internal class FakeRequestWaitDispatcher(outcomes: List<RequestWaitOutcome>) {
    private val pending = ArrayDeque(outcomes)

    fun next(): RequestWaitOutcome {
        check(pending.isNotEmpty()) { "requestWait fixture has no configured outcome" }
        return pending.removeFirst()
    }
}

@Implements(UsbDeviceConnection::class)
internal class Step4UsbDeviceConnectionShadow {
    private val incomingPackets = ArrayDeque<ByteArray>()
    private val queuedRequests = IdentityHashMap<UsbRequest, ByteBuffer>()
    private val queueOutcomes = ArrayDeque<Boolean>()
    private val bulkTransferOutcomes = ArrayDeque<Int>()
    private val queueBufferSizes = mutableListOf<Int>()
    private lateinit var waitDispatcher: FakeRequestWaitDispatcher

    fun configure(
        packets: List<ByteArray>,
        configuredQueueOutcomes: List<Boolean>,
        waitOutcomes: List<RequestWaitOutcome>,
        configuredBulkTransferOutcomes: List<Int>,
    ) {
        incomingPackets.clear()
        packets.forEach { incomingPackets.add(it.copyOf()) }
        queueOutcomes.clear()
        queueOutcomes.addAll(configuredQueueOutcomes)
        bulkTransferOutcomes.clear()
        bulkTransferOutcomes.addAll(configuredBulkTransferOutcomes)
        queueBufferSizes.clear()
        waitDispatcher = FakeRequestWaitDispatcher(waitOutcomes)
    }

    fun queueRequest(request: UsbRequest, buffer: ByteBuffer): Boolean {
        check(queueOutcomes.isNotEmpty()) { "USB request queue result must be configured explicitly" }
        queueBufferSizes += buffer.remaining()
        val queued = queueOutcomes.removeFirst()
        if (queued) queuedRequests[request] = buffer
        return queued
    }

    fun cancelRequest(request: UsbRequest) {
        queuedRequests.remove(request)
    }

    fun queuedBufferCapacities(): List<Int> = queueBufferSizes.toList()
    fun incomingPacketCount(): Int = incomingPackets.size

    @Implementation
    protected fun requestWait(timeoutMillis: Long): UsbRequest? {
        require(timeoutMillis > 0L)
        return when (waitDispatcher.next()) {
            RequestWaitOutcome.TIMEOUT -> throw TimeoutException("fixture timeout")
            RequestWaitOutcome.NULL_RESULT -> null
            RequestWaitOutcome.COMPLETE_CURRENT -> {
                check(incomingPackets.isNotEmpty()) { "USB completion needs an explicit inbound packet" }
                val request = queuedRequests.keys.firstOrNull()
                    ?: error("no queued USB request is available to complete")
                val buffer = queuedRequests.remove(request) ?: error("queued request has no buffer")
                val packet = incomingPackets.removeFirst()
                check(packet.size <= buffer.remaining()) { "inbound fixture exceeds the queued buffer" }
                buffer.put(packet)
                request
            }
            RequestWaitOutcome.COMPLETE_UNEXPECTED -> ReflectionHelpers.callConstructor(UsbRequest::class.java)
        }
    }

    @Implementation
    protected fun close() {
        queuedRequests.clear()
    }

    @Implementation(minSdk = 18)
    protected fun bulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        require(timeout > 0)
        check(endpoint.maxPacketSize > 0)
        check(bulkTransferOutcomes.isNotEmpty()) { "USB OUT result must be configured explicitly" }
        return bulkTransferOutcomes.removeFirst()
    }
}

@Implements(UsbRequest::class)
internal class Step4UsbRequestShadow {
    @RealObject private lateinit var request: UsbRequest

    private var connection: UsbDeviceConnection? = null

    @Implementation
    protected fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        require(endpoint.maxPacketSize > 0)
        this.connection = connection
        return true
    }

    @Implementation
    protected fun queue(buffer: ByteBuffer): Boolean = owner().queueRequest(request, buffer)

    @Implementation(minSdk = 26)
    protected fun queue(buffer: ByteBuffer, length: Int): Boolean {
        require(length in 0..buffer.remaining())
        val originalLimit = buffer.limit()
        return try {
            buffer.limit(buffer.position() + length)
            owner().queueRequest(request, buffer)
        } finally {
            buffer.limit(originalLimit)
        }
    }

    @Implementation
    protected fun cancel(): Boolean {
        owner().cancelRequest(request)
        return true
    }

    @Implementation
    protected fun close() {
        connection = null
    }

    private fun owner(): Step4UsbDeviceConnectionShadow =
        Shadow.extract(connection ?: error("USB request is not initialized"))
}

internal enum class TunWriteOutcome {
    WRITTEN,
    NOT_READY,
    FAILED,
}

internal class FakeTun(
    writeOutcomes: List<TunWriteOutcome>,
    kernelReplies: List<ByteArray?>,
) {
    private val outcomes = ArrayDeque(writeOutcomes)
    private val replies = ArrayDeque<KernelReply>().apply { kernelReplies.forEach { add(KernelReply(it?.copyOf())) } }
    private val writtenPackets = mutableListOf<ByteArray>()

    fun writeToKernel(packet: ByteArray): TunWriteOutcome {
        check(outcomes.isNotEmpty()) { "TUN write outcome must be configured explicitly" }
        val outcome = outcomes.removeFirst()
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

    private val tasks = java.util.PriorityQueue<Task>(compareBy<Task>({ it.deadlineMillis }, { it.sequence }))
    private var nextSequence = 0L

    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        require(delayMillis >= 0L)
        val task = Task(clock.nowMillis + delayMillis, nextSequence++, action)
        tasks += task
        return { task.cancelled = true }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0L)
        val target = clock.nowMillis + deltaMillis
        while (tasks.peek()?.deadlineMillis?.let { it <= target } == true) {
            val task = tasks.remove()
            clock.advanceTo(task.deadlineMillis)
            if (!task.cancelled) task.action()
        }
        clock.advanceTo(target)
    }
}

internal data class WiredStartupFixture(val name: String, val events: List<WiredStartupEvent>)

internal enum class WiredStartupEvent {
    START_SESSION_SENT,
    IPV4_SYN_TO_AIRPLAY,
    IPV6_SYN_TO_AIRPLAY,
    TUN_WRITE_SUCCEEDED,
    SYN_ACK_OBSERVED,
    USB_OUT_NOT_READY,
    TCP_ACCEPTED,
    RTSP_ACTIVE,
    MAIN_SETUP,
    ALT_SETUP,
    SESSION_RECOVERED,
    SECOND_LOSS,
}

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
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val opaquePayload: ByteArray,
)
