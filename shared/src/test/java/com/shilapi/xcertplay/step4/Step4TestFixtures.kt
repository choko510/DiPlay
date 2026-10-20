package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.Ntb16Codec
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.sin

internal enum class StartupSignal {
    START_SESSION_SENT,
    IPV4_SYN,
    IPV6_SYN,
    TUN_WRITE_SUCCESS,
    TUN_WRITE_FAILURE,
    TUN_SYN_ACK,
    NCM_OUT_NOT_READY,
    TCP_ACCEPTED,
    RTSP_ACTIVE,
    MAIN_SETUP,
    ALT_SETUP,
    MAIN_FRAME_SUBMITTED,
    SESSION_RECOVERED,
    SESSION_LOST_AGAIN,
}

internal data class StartupTraceFixture(
    val name: String,
    val signals: List<StartupSignal>,
)

internal object StartupTraceFixtures {
    val startSessionWithoutSyn = StartupTraceFixture(
        "start-session-without-syn",
        listOf(StartupSignal.START_SESSION_SENT),
    )
    val ipv4SynOnly = StartupTraceFixture(
        "ipv4-syn-only",
        listOf(StartupSignal.START_SESSION_SENT, StartupSignal.IPV4_SYN),
    )
    val ipv6SynToTunFailure = StartupTraceFixture(
        "ipv6-syn-to-tun-failure",
        listOf(StartupSignal.START_SESSION_SENT, StartupSignal.IPV6_SYN, StartupSignal.TUN_WRITE_FAILURE),
    )
    val ipv6SynTunReplyAbsent = StartupTraceFixture(
        "ipv6-syn-tun-reply-absent",
        listOf(StartupSignal.START_SESSION_SENT, StartupSignal.IPV6_SYN, StartupSignal.TUN_WRITE_SUCCESS),
    )
    val ipv6SynAckNcmNotReady = StartupTraceFixture(
        "ipv6-syn-ack-ncm-not-ready",
        listOf(
            StartupSignal.START_SESSION_SENT,
            StartupSignal.IPV6_SYN,
            StartupSignal.TUN_WRITE_SUCCESS,
            StartupSignal.TUN_SYN_ACK,
            StartupSignal.NCM_OUT_NOT_READY,
        ),
    )
    val tcpAcceptedWithoutRtsp = StartupTraceFixture(
        "tcp-accepted-without-rtsp",
        listOf(StartupSignal.START_SESSION_SENT, StartupSignal.TCP_ACCEPTED),
    )
    val rtspActiveWithoutMainFrame = StartupTraceFixture(
        "rtsp-active-without-main-frame",
        listOf(StartupSignal.START_SESSION_SENT, StartupSignal.RTSP_ACTIVE),
    )
    val mainAndAltAreDistinct = StartupTraceFixture(
        "main-and-alt-setup-are-distinct",
        listOf(StartupSignal.RTSP_ACTIVE, StartupSignal.MAIN_SETUP, StartupSignal.ALT_SETUP),
    )
    val recoveredThenLostAgain = StartupTraceFixture(
        "recovered-then-lost-again",
        listOf(StartupSignal.SESSION_RECOVERED, StartupSignal.SESSION_LOST_AGAIN),
    )
}

internal object Ntb16Fixture {
    fun single(frame: ByteArray, sequence: Int = 0): ByteArray = Ntb16Codec.build(frame, sequence)

    fun shortHeader(): ByteArray = single(ByteArray(16)).copyOf(11)

    fun corruptSignature(): ByteArray = single(ByteArray(16)).also { it[0] = (it[0].toInt() xor 0xff).toByte() }

    fun invalidNdpOffset(): ByteArray = single(ByteArray(16)).also {
        it[10] = 0xff.toByte()
        it[11] = 0x7f
    }

    fun garbage(bytes: Int): ByteArray {
        require(bytes >= 0)
        return ByteArray(bytes) { index -> (index * 31 + 7).toByte() }
    }

    fun split(block: ByteArray, firstChunkBytes: Int): List<ByteArray> {
        require(firstChunkBytes in 1 until block.size)
        return listOf(block.copyOfRange(0, firstChunkBytes), block.copyOfRange(firstChunkBytes, block.size))
    }

    fun coalesced(vararg blocks: ByteArray): ByteArray {
        require(blocks.isNotEmpty())
        return blocks.fold(ByteArray(0)) { result, block -> result + block }
    }

