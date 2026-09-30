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

/** Controls sparse timestamp-anchor queries while playback-head reads remain frequent. */
internal class AudioTimestampPollPolicy(
    private val firstQueryDelayNs: Long = TIMESTAMP_FIRST_QUERY_NS,
    private val warmupIntervalNs: Long = TIMESTAMP_WARMUP_INTERVAL_NS,
    private val stableIntervalNs: Long = TIMESTAMP_STABLE_INTERVAL_NS,
    private val stableAfterSuccesses: Int = TIMESTAMP_STABLE_QUERY_COUNT,
) {
    private var trackCreatedNs: Long? = null
    private var lastQueryNs: Long? = null
    private var successfulQueries = 0

    val pollingIntervalMs: Long
        get() = if (successfulQueries >= stableAfterSuccesses) {
            stableIntervalNs / 1_000_000L
        } else {
            warmupIntervalNs / 1_000_000L
        }

    fun reset(trackCreatedNs: Long) {
        this.trackCreatedNs = trackCreatedNs
        lastQueryNs = null
        successfulQueries = 0
    }

    fun shouldQuery(nowNs: Long): Boolean {
        val lastQuery = lastQueryNs
        if (lastQuery == null) {
            val created = trackCreatedNs ?: return false
            return nowNs - created >= firstQueryDelayNs
        }
        val interval = if (successfulQueries >= stableAfterSuccesses) {
            stableIntervalNs
        } else {
            warmupIntervalNs
        }
        return nowNs - lastQuery >= interval
    }

    fun recordQuery(nowNs: Long, timestampAvailable: Boolean) {
        lastQueryNs = nowNs
        successfulQueries = if (timestampAvailable) successfulQueries + 1 else 0
    }
}

internal const val TRACK_RETRY_INITIAL_MS = 250L
internal const val TRACK_RETRY_MAX_MS = 5_000L
internal const val TRACK_RETRY_LOG_INTERVAL_NS = 1_000_000_000L
internal const val TIMESTAMP_FIRST_QUERY_NS = 250_000_000L
internal const val TIMESTAMP_WARMUP_INTERVAL_NS = 500_000_000L
internal const val TIMESTAMP_STABLE_INTERVAL_NS = 10_000_000_000L
internal const val TIMESTAMP_STABLE_QUERY_COUNT = 3
