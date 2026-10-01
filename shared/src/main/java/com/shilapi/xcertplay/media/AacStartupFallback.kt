package com.shilapi.xcertplay.media

import java.util.ArrayDeque

internal data class PendingAacAu(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val sourceSample: Long,
)

/** Bounded replay cache used only while a raw AAC decoder is proving it can produce PCM. */
internal class AacStartupReplayCache(
    private val maxAccessUnits: Int = AAC_STARTUP_MAX_AUS,
    private val maxBytes: Int = AAC_STARTUP_MAX_BYTES,
    private val maxDurationUs: Long = AAC_STARTUP_MAX_DURATION_US,
) {
    private val accessUnits = ArrayDeque<PendingAacAu>()

    var byteCount: Int = 0
        private set

    val size: Int get() = accessUnits.size

    fun offer(accessUnit: PendingAacAu): Boolean {
        if (accessUnit.bytes.isEmpty() || accessUnit.bytes.size > maxBytes || size >= maxAccessUnits) return false
        if (byteCount + accessUnit.bytes.size > maxBytes) return false
        if (accessUnits.isNotEmpty()) {
            val oldestTimeUs = minOf(accessUnits.minOf { it.presentationTimeUs }, accessUnit.presentationTimeUs)
            val newestTimeUs = maxOf(accessUnits.maxOf { it.presentationTimeUs }, accessUnit.presentationTimeUs)
            if (newestTimeUs - oldestTimeUs > maxDurationUs) return false
        }
        accessUnits.addLast(accessUnit)
        byteCount += accessUnit.bytes.size
        return true
    }

    fun drain(): List<PendingAacAu> {
        val result = ArrayList<PendingAacAu>(size)
        while (accessUnits.isNotEmpty()) result.add(accessUnits.removeFirst())
        byteCount = 0
        result.sortBy { it.presentationTimeUs }
        return result
    }

    fun drainIncluding(current: PendingAacAu?): List<PendingAacAu> {
        val replay = drain().toMutableList()
        if (current != null && current.bytes.size <= replayMaxBytes) replay.add(current)
        replay.sortBy { it.presentationTimeUs }
        var replayBytes = replay.sumOf { it.bytes.size.toLong() }
        while (replay.isNotEmpty() &&
            (replay.size > replayMaxAccessUnits || replayBytes > replayMaxBytes ||
                replay.last().presentationTimeUs - replay.first().presentationTimeUs > maxDurationUs)
        ) {
            val oldestCachedIndex = replay.indexOfFirst { it !== current }
            if (oldestCachedIndex < 0) {
                replay.clear()
                break
            }
            replayBytes -= replay.removeAt(oldestCachedIndex).bytes.size
        }
        return replay
    }

    fun clear() {
        accessUnits.clear()
        byteCount = 0
    }

    private val replayMaxAccessUnits = maxAccessUnits + 1
    private val replayMaxBytes = maxBytes + AAC_STARTUP_LIVE_AU_RESERVE_BYTES
}

/** Tracks the one-time raw-to-ADTS decoder fallback trigger. */
internal class AacDecoderFallbackPolicy(
    private val minimumAccessUnits: Int = AAC_STARTUP_MIN_AUS,
    private val waitNanos: Long = AAC_STARTUP_WAIT_NS,
) {
    private var firstSubmittedAuNs: Long? = null
    private var accessUnitsSubmitted = 0
    private var decoderOutputObserved = false
    private var fallbackAttempted = false

    fun reset() {
        firstSubmittedAuNs = null
        accessUnitsSubmitted = 0
        decoderOutputObserved = false
        fallbackAttempted = false
    }

    fun onAccessUnitsSubmitted(count: Int, nowNs: Long) {
        if (fallbackAttempted || decoderOutputObserved) return
        val submittedCount = count.coerceAtLeast(0)
        if (submittedCount > 0 && firstSubmittedAuNs == null) firstSubmittedAuNs = nowNs
        accessUnitsSubmitted += submittedCount
    }

    fun onDecoderOutput() {
        decoderOutputObserved = true
    }

    fun shouldFallback(nowNs: Long): Boolean {
        val firstSubmitted = firstSubmittedAuNs ?: return false
        return !fallbackAttempted && !decoderOutputObserved &&
            accessUnitsSubmitted >= minimumAccessUnits && nowNs - firstSubmitted >= waitNanos
    }

    fun beginFallback(): Boolean {
        if (fallbackAttempted || decoderOutputObserved) return false
        fallbackAttempted = true
        return true
    }

    val wasAttempted: Boolean get() = fallbackAttempted
    val outputObserved: Boolean get() = decoderOutputObserved
    val submittedAccessUnits: Int get() = accessUnitsSubmitted
}

internal const val AAC_STARTUP_MIN_AUS = 8
internal const val AAC_STARTUP_WAIT_NS = 2_000_000_000L
internal const val AAC_STARTUP_MAX_AUS = 100
internal const val AAC_STARTUP_MAX_BYTES = 512 * 1024
internal const val AAC_STARTUP_LIVE_AU_RESERVE_BYTES = 4 * 1024
internal const val AAC_STARTUP_MAX_DURATION_US = 3_000_000L
