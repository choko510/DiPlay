package com.shilapi.xcertplay.airplay

internal data class RtpHeader(val sequenceNumber: Int, val timestamp: Long)

internal fun parseRtpHeader(packet: ByteArray): RtpHeader? {
    if (packet.size < 12) return null
    val sequence = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
    val timestamp = ((packet[4].toLong() and 0xff) shl 24) or
        ((packet[5].toLong() and 0xff) shl 16) or
        ((packet[6].toLong() and 0xff) shl 8) or
        (packet[7].toLong() and 0xff)
    return RtpHeader(sequence, timestamp)
}

internal data class RtpReorderStats(
    val received: Long,
    val delivered: Long,
    val reordered: Long,
    val duplicates: Long,
    val late: Long,
    val lost: Long,
    val maxReorderDepth: Int,
    val maxGap: Int,
)

internal data class RtpDelivery<T>(
    val sequenceNumber: Int,
    val value: T,
    val missingBefore: Int,
)

/** Small bounded jitter window for the 16-bit RTP sequence number space. */
internal class RtpReorderBuffer<T>(
    private val maxPendingPackets: Int,
    private val holdNanos: Long,
) {
    private data class Pending<T>(val value: T, val arrivalNs: Long)

    private val pending = HashMap<Int, Pending<T>>()
    private val recentDelivered = IntArray(maxPendingPackets.coerceAtLeast(1) * 2) { -1 }
    private var recentIndex = 0
    private var nextSequence: Int? = null
    private var gapSinceNs: Long? = null
    private var received = 0L
    private var delivered = 0L
    private var reordered = 0L
    private var duplicates = 0L
    private var late = 0L
    private var lost = 0L
    private var maxReorderDepth = 0
    private var maxGap = 0

    init {
        require(maxPendingPackets > 0)
        require(holdNanos >= 0)
    }

    val pendingCount: Int get() = pending.size

    fun offer(sequenceNumber: Int, value: T, nowNs: Long): List<RtpDelivery<T>> {
        val sequence = sequenceNumber and 0xffff
        received++
        val expected = nextSequence
        if (expected == null) {
            nextSequence = sequence
        } else {
            val distance = forwardDistance(expected, sequence)
            if (distance == 0x8000 || distance > 0x8000) {
                if (wasDelivered(sequence)) duplicates++ else late++
                return emptyList()
            }
            if (pending.containsKey(sequence) || wasDelivered(sequence)) {
                duplicates++
                return emptyList()
            }
            if (distance > 0) {
                reordered++
                maxGap = maxOf(maxGap, distance)
            }
        }
        pending[sequence] = Pending(value, nowNs)
        maxReorderDepth = maxOf(maxReorderDepth, pending.size)
        return drain(nowNs, force = false)
    }

    fun poll(nowNs: Long): List<RtpDelivery<T>> = drain(nowNs, force = false)

    fun flush(): List<RtpDelivery<T>> = drain(Long.MAX_VALUE, force = true)

    fun clear() {
        pending.clear()
        recentDelivered.fill(-1)
        recentIndex = 0
        nextSequence = null
        gapSinceNs = null
    }

    fun stats(): RtpReorderStats = RtpReorderStats(
        received = received,
        delivered = delivered,
        reordered = reordered,
        duplicates = duplicates,
        late = late,
        lost = lost,
        maxReorderDepth = maxReorderDepth,
        maxGap = maxGap,
    )

    private fun drain(nowNs: Long, force: Boolean): List<RtpDelivery<T>> {
        if (pending.isEmpty()) return emptyList()
        val output = ArrayList<RtpDelivery<T>>(pending.size)
        var missingBefore = 0
        while (pending.isNotEmpty()) {
            val expected = nextSequence ?: return output
            val inOrder = pending.remove(expected)
            if (inOrder != null) {
                output.add(RtpDelivery(expected, inOrder.value, missingBefore))
                missingBefore = 0
                delivered++
                rememberDelivered(expected)
                nextSequence = (expected + 1) and 0xffff
                gapSinceNs = null
                continue
            }

            val nearest = nearestPending(expected) ?: return output
            val gapStarted = gapSinceNs ?: nearest.second.arrivalNs.also { gapSinceNs = it }
            val expired = force || nowNs - gapStarted >= holdNanos
            val overflow = pending.size >= maxPendingPackets || nearest.first >= maxPendingPackets
            if (!expired && !overflow) return output

            missingBefore = nearest.first
            lost += missingBefore
            nextSequence = nearest.third
            gapSinceNs = null
        }
        return output
    }

    private fun nearestPending(expected: Int): Triple<Int, Pending<T>, Int>? {
        var nearest: Triple<Int, Pending<T>, Int>? = null
        for ((sequence, packet) in pending) {
            val distance = forwardDistance(expected, sequence)
            if (distance in 1..0x7fff && (nearest == null || distance < nearest.first)) {
                nearest = Triple(distance, packet, sequence)
            }
        }
        return nearest
    }

    private fun wasDelivered(sequence: Int): Boolean = recentDelivered.any { it == sequence }

    private fun rememberDelivered(sequence: Int) {
        recentDelivered[recentIndex] = sequence
        recentIndex = (recentIndex + 1) % recentDelivered.size
    }

    private fun forwardDistance(from: Int, to: Int): Int = (to - from) and 0xffff
}