    fun exact512Boundary(padded: Boolean): Pair<ByteArray, ByteArray> {
        val frame = ByteArray(484) { index -> (index * 17).toByte() }
        val block = Ntb16Codec.build(frame, sequence = 0)
        check(block.size == 513 && block.last() == 0.toByte())
        return frame to if (padded) block else block.copyOf(block.size - 1)
    }
}

internal object EthernetPacketFixture {
    fun ipv6Tcp(sourcePort: Int = 49_152, destinationPort: Int = 7_000, flags: Int = 0x12): ByteArray {
        require(sourcePort in 0..0xffff && destinationPort in 0..0xffff && flags in 0..0xff)
        val ipv6 = ByteArray(60)
        ipv6[0] = 0x60
        putU16(ipv6, 4, 20)
        ipv6[6] = 6
        ipv6[7] = 64
        ipv6[8] = 0x20
        ipv6[23] = 1
        ipv6[24] = 0x20
        ipv6[39] = 2
        putU16(ipv6, 40, sourcePort)
        putU16(ipv6, 42, destinationPort)
        ipv6[52] = 0x50
        ipv6[53] = flags.toByte()
        return EthernetIpv6Codec.build(
            sourceMac = byteArrayOf(0x02, 0, 0, 0, 0, 1),
            destinationMac = byteArrayOf(0x02, 0, 0, 0, 0, 2),
            ipv6 = ipv6,
        )
    }

    fun ipv4Tcp(sourcePort: Int = 49_152, destinationPort: Int = 7_000, flags: Int = 0x02): ByteArray {
        require(sourcePort in 0..0xffff && destinationPort in 0..0xffff && flags in 0..0xff)
        val frame = ByteArray(14 + 20 + 20)
        byteArrayOf(0x02, 0, 0, 0, 0, 2).copyInto(frame, 0)
        byteArrayOf(0x02, 0, 0, 0, 0, 1).copyInto(frame, 6)
        frame[12] = 0x08
        frame[13] = 0x00
        frame[14] = 0x45
        putU16(frame, 16, 40)
        frame[22] = 64
        frame[23] = 6
        frame[26] = 192.toByte()
        frame[27] = 0.toByte()
        frame[28] = 2
        frame[29] = 1
        frame[30] = 192.toByte()
        frame[31] = 0.toByte()
        frame[32] = 2
        frame[33] = 2
        val tcpOffset = 14 + 20
        putU16(frame, tcpOffset, sourcePort)
        putU16(frame, tcpOffset + 2, destinationPort)
        frame[tcpOffset + 12] = 0x50
        frame[tcpOffset + 13] = flags.toByte()
        return frame
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}

internal data class SplitGeometryFixture(
    val carPlayFraction: Float,
    val youtubeFraction: Float,
) {
    init {
        require(carPlayFraction in 0f..1f)
        require(youtubeFraction in 0f..1f)
        require(kotlin.math.abs(carPlayFraction + youtubeFraction - 1f) < 0.0001f)
    }

    companion object {
        val LOCAL_55_45 = SplitGeometryFixture(0.55f, 0.45f)
    }
}

internal data class CarPlayInputRequestFixture(
    val name: String,
    val inputRequested: Boolean,
    val inputPort: Int?,
) {
    init {
        require(inputPort == null || inputPort in 1..0xffff)
    }
}

internal object CarPlayInputRequestFixtures {
    val declined = CarPlayInputRequestFixture("input-declined", inputRequested = false, inputPort = null)
    val siri = CarPlayInputRequestFixture("siri", inputRequested = true, inputPort = 49_152)
    val phone = CarPlayInputRequestFixture("phone", inputRequested = true, inputPort = 49_153)
    val voiceMessage = CarPlayInputRequestFixture("voice-message", inputRequested = true, inputPort = 49_154)
}

internal class VirtualClock(initialNanos: Long = 0L) {
    init {
        require(initialNanos >= 0L)
    }

    var nowNanos: Long = initialNanos
        private set

