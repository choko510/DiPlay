package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.sin

internal class VirtualClock(initialNanos: Long = 0) {
    var nowNanos: Long = initialNanos
        private set

    fun advanceByMillis(milliseconds: Long) {
        require(milliseconds >= 0)
        advanceTo(Math.addExact(nowNanos, Math.multiplyExact(milliseconds, NANOS_PER_MILLISECOND)))
    }

    internal fun advanceTo(targetNanos: Long) {
        require(targetNanos >= nowNanos)
        nowNanos = targetNanos
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal class VirtualScheduler(private val clock: VirtualClock) {
    private val tasks = PriorityQueue(compareBy<Task> { it.deadlineNanos }.thenBy { it.sequence })
    private var sequence = 0L

    fun schedule(delayMillis: Long, action: () -> Unit): Handle {
        require(delayMillis >= 0)
        val deadline = Math.addExact(
            clock.nowNanos,
            Math.multiplyExact(delayMillis, NANOS_PER_MILLISECOND),
        )
        val task = Task(deadline, sequence++, action)
        tasks.add(task)
        return Handle { task.cancelled = true }
    }

    fun advanceByMillis(milliseconds: Long): Int {
        require(milliseconds >= 0)
        val target = Math.addExact(
            clock.nowNanos,
            Math.multiplyExact(milliseconds, NANOS_PER_MILLISECOND),
        )
        var executed = 0
        while (tasks.peek()?.deadlineNanos?.let { it <= target } == true) {
            val task = tasks.remove()
            clock.advanceTo(task.deadlineNanos)
            if (!task.cancelled) {
                task.action()
                executed++
            }
        }
        clock.advanceTo(target)
        return executed
    }

    class Handle internal constructor(private val cancelAction: () -> Unit) {
        fun cancel() = cancelAction()
    }

    private class Task(
        val deadlineNanos: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal class FakeTunDevice {
    private val incoming = ArrayDeque<ByteArray>()
    private val writeOutcomes = ArrayDeque<WriteOutcome>()
    val writtenPackets = mutableListOf<ByteArray>()

    fun enqueueIncoming(packet: ByteArray) {
        incoming.addLast(packet.copyOf())
    }

    fun enqueueWriteOutcome(success: Boolean) {
        writeOutcomes.addLast(if (success) WriteOutcome.Success else WriteOutcome.Rejected)
    }

    fun enqueueWriteException(error: RuntimeException) {
        writeOutcomes.addLast(WriteOutcome.Exception(error))
    }

    fun read(): ByteArray? = if (incoming.isEmpty()) null else incoming.removeFirst().copyOf()

    fun write(packet: ByteArray): Boolean {
        val outcome = if (writeOutcomes.isEmpty()) WriteOutcome.Rejected else writeOutcomes.removeFirst()
        return when (outcome) {
            WriteOutcome.Success -> {
                writtenPackets.add(packet.copyOf())
                true
            }
            WriteOutcome.Rejected -> false
            is WriteOutcome.Exception -> throw outcome.error
        }
    }

    private sealed class WriteOutcome {
        data object Success : WriteOutcome()
        data object Rejected : WriteOutcome()
        data class Exception(val error: RuntimeException) : WriteOutcome()
    }
}

internal enum class WiredFixtureEventKind {
    START_SESSION_SENT,
    IPV4_SYN,
    IPV6_SYN,
    TUN_WRITE_SUCCESS,
    TUN_WRITE_FAILURE,
    SYN_ACK_OBSERVED,
    NCM_NOT_READY,
    TCP_ACCEPTED,
    RTSP_ACTIVE,
    MAIN_SETUP,
    ALT_SETUP,
    MAIN_FRAME_SUBMITTED,
    RECONNECT_FAILURE,
    RETRY_PENDING,
    SESSION_ACTIVE,
    SECOND_LOSS,
}

internal data class TimedWiredFixtureEvent(
    val atMillis: Long,
    val kind: WiredFixtureEventKind,
)

internal data class WiredScenarioFixture(
    val name: String,
    val events: List<TimedWiredFixtureEvent>,
) {
    init {
        require(name.isNotBlank())
        require(events.zipWithNext().all { (left, right) -> left.atMillis <= right.atMillis })
        require(events.all { it.atMillis >= 0 })
    }
}

internal object WiredScenarioFixtures {
    fun startSessionNoSyn() = scenario("start-session-no-syn", WiredFixtureEventKind.START_SESSION_SENT)

    fun ipv4SynOnly() = scenario(
        "ipv4-syn-only",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.IPV4_SYN,
    )

    fun ipv6SynAckNotReady() = scenario(
        "ipv6-syn-ack-not-ready",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.IPV6_SYN,
        WiredFixtureEventKind.TUN_WRITE_SUCCESS,
        WiredFixtureEventKind.SYN_ACK_OBSERVED,
        WiredFixtureEventKind.NCM_NOT_READY,
    )

    fun tunReplyNone() = scenario(
        "tun-reply-none",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.IPV6_SYN,
        WiredFixtureEventKind.TUN_WRITE_SUCCESS,
    )

    fun tcpAcceptOnly() = scenario(
        "tcp-accept-only",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.TCP_ACCEPTED,
    )

    fun rtspActiveOnly() = scenario(
        "rtsp-active-only",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.RTSP_ACTIVE,
    )

    fun altOnly() = scenario(
        "alt-only",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.ALT_SETUP,
    )

    fun mainAndAltSeparate() = scenario(
        "main-alt-separate",
        WiredFixtureEventKind.START_SESSION_SENT,
        WiredFixtureEventKind.MAIN_SETUP,
        WiredFixtureEventKind.ALT_SETUP,
        WiredFixtureEventKind.MAIN_FRAME_SUBMITTED,
    )

    fun recoveredThenSecondLoss() = scenario(
        "recovered-then-second-loss",
        WiredFixtureEventKind.RECONNECT_FAILURE,
        WiredFixtureEventKind.RETRY_PENDING,
        WiredFixtureEventKind.SESSION_ACTIVE,
        WiredFixtureEventKind.SECOND_LOSS,
    )

    private fun scenario(name: String, vararg kinds: WiredFixtureEventKind) = WiredScenarioFixture(
        name,
        kinds.mapIndexed { index, kind -> TimedWiredFixtureEvent(index * 25L, kind) },
    )
}

internal enum class MicrophoneFixtureKind { SIRI, PHONE, VOICE_MESSAGE }

internal data class MicrophoneRequestFixture(
    val kind: MicrophoneFixtureKind,
    val inputEnabled: Boolean,
    val audioPort: Int,
) {
    init {
        require(audioPort in 1..0xffff)
    }
}

internal object MicrophoneFixtures {
    private const val TEST_AUDIO_PORT = 49_152

    fun siri() = MicrophoneRequestFixture(MicrophoneFixtureKind.SIRI, true, TEST_AUDIO_PORT)
    fun phone() = MicrophoneRequestFixture(MicrophoneFixtureKind.PHONE, true, TEST_AUDIO_PORT)
    fun voiceMessage() = MicrophoneRequestFixture(MicrophoneFixtureKind.VOICE_MESSAGE, true, TEST_AUDIO_PORT)
    fun explicitlyDisabled() = MicrophoneRequestFixture(MicrophoneFixtureKind.VOICE_MESSAGE, false, TEST_AUDIO_PORT)
}

internal object PcmFixtures {
    fun silence(frameCount: Int, channels: Int = 2): FloatArray {
        require(frameCount >= 0 && channels > 0)
        return FloatArray(frameCount * channels)
    }

    fun impulse(frameCount: Int, channels: Int = 2, channel: Int = 0): FloatArray {
        require(frameCount > 0 && channels > 0 && channel in 0 until channels)
        return FloatArray(frameCount * channels).also { it[channel] = 1f }
    }

    fun sine(sampleRate: Int, frequencyHz: Double, frameCount: Int, channels: Int = 2): FloatArray {
        require(sampleRate > 0 && frequencyHz >= 0 && frameCount >= 0 && channels > 0)
        return FloatArray(frameCount * channels) { index ->
            val frame = index / channels
            sin(2.0 * PI * frequencyHz * frame / sampleRate).toFloat()
        }
    }
}

internal data class VideoFrameFixture(
    val codec: VideoCodec,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
    val nalus: ByteArray,
)

internal object VideoFixtures {
    fun frame(
        codec: VideoCodec = VideoCodec.H264,
        presentationTimeUs: Long = 0,
        keyFrame: Boolean = true,
    ): VideoFrameFixture {
        require(presentationTimeUs >= 0)
        val nalus = when (codec) {
            VideoCodec.H264 -> if (keyFrame) {
                byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte())
            } else {
                byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte())
            }
            VideoCodec.H265 -> if (keyFrame) {
                byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x80.toByte())
            } else {
                byteArrayOf(0, 0, 0, 1, 0x02, 0x01, 0x80.toByte())
            }
        }
        return VideoFrameFixture(codec, presentationTimeUs, keyFrame, nalus)
    }
}
