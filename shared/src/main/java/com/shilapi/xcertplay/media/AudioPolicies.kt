package com.shilapi.xcertplay.media

internal data class TrackCreationAttempt(
    val allowed: Boolean,
    val attempt: Int,
    val retryInMs: Long,
)

internal fun <T> shouldCreateTrack(hasTrack: Boolean, activeFormat: T?, requestedFormat: T): Boolean =
    !hasTrack || activeFormat != requestedFormat

/** Bounded retry state for transient AudioTrack construction failures. */
internal class TrackCreationRetryPolicy<T : Any>(
    private val initialDelayMs: Long = TRACK_RETRY_INITIAL_MS,
    private val maximumDelayMs: Long = TRACK_RETRY_MAX_MS,
) {
    private var format: T? = null
    private var failureCount = 0
    private var retryAfterNs = 0L

    fun beginAttempt(nextFormat: T, nowNs: Long): TrackCreationAttempt {
        if (format != nextFormat) resetFor(nextFormat)
        val remainingNs = retryAfterNs - nowNs
        if (remainingNs > 0) {
            return TrackCreationAttempt(
                allowed = false,
                attempt = failureCount + 1,
                retryInMs = (remainingNs + 999_999L) / 1_000_000L,
            )
        }
        return TrackCreationAttempt(allowed = true, attempt = failureCount + 1, retryInMs = 0)
    }

    fun recordFailure(failedFormat: T, nowNs: Long): Long {
        if (format != failedFormat) resetFor(failedFormat)
        failureCount++
        val shift = (failureCount - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val delayMs = (initialDelayMs * (1L shl shift)).coerceAtMost(maximumDelayMs)
        retryAfterNs = nowNs + delayMs * 1_000_000L
        return delayMs
    }

    fun recordSuccess(successfulFormat: T): Int {
        val attempts = if (format == successfulFormat) failureCount + 1 else 1
        reset()
        return attempts
    }

    fun reset() {
        format = null
        failureCount = 0
        retryAfterNs = 0L
    }

    private fun resetFor(nextFormat: T) {
        format = nextFormat
        failureCount = 0
        retryAfterNs = 0L
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 8
    }
}

internal enum class AudioTimestampPollMode { STARTUP, WARMUP, STABLE, PROBE }

internal data class AudioTimestampPollUpdate(
    val acceptAnchor: Boolean = false,
    val enteredProbe: Boolean = false,
    val recoveredFromProbe: Boolean = false,
    val becameStable: Boolean = false,
)

/** Controls sparse timestamp-anchor queries while playback-head reads remain frequent. */
internal class AudioTimestampPollPolicy(
    private val firstQueryDelayNs: Long = TIMESTAMP_FIRST_QUERY_NS,
    private val warmupIntervalNs: Long = TIMESTAMP_WARMUP_INTERVAL_NS,
    private val stableIntervalNs: Long = TIMESTAMP_STABLE_INTERVAL_NS,
    private val probeIntervalNs: Long = TIMESTAMP_PROBE_INTERVAL_NS,
    private val unavailableAfterFalseQueries: Int = TIMESTAMP_UNAVAILABLE_FALSE_QUERIES,
    private val maximumWarmupQueries: Int = TIMESTAMP_MAX_WARMUP_QUERIES,
    private val stableAfterAdvancingSamples: Int = TIMESTAMP_STABLE_ADVANCING_SAMPLES,
    private val unavailableAfterStaleQueries: Int = TIMESTAMP_UNAVAILABLE_STALE_QUERIES,
) {
    private var trackCreatedNs: Long? = null
    private var lastQueryNs: Long? = null
    private var falseQueries = 0
    private var warmupQueries = 0
    private var advancingSamples = 0
    private var staleQueries = 0
    private var lastFramePosition: Long? = null
    var mode: AudioTimestampPollMode = AudioTimestampPollMode.STARTUP
        private set

    val pollingIntervalMs: Long
        get() = when (mode) {
            AudioTimestampPollMode.STARTUP -> firstQueryDelayNs
            AudioTimestampPollMode.WARMUP -> warmupIntervalNs
            AudioTimestampPollMode.STABLE -> stableIntervalNs
            AudioTimestampPollMode.PROBE -> probeIntervalNs
        } / 1_000_000L

    val unavailable: Boolean
        get() = mode == AudioTimestampPollMode.PROBE

    fun reset(trackCreatedNs: Long) {
        this.trackCreatedNs = trackCreatedNs
        lastQueryNs = null
        falseQueries = 0
        warmupQueries = 0
        advancingSamples = 0
        staleQueries = 0
        lastFramePosition = null
        mode = AudioTimestampPollMode.STARTUP
    }

    fun shouldQuery(nowNs: Long): Boolean {
        val lastQuery = lastQueryNs
        if (lastQuery == null) {
            val created = trackCreatedNs ?: return false
            return nowNs - created >= firstQueryDelayNs
        }
        val interval = when (mode) {
            AudioTimestampPollMode.STARTUP -> firstQueryDelayNs
            AudioTimestampPollMode.WARMUP -> warmupIntervalNs
            AudioTimestampPollMode.STABLE -> stableIntervalNs
            AudioTimestampPollMode.PROBE -> probeIntervalNs
        }
        return nowNs - lastQuery >= interval
    }

    fun recordQuery(nowNs: Long, timestampAvailable: Boolean, framePosition: Long?): AudioTimestampPollUpdate {
        lastQueryNs = nowNs
        return when (mode) {
            AudioTimestampPollMode.STARTUP,
            AudioTimestampPollMode.WARMUP -> recordWarmupQuery(timestampAvailable, framePosition)
            AudioTimestampPollMode.STABLE -> recordStableQuery(timestampAvailable, framePosition)
            AudioTimestampPollMode.PROBE -> recordProbeQuery(timestampAvailable, framePosition)
        }
    }

    private fun recordWarmupQuery(timestampAvailable: Boolean, framePosition: Long?): AudioTimestampPollUpdate {
        mode = AudioTimestampPollMode.WARMUP
        warmupQueries++
        val accepted = recordFrame(timestampAvailable, framePosition)
        if (advancingSamples >= stableAfterAdvancingSamples) {
            mode = AudioTimestampPollMode.STABLE
            return AudioTimestampPollUpdate(acceptAnchor = accepted, becameStable = true)
        }
        if (falseQueries >= unavailableAfterFalseQueries || warmupQueries >= maximumWarmupQueries) {
            mode = AudioTimestampPollMode.PROBE
            advancingSamples = 0
            staleQueries = 0
            return AudioTimestampPollUpdate(enteredProbe = true)
        }
        return AudioTimestampPollUpdate(acceptAnchor = accepted)
    }

    private fun recordStableQuery(timestampAvailable: Boolean, framePosition: Long?): AudioTimestampPollUpdate {
        val accepted = recordFrame(timestampAvailable, framePosition)
        if (timestampAvailable && framePosition != null && !accepted) {
            staleQueries++
            if (staleQueries >= unavailableAfterStaleQueries) {
                mode = AudioTimestampPollMode.PROBE
                advancingSamples = 0
                staleQueries = 0
                return AudioTimestampPollUpdate(enteredProbe = true)
            }
        } else if (accepted) {
            staleQueries = 0
        }
        if (falseQueries >= unavailableAfterFalseQueries) {
            mode = AudioTimestampPollMode.PROBE
            advancingSamples = 0
            staleQueries = 0
            return AudioTimestampPollUpdate(enteredProbe = true)
        }
        return AudioTimestampPollUpdate(acceptAnchor = accepted)
    }

    private fun recordProbeQuery(timestampAvailable: Boolean, framePosition: Long?): AudioTimestampPollUpdate {
        if (!timestampAvailable || framePosition == null) return AudioTimestampPollUpdate()
        val previous = lastFramePosition
        val advancing = previous == null || framePosition > previous
        if (advancing) lastFramePosition = framePosition
        mode = AudioTimestampPollMode.WARMUP
        falseQueries = 0
        warmupQueries = 1
        advancingSamples = if (advancing) 1 else 0
        staleQueries = 0
        return AudioTimestampPollUpdate(acceptAnchor = advancing, recoveredFromProbe = true)
    }

    private fun recordFrame(timestampAvailable: Boolean, framePosition: Long?): Boolean {
        if (!timestampAvailable || framePosition == null) {
            falseQueries++
            return false
        }
        falseQueries = 0
        val previous = lastFramePosition
        if (previous == null || framePosition > previous) {
            lastFramePosition = framePosition
            advancingSamples++
            return true
        }
        return false
    }
}

internal const val TRACK_RETRY_INITIAL_MS = 250L
internal const val TRACK_RETRY_MAX_MS = 5_000L
internal const val TRACK_RETRY_LOG_INTERVAL_NS = 1_000_000_000L
internal const val TIMESTAMP_FIRST_QUERY_NS = 250_000_000L
internal const val TIMESTAMP_WARMUP_INTERVAL_NS = 500_000_000L
internal const val TIMESTAMP_STABLE_INTERVAL_NS = 10_000_000_000L
internal const val TIMESTAMP_PROBE_INTERVAL_NS = 10_000_000_000L
internal const val TIMESTAMP_UNAVAILABLE_FALSE_QUERIES = 5
internal const val TIMESTAMP_MAX_WARMUP_QUERIES = 10
internal const val TIMESTAMP_STABLE_ADVANCING_SAMPLES = 3
internal const val TIMESTAMP_UNAVAILABLE_STALE_QUERIES = 3
