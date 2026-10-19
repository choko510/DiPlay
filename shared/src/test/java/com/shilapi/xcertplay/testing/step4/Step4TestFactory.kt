package com.shilapi.xcertplay.testing.step4

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import com.shilapi.xcertplay.transport.Ntb16Codec
import org.robolectric.Shadows
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUsbDeviceConnection
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

internal object Step4TestFactory {
    fun usbConnection(): UsbConnectionFixture {
        val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        return UsbConnectionFixture(connection, Shadows.shadowOf(connection), NCM_ENDPOINTS)
    }

    fun ncmReads(
        frames: List<ByteArray>,
        optionalPadAfterBlocks: Set<Int> = emptySet(),
        readChunkSizes: List<Int> = emptyList(),
    ): NcmReadFixture {
        require(frames.isNotEmpty())
        require(optionalPadAfterBlocks.all { it in frames.indices })
        require(readChunkSizes.all { it > 0 })
        val blocks = frames.mapIndexed { index, frame ->
            val blockWithCodecPad = Ntb16Codec.build(frame.copyOf(), index)
            blockWithCodecPad.copyOf(blockLength(blockWithCodecPad))
        }
        val stream = ByteArrayOutputStream()
        blocks.forEachIndexed { index, block ->
            stream.write(block)
            if (index in optionalPadAfterBlocks) {
                require(block.size % 512 == 0)
                stream.write(0)
            }
        }
        val bytes = stream.toByteArray()
        val reads = splitReads(bytes, readChunkSizes)
        return NcmReadFixture(
            blocks = blocks,
            reads = reads,
            expectedDatagrams = frames.map(ByteArray::copyOf),
        )
    }

    fun malformedNcmReads(): List<NamedNcmFailureFixture> {
        val badNdp = ncmReads(listOf(ByteArray(32))).blocks.single().copyOf().apply {
            this[10] = 0xff.toByte()
            this[11] = 0x7f.toByte()
        }
        return listOf(
            NamedNcmFailureFixture("no-read", emptyList()),
            NamedNcmFailureFixture("zero-byte-read", listOf(ByteArray(0))),
            NamedNcmFailureFixture("short-nth", listOf(ByteArray(8))),
            NamedNcmFailureFixture("invalid-ndp-offset", listOf(badNdp)),
            NamedNcmFailureFixture("corrupt-garbage", listOf(ByteArray(128) { 0x5a })),
        )
    }

    fun requestWaitDispatcher(vararg outcomes: UsbRequestWaitOutcome): UsbRequestWaitDispatcher =
        UsbRequestWaitDispatcher(outcomes.toList())

    fun usbReadScenarios(): List<UsbReadScenario> = listOf(
        UsbReadScenario("queue32k-complete", 32 * 1024, true, UsbRequestWaitOutcome.Completed("bulk-in", 32 * 1024)),
        UsbReadScenario("queue32k-false", 32 * 1024, false, null),
        UsbReadScenario("queued-timeout", 32 * 1024, true, UsbRequestWaitOutcome.Timeout),
        UsbReadScenario("queued-null", 32 * 1024, true, UsbRequestWaitOutcome.NullResult),
        UsbReadScenario("unexpected-endpoint-completion", 32 * 1024, true, UsbRequestWaitOutcome.UnexpectedCompletion("status-in")),
    )

    fun tun(inboundPackets: List<ByteArray>, writeOutcomes: List<TunWriteOutcome>): FakeTun =
        FakeTun(inboundPackets, writeOutcomes)

    fun virtualScheduler(startMillis: Long = 0): VirtualScheduler = VirtualScheduler(VirtualClock(startMillis))

    fun pcm16(
        samples: ShortArray,
        sampleRate: Int = 48_000,
        channelCount: Int = 2,
    ): Pcm16Fixture {
        require(sampleRate > 0)
        require(channelCount > 0)
        require(samples.size % channelCount == 0)
        val bytes = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        return Pcm16Fixture(sampleRate, channelCount, bytes.array())
    }

    fun videoPayload(
        width: Int = 16,
        height: Int = 16,
        presentationTimeUs: Long = 0,
        seed: Int = 0,
    ): VideoPayloadFixture {
        require(width > 0 && height > 0)
        require(presentationTimeUs >= 0)
        return VideoPayloadFixture(
            codec = "opaque-test-payload",
            width = width,
            height = height,
            presentationTimeUs = presentationTimeUs,
            bytes = ByteArray(width * height) { (it + seed).toByte() },
        )
    }

    fun wiredAttempt(vararg events: WiredAttemptEvent): WiredAttemptFixture =
        WiredAttemptFixture(events.toList())

