package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

internal data class DspTimingSummary(
    val averageUs: Double = 0.0,
    val p50Us: Long = 0,
    val p95Us: Long = 0,
    val p99Us: Long = 0,
    val maxUs: Long = 0,
    val sampleCount: Long = 0,
)

internal class DspFixedHistogram {
    private val buckets = AtomicLongArray(BUCKET_COUNT)
    private val sampleCount = AtomicLong()
    private val totalNanos = AtomicLong()
    private val maximumNanos = AtomicLong()

    fun record(durationNanos: Long) {
        val boundedNanos = durationNanos.coerceAtLeast(0L)
        val index = (boundedNanos / BUCKET_WIDTH_NANOS).coerceAtMost(OVERFLOW_BUCKET.toLong()).toInt()
        updateMaximum(boundedNanos)
        totalNanos.addAndGet(boundedNanos)
        buckets.incrementAndGet(index)
        sampleCount.incrementAndGet()
    }

    fun snapshot(): DspTimingSummary {
        val count = sampleCount.get()
        if (count == 0L) return DspTimingSummary()
        return DspTimingSummary(
            averageUs = totalNanos.get().toDouble() / count / NANOS_PER_MICROSECOND,
            p50Us = percentileUpperBound(count, 0.50),
            p95Us = percentileUpperBound(count, 0.95),
            p99Us = percentileUpperBound(count, 0.99),
            maxUs = maximumNanos.get() / NANOS_PER_MICROSECOND,
            sampleCount = count,
        )
    }

    fun reset() {
        for (index in 0 until BUCKET_COUNT) buckets.set(index, 0L)
        sampleCount.set(0L)
        totalNanos.set(0L)
        maximumNanos.set(0L)
    }

    private fun percentileUpperBound(count: Long, fraction: Double): Long {
        val target = kotlin.math.ceil(count * fraction).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (index in 0 until BUCKET_COUNT) {
            cumulative += buckets.get(index)
            if (cumulative >= target) {
                if (index == OVERFLOW_BUCKET) return maximumNanos.get() / NANOS_PER_MICROSECOND
                val upperBoundNanos = (index + 1L) * BUCKET_WIDTH_NANOS
                return (upperBoundNanos + NANOS_PER_MICROSECOND - 1L) / NANOS_PER_MICROSECOND
            }
        }
        return maximumNanos.get() / NANOS_PER_MICROSECOND
    }

    private fun updateMaximum(candidate: Long) {
        while (true) {
            val previous = maximumNanos.get()
            if (candidate <= previous || maximumNanos.compareAndSet(previous, candidate)) return
        }
    }

    private companion object {
        const val BUCKET_WIDTH_NANOS = 20_000L
        const val OVERFLOW_BUCKET = 1_024
        const val BUCKET_COUNT = OVERFLOW_BUCKET + 1
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