    fun advanceBy(nanos: Long) {
        require(nanos >= 0L)
        nowNanos = Math.addExact(nowNanos, nanos)
    }
}

internal class ManualScheduler(private val clock: VirtualClock) {
    internal data class Entry(
        val deadlineNanos: Long,
        val order: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    internal class Handle internal constructor(private val entry: Entry) {
        val isCancelled: Boolean get() = entry.cancelled
        fun cancel() {
            entry.cancelled = true
        }
    }

    private val entries = PriorityQueue(compareBy<Entry> { it.deadlineNanos }.thenBy { it.order })
    private var nextOrder = 0L

    fun scheduleAfter(delayNanos: Long, action: () -> Unit): Handle {
        require(delayNanos >= 0L)
        val entry = Entry(Math.addExact(clock.nowNanos, delayNanos), nextOrder++, action)
        entries.add(entry)
        return Handle(entry)
    }

    fun advanceBy(nanos: Long) {
        clock.advanceBy(nanos)
        while (entries.peek()?.deadlineNanos?.let { it <= clock.nowNanos } == true) {
            val entry = entries.remove()
            if (!entry.cancelled) entry.action()
        }
    }
}

internal class SimulatedTun {
    private val appInput = ArrayDeque<ByteArray>()
    private val kernelInput = ArrayDeque<ByteArray>()

    fun kernelSends(packet: ByteArray) {
        appInput.addLast(packet.copyOf())
    }

    fun appReceives(): ByteArray? = appInput.pollFirst()?.copyOf()

    fun appSends(packet: ByteArray) {
        kernelInput.addLast(packet.copyOf())
    }

    fun kernelReceives(): ByteArray? = kernelInput.pollFirst()?.copyOf()
}

internal object Pcm16Fixture {
    fun silence(frames: Int, channels: Int): ByteArray {
        require(frames >= 0)
        require(channels in 1..2)
        val sampleCount = Math.multiplyExact(frames, channels)
        return ByteArray(Math.multiplyExact(sampleCount, Short.SIZE_BYTES))
    }

    fun impulse(frames: Int, channels: Int, frame: Int, value: Short = Short.MAX_VALUE): ByteArray {
        require(frames > 0)
        require(channels in 1..2)
        require(frame in 0 until frames)
        val pcm = silence(frames, channels)
        repeat(channels) { channel ->
            val offset = (frame * channels + channel) * Short.SIZE_BYTES
            pcm[offset] = value.toByte()
            pcm[offset + 1] = (value.toInt() shr 8).toByte()
        }
        return pcm
    }

    fun sine(
        frames: Int,
        channels: Int,
        sampleRate: Int,
        frequencyHz: Double,
        amplitude: Double = 0.5,
    ): ByteArray {
        require(frames >= 0)
        require(channels in 1..2)
        require(sampleRate > 0)
        require(frequencyHz >= 0.0 && frequencyHz < sampleRate / 2.0)
        require(amplitude in 0.0..1.0)
        val sampleCount = Math.multiplyExact(frames, channels)
        val pcm = ByteArray(Math.multiplyExact(sampleCount, Short.SIZE_BYTES))
        repeat(frames) { frame ->
            val sample = (sin(2.0 * PI * frequencyHz * frame / sampleRate) * amplitude * Short.MAX_VALUE)
                .toInt()
                .toShort()
            repeat(channels) { channel ->
                val offset = (frame * channels + channel) * Short.SIZE_BYTES
                pcm[offset] = sample.toByte()
                pcm[offset + 1] = (sample.toInt() shr 8).toByte()
            }
        }
        return pcm
    }
}

internal data class VideoFrameFixture(
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(presentationTimeUs >= 0L)
        val pixelCount = Math.multiplyExact(width, height)
        require(rgba.size == Math.multiplyExact(pixelCount, 4))
    }

    companion object {
        fun solid(width: Int, height: Int, presentationTimeUs: Long, rgba: Int): VideoFrameFixture {
            require(width > 0 && height > 0)
            require(presentationTimeUs >= 0L)
            val pixelCount = Math.multiplyExact(width, height)
            val pixels = ByteArray(Math.multiplyExact(pixelCount, 4))
            repeat(pixelCount) { pixel ->
                val offset = pixel * 4
                pixels[offset] = (rgba ushr 24).toByte()
                pixels[offset + 1] = (rgba ushr 16).toByte()
                pixels[offset + 2] = (rgba ushr 8).toByte()
                pixels[offset + 3] = rgba.toByte()
            }
            return VideoFrameFixture(width, height, presentationTimeUs, pixels)
        }
    }
}