    fun minimumWiredAttempts(): List<NamedWiredAttemptFixture> = listOf(
        NamedWiredAttemptFixture("start-session-no-syn", wiredAttempt(WiredAttemptEvent.START_SESSION_SENT)),
        NamedWiredAttemptFixture(
            "ipv4-syn-only",
            wiredAttempt(WiredAttemptEvent.START_SESSION_SENT, WiredAttemptEvent.IPV4_SYN),
        ),
        NamedWiredAttemptFixture(
            "ipv6-tun-synack-not-ready",
            wiredAttempt(
                WiredAttemptEvent.START_SESSION_SENT,
                WiredAttemptEvent.IPV6_SYN,
                WiredAttemptEvent.TUN_WRITE_SUCCESS,
                WiredAttemptEvent.SYN_ACK_NOT_READY,
            ),
        ),
        NamedWiredAttemptFixture(
            "tun-reply-none",
            wiredAttempt(WiredAttemptEvent.START_SESSION_SENT, WiredAttemptEvent.IPV6_SYN, WiredAttemptEvent.TUN_REPLY_NONE),
        ),
        NamedWiredAttemptFixture("tcp-accept-only", wiredAttempt(WiredAttemptEvent.TCP_ACCEPTED)),
        NamedWiredAttemptFixture(
            "airplay-control-accepted-only",
            wiredAttempt(WiredAttemptEvent.AIRPLAY_CONTROL_ACCEPTED),
        ),
        NamedWiredAttemptFixture("rtsp-active-only", wiredAttempt(WiredAttemptEvent.RTSP_ACTIVE)),
        NamedWiredAttemptFixture("alt-setup-only", wiredAttempt(WiredAttemptEvent.ALT_SETUP)),
        NamedWiredAttemptFixture("main-first-frame", wiredAttempt(WiredAttemptEvent.MAIN_SETUP, WiredAttemptEvent.MAIN_FIRST_FRAME)),
        NamedWiredAttemptFixture(
            "recovered-then-second-loss",
            wiredAttempt(
                WiredAttemptEvent.HOST_RETRY_SCHEDULED,
                WiredAttemptEvent.SESSION_ACTIVE,
                WiredAttemptEvent.SESSION_LOST_AGAIN,
                WiredAttemptEvent.HOST_RETRY_SCHEDULED,
            ),
        ),
    )

    fun microphoneRequests(): List<MicrophoneSetupFixture> = listOf(
        MicrophoneSetupFixture("siri", AudioRequestKind.SIRI, input = true, dataPort = 49_100),
        MicrophoneSetupFixture("phone", AudioRequestKind.PHONE, input = true, dataPort = 49_101),
        MicrophoneSetupFixture("voice-message", AudioRequestKind.VOICE_MESSAGE, input = true, dataPort = 49_102),
        MicrophoneSetupFixture("explicit-false-with-port", AudioRequestKind.VOICE_MESSAGE, input = false, dataPort = 49_103),
        MicrophoneSetupFixture("music-output", AudioRequestKind.MEDIA, input = false, dataPort = null),
    )

    fun localSplitViewport(
        width: Int = 1_920,
        height: Int = 1_080,
        carPlayFraction: Float = 0.55f,
    ): LocalSplitViewportFixture = LocalSplitViewportFixture(width, height, carPlayFraction)

    private fun splitReads(bytes: ByteArray, chunkSizes: List<Int>): List<ByteArray> {
        if (chunkSizes.isEmpty()) return listOf(bytes)
        val reads = ArrayList<ByteArray>()
        var offset = 0
        var chunkIndex = 0
        while (offset < bytes.size) {
            val requestedSize = chunkSizes.getOrElse(chunkIndex) { bytes.size - offset }
            val size = requestedSize.coerceAtMost(bytes.size - offset)
            reads += bytes.copyOfRange(offset, offset + size)
            offset += size
            chunkIndex++
        }
        return reads
    }

    private fun blockLength(block: ByteArray): Int =
        (block[8].toInt() and 0xff) or ((block[9].toInt() and 0xff) shl 8)

    private val NCM_ENDPOINTS = listOf(
        UsbEndpointDescriptorFixture(
            address = UsbConstants.USB_DIR_IN or 1,
            attributes = UsbConstants.USB_ENDPOINT_XFER_BULK,
            maxPacketSize = 512,
            interval = 0,
        ),
        UsbEndpointDescriptorFixture(
            address = UsbConstants.USB_DIR_OUT or 2,
            attributes = UsbConstants.USB_ENDPOINT_XFER_BULK,
            maxPacketSize = 512,
            interval = 0,
        ),
        UsbEndpointDescriptorFixture(
            address = UsbConstants.USB_DIR_IN or 3,
            attributes = UsbConstants.USB_ENDPOINT_XFER_INT,
            maxPacketSize = 16,
            interval = 9,
        ),
    )
}

internal data class UsbEndpointDescriptorFixture(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
) {
    init {
        require(maxPacketSize > 0)
        require(interval >= 0)
    }
}

internal class UsbConnectionFixture(
    val connection: UsbDeviceConnection,
    val shadow: ShadowUsbDeviceConnection,
    val endpointDescriptors: List<UsbEndpointDescriptorFixture>,
) {
    fun readIncoming(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        val request = UsbRequest()
        check(request.initialize(connection, Shadow.newInstanceOf(UsbEndpoint::class.java)))
        return try {
            shadow.writeIncomingData(bytes.copyOf())
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            check(request.queue(buffer))
            check(connection.requestWait(1_000) === request)
            buffer.flip()
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            request.close()
        }
    }
}

