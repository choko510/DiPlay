package com.shilapi.xcertplay.step4

import android.hardware.usb.ScriptedUsbDeviceConnection
import android.hardware.usb.UsbConstants
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmDiagnosticEvent
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

internal object Step4TestFixtures {
    data class NcmBridgeFixture(
        val bridge: NcmUsbBridge,
        val connection: ScriptedUsbDeviceConnection,
        val bulkIn: ScriptedUsbDeviceConnection.Endpoint,
        val bulkOut: ScriptedUsbDeviceConnection.Endpoint,
        val events: MutableList<NcmDiagnosticEvent>,
    )

    data class PcmBlock(
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Int,
        val samples: FloatArray,
    )

    data class VideoFrame(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val presentationTimeUs: Long,
        val bytes: ByteArray,
    )

    data class MicrophoneRequest(
        val audioType: Int?,
        val input: Boolean?,
        val dataPort: Int?,
        val sampleRate: Int?,
        val channels: Int?,
    )

    data class SplitGeometry(
        val displayWidth: Int,
        val displayHeight: Int,
        val carPlayFraction: Float,
        val carPlayWidth: Int,
        val youtubeWidth: Int,
    )

    fun ncmBridge(
        profile: NcmDiagnosticProfile = NcmDiagnosticProfile.SYNC_BULK_IN,
        attempt: Int = 1,
    ): NcmBridgeFixture {
        val connection = ScriptedUsbDeviceConnection()
        val bulkIn = ScriptedUsbDeviceConnection.Endpoint(
            0x81,
            UsbConstants.USB_ENDPOINT_XFER_BULK,
            512,
            0,
        )
        val bulkOut = ScriptedUsbDeviceConnection.Endpoint(
            0x02,
            UsbConstants.USB_ENDPOINT_XFER_BULK,
            512,
            0,
        )
        val events = java.util.Collections.synchronizedList(mutableListOf<NcmDiagnosticEvent>())
        val bridge = NcmUsbBridge(
            connection,
            bulkOut,
            bulkIn,
            null,
            null,
            "step4-test",
            { events.add(it) },
            profile,
            attempt,
            false,
            {},
        )
        return NcmBridgeFixture(bridge, connection, bulkIn, bulkOut, events)
    }

    fun ethernetFrame(size: Int = 60, seed: Int = 0): ByteArray {
        require(size >= 14)
        return ByteArray(size) { index ->
            when (index) {
                0 -> 0x02
                6 -> 0x04
                12 -> 0x86
                13 -> 0xdd
                else -> ((index * 31 + seed) and 0xff)
            }.toByte()
        }
    }

    fun ipv4TcpFrame(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        flags: Int = 0x02,
        sourceAddress: ByteArray = byteArrayOf(10, 0, 0, 2),
        destinationAddress: ByteArray = byteArrayOf(10, 0, 0, 1),
    ): ByteArray {
        require(sourcePort in 0..0xffff && destinationPort in 0..0xffff && flags in 0..0xff)
        require(sourceAddress.size == 4 && destinationAddress.size == 4)
        val frame = ethernetHeader(14 + 20 + 20, 0x0800)
        frame[14] = 0x45
        putU16(frame, 16, 40)
        frame[22] = 64
        frame[23] = 6
        sourceAddress.copyInto(frame, 26)
        destinationAddress.copyInto(frame, 30)
        putTcpHeader(frame, 34, sourcePort, destinationPort, flags)
        return frame
    }

    fun ipv6TcpFrame(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        flags: Int = 0x02,
        sourceAddress: ByteArray = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
        destinationAddress: ByteArray = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
    ): ByteArray {
        require(sourcePort in 0..0xffff && destinationPort in 0..0xffff && flags in 0..0xff)
        require(sourceAddress.size == 16 && destinationAddress.size == 16)
        val frame = ethernetHeader(14 + 40 + 20, 0x86dd)
        frame[14] = 0x60
        putU16(frame, 18, 20)
        frame[20] = 6
        frame[21] = 64
        sourceAddress.copyInto(frame, 22)
        destinationAddress.copyInto(frame, 38)
        putTcpHeader(frame, 54, sourcePort, destinationPort, flags)
        return frame
    }

    fun ntb(frame: ByteArray, sequence: Int = 1): ByteArray = Ntb16Codec.build(frame, sequence)

    fun cdcNcmDescriptors(controlId: Int = 3, dataId: Int = 4): ByteArray =
        bytes(9, 2, 72, 0, 2, 4, 0, 0x80, 50) +
            bytes(9, 4, controlId, 0, 1, 2, 0x0d, 0, 0) +
            bytes(5, 0x24, 0x06, controlId, dataId) +
            bytes(13, 0x24, 0x0f, 1, 0, 0, 0, 0, 0xdc, 0x05, 0, 0, 0) +
            bytes(6, 0x24, 0x1a, 0x10, 0x01, 0x21) +
            bytes(7, 5, 0x83, 3, 16, 0, 10) +
            bytes(9, 4, dataId, 1, 2, 0x0a, 0, 0, 0) +
            bytes(7, 5, 0x81, 2, 0, 2, 0) +
            bytes(7, 5, 0x02, 2, 0, 2, 0)

    fun pcmBlock(
        sampleRate: Int = 48_000,
        channels: Int = 2,
        frameCount: Int = 128,
        seed: Int = 0,
    ): PcmBlock {
        require(sampleRate > 0)
        require(channels in 1..2)
        require(frameCount > 0)
        val samples = FloatArray(frameCount * channels) { index ->
            (((index * 37 + seed) % 257) - 128) / 128f
        }
        return PcmBlock(sampleRate, channels, frameCount, samples)
    }

    fun videoFrame(
        mimeType: String = "video/avc",
        width: Int = 640,
        height: Int = 480,
        presentationTimeUs: Long = 1_000_000,
        byteCount: Int = 32,
        seed: Int = 0,
    ): VideoFrame {
        require(width > 0 && height > 0 && byteCount > 0)
        return VideoFrame(
            mimeType,
            width,
            height,
            presentationTimeUs,
            ByteArray(byteCount) { index -> ((index * 19 + seed) and 0xff).toByte() },
        )
    }

    fun splitGeometry(width: Int, height: Int, fraction: Float = 0.55f): SplitGeometry {
        require(width > 0 && height > 0)
        require(fraction.isFinite() && fraction in 0f..1f)
        val carPlayWidth = (width * fraction).toInt()
        return SplitGeometry(width, height, fraction, carPlayWidth, width - carPlayWidth)
    }

    private fun ethernetHeader(length: Int, etherType: Int): ByteArray = ByteArray(length).apply {
        byteArrayOf(0x02, 0, 0, 0, 0, 1).copyInto(this, 0)
        byteArrayOf(0x02, 0, 0, 0, 0, 2).copyInto(this, 6)
        this[12] = (etherType ushr 8).toByte()
        this[13] = etherType.toByte()
    }