internal data class NcmReadFixture(
    val blocks: List<ByteArray>,
    val reads: List<ByteArray>,
    val expectedDatagrams: List<ByteArray>,
)

internal data class NamedNcmFailureFixture(val name: String, val reads: List<ByteArray>)

internal sealed interface UsbRequestWaitOutcome {
    data object Timeout : UsbRequestWaitOutcome
    data object NullResult : UsbRequestWaitOutcome
    data class Completed(val requestId: String, val byteCount: Int) : UsbRequestWaitOutcome
    data class UnexpectedCompletion(val requestId: String) : UsbRequestWaitOutcome
}

internal data class UsbReadScenario(
    val name: String,
    val bufferBytes: Int,
    val queueSucceeded: Boolean,
    val waitOutcome: UsbRequestWaitOutcome?,
)

internal class UsbRequestWaitDispatcher(outcomes: List<UsbRequestWaitOutcome>) {
    private val pending = ArrayDeque(outcomes)

    fun requestWait(): UsbRequestWaitOutcome =
        checkNotNull(pending.pollFirst()) { "No requestWait outcome was configured" }

    fun pendingCount(): Int = pending.size
}

internal sealed interface TunWriteOutcome {
    data object Success : TunWriteOutcome
    data class Failure(val reason: String) : TunWriteOutcome
}

internal class FakeTun(inboundPackets: List<ByteArray>, writeOutcomes: List<TunWriteOutcome>) {
    private val inbound = ArrayDeque(inboundPackets.map(ByteArray::copyOf))
    private val pendingWriteOutcomes = ArrayDeque(writeOutcomes)
    private val sentPackets = ArrayList<ByteArray>()

    fun read(): ByteArray? = inbound.pollFirst()?.copyOf()

    fun write(packet: ByteArray): TunWriteOutcome {
        val outcome = checkNotNull(pendingWriteOutcomes.pollFirst()) { "No TUN write outcome was configured" }
        if (outcome == TunWriteOutcome.Success) sentPackets += packet.copyOf()
        return outcome
    }

    fun sentPackets(): List<ByteArray> = sentPackets.map(ByteArray::copyOf)
}

internal class VirtualClock(startMillis: Long) {
    var nowMillis: Long = startMillis
        private set

    internal fun setNowMillis(value: Long) {
        require(value >= nowMillis)
        nowMillis = value
    }
}

internal class VirtualScheduler(val clock: VirtualClock) {
    private data class Task(
        val dueMillis: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = ArrayList<Task>()
    private var nextSequence = 0L

    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        require(delayMillis >= 0)
        val task = Task(clock.nowMillis + delayMillis, nextSequence++, action)
        tasks += task
        return { task.cancelled = true }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0)
        val targetMillis = clock.nowMillis + deltaMillis
        while (true) {
            val next = tasks
                .asSequence()
                .filter { !it.cancelled && it.dueMillis <= targetMillis }
                .minWithOrNull(compareBy<Task> { it.dueMillis }.thenBy { it.sequence })
                ?: break
            tasks.remove(next)
            clock.setNowMillis(next.dueMillis)
            if (!next.cancelled) next.action()
        }
        tasks.removeAll { it.cancelled }
        clock.setNowMillis(targetMillis)
    }

    fun pendingCount(): Int = tasks.count { !it.cancelled }
}

internal data class Pcm16Fixture(
    val sampleRate: Int,
    val channelCount: Int,
    val littleEndianBytes: ByteArray,
)

internal data class VideoPayloadFixture(
    val codec: String,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val bytes: ByteArray,
)

internal enum class WiredAttemptEvent {
    START_SESSION_SENT,
    IPV4_SYN,
    IPV6_SYN,
    TUN_WRITE_SUCCESS,
    TUN_WRITE_FAILURE,
    TUN_REPLY_NONE,
    SYN_ACK_NOT_READY,
    TCP_ACCEPTED,
    AIRPLAY_CONTROL_ACCEPTED,
    RTSP_ACTIVE,
    ALT_SETUP,
    MAIN_SETUP,
    MAIN_FIRST_FRAME,
    HOST_RETRY_SCHEDULED,
    SESSION_ACTIVE,
    SESSION_LOST_AGAIN,
}

internal data class WiredAttemptFixture(val events: List<WiredAttemptEvent>)

internal data class NamedWiredAttemptFixture(val name: String, val attempt: WiredAttemptFixture)

internal enum class AudioRequestKind {
    SIRI,
    PHONE,
    VOICE_MESSAGE,
    MEDIA,
}

internal data class MicrophoneSetupFixture(
    val name: String,
    val kind: AudioRequestKind,
    val input: Boolean?,
    val dataPort: Int?,
)

internal data class LocalSplitViewportFixture(
    val width: Int,
    val height: Int,
    val carPlayFraction: Float,
)