    private fun putTcpHeader(frame: ByteArray, offset: Int, sourcePort: Int, destinationPort: Int, flags: Int) {
        putU16(frame, offset, sourcePort)
        putU16(frame, offset + 2, destinationPort)
        frame[offset + 12] = 0x50
        frame[offset + 13] = flags.toByte()
        putU16(frame, offset + 14, 0x4000)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

internal enum class WiredStreamSlot { MAIN, ALT }

internal data class WiredStartupEvent(
    val attempt: Int,
    val elapsedNanos: Long,
    val name: String,
    val streamSlot: WiredStreamSlot? = null,
)

internal class WiredStartupEventRecorder(private val attempt: Int, private val startedAtNanos: Long) {
    private val events = mutableListOf<WiredStartupEvent>()

    @Synchronized
    fun record(name: String, timestampNanos: Long, streamSlot: WiredStreamSlot? = null) {
        require(timestampNanos >= startedAtNanos)
        val elapsedNanos = timestampNanos - startedAtNanos
        require(events.lastOrNull()?.elapsedNanos?.let { elapsedNanos >= it } ?: true)
        events += WiredStartupEvent(attempt, elapsedNanos, name, streamSlot)
    }

    @Synchronized
    fun snapshot(): List<WiredStartupEvent> = events.toList()
}

internal class VirtualClockScheduler(startMillis: Long = 0) {
    private data class Entry(
        val dueMillis: Long,
        val order: Long,
        val cancelled: AtomicLong,
        val action: () -> Unit,
    )

    private val order = AtomicLong()
    private val tasks = PriorityQueue<Entry>(compareBy<Entry> { it.dueMillis }.thenBy { it.order })
    var nowMillis: Long = startMillis
        private set

    @Synchronized
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        require(delayMillis >= 0)
        val cancelled = AtomicLong(0)
        tasks += Entry(Math.addExact(nowMillis, delayMillis), order.incrementAndGet(), cancelled, action)
        return { cancelled.set(1) }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0)
        val target = Math.addExact(nowMillis, deltaMillis)
        while (true) {
            val next = synchronized(this) {
                val candidate = tasks.peek()
                if (candidate == null || candidate.dueMillis > target) {
                    nowMillis = target
                    null
                } else {
                    tasks.remove().also { nowMillis = it.dueMillis }
                }
            } ?: return
            if (next.cancelled.get() == 0L) next.action()
        }
    }
}

internal sealed interface TunReadOutcome {
    data class Bytes(val bytes: ByteArray) : TunReadOutcome
    data object ZeroBytes : TunReadOutcome
    data object EndOfStream : TunReadOutcome
    data class Failure(val error: IOException) : TunReadOutcome
}

internal class ScriptedTunInputStream : InputStream() {
    private val outcomes = ArrayDeque<TunReadOutcome>()
    private var activeBytes: ByteArray? = null
    private var offset = 0

    @Synchronized
    fun enqueue(outcome: TunReadOutcome) {
        outcomes.addLast(outcome)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count <= 0) count else one[0].toInt() and 0xff
    }

    @Synchronized
    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start + length <= buffer.size)
        if (length == 0) return 0
        while (activeBytes == null || offset == activeBytes!!.size) {
            if (activeBytes != null) {
                activeBytes = null
                offset = 0
            }
            if (outcomes.isEmpty()) throw IOException("TUN read outcome was not scripted")
            when (val next = outcomes.removeFirst()) {
                is TunReadOutcome.Bytes -> {
                    activeBytes = next.bytes.clone()
                    offset = 0
                    if (activeBytes!!.isEmpty()) return 0
                }
                TunReadOutcome.ZeroBytes -> return 0
                TunReadOutcome.EndOfStream -> return -1
                is TunReadOutcome.Failure -> throw next.error
            }
        }
        val bytes = activeBytes!!
        val copied = minOf(length, bytes.size - offset)
        System.arraycopy(bytes, offset, buffer, start, copied)
        offset += copied
        return copied
    }
}

internal sealed interface TunWriteOutcome {
    data object Accept : TunWriteOutcome
    data class Failure(val error: IOException) : TunWriteOutcome
}

internal class ScriptedTunOutputStream : OutputStream() {
    private val outcomes = ArrayDeque<TunWriteOutcome>()
    private val writes = mutableListOf<ByteArray>()

    @Synchronized
    fun enqueue(outcome: TunWriteOutcome) {
        outcomes.addLast(outcome)
    }

    @Synchronized
    fun writes(): List<ByteArray> = writes.map(ByteArray::clone)

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Synchronized
    override fun write(buffer: ByteArray, start: Int, length: Int) {
        require(start >= 0 && length >= 0 && start + length <= buffer.size)
        if (outcomes.isEmpty()) throw IOException("TUN write outcome was not scripted")
        when (val next = outcomes.removeFirst()) {
            TunWriteOutcome.Accept -> writes += buffer.copyOfRange(start, start + length)
            is TunWriteOutcome.Failure -> throw next.error
        }
    }
}
