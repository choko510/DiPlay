package com.shilapi.xcertplay.media

import android.util.Log
import java.util.Arrays

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val streamType: Int,
    private val decoderOwnerId: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var surfaceSubmitted = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private var queuePolls = 0L
    private var emptyQueuePolls = 0L
    private var timedQueuePolls = 0L
    private var queueWaitNs = 0L
    private var queuePeakDepth = 0
    private var outputDequeueCalls = 0L
    private var outputDequeueWaitCalls = 0L
    private var outputDequeueWaitNs = 0L
    private var pendingOutputPeak = 0
    private var pendingOutputTimeouts = 0L
    private var keyFrameRequestAttempts = 0L
    private var latencySampleSize = 0
    private var latencySampleWriteIndex = 0
    private var unmatchedSurfaceOutputs = 0L
    private var pendingFrameOverwrites = 0L
    private var nextPendingFrameIndex = 0
    private var lastThreadCpuNs = -1L
    private var lastProcessAllocBytes = -1L
    private var lastGlobalGcCount = -1L
    private val pendingFrameTimesUs = LongArray(FRAME_CORRELATION_CAPACITY)
    private val pendingFrameArrivalNs = LongArray(FRAME_CORRELATION_CAPACITY)
    private val pendingFrameOccupied = BooleanArray(FRAME_CORRELATION_CAPACITY)
    private val latencySamplesNs = LongArray(LATENCY_SAMPLE_CAPACITY)
    private val sortedLatencySamplesNs = LongArray(LATENCY_SAMPLE_CAPACITY)

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        val touchLatency = TouchLatencyProbe.onFrame(now)
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onQueuePoll(waitNs: Long, timed: Boolean, jobFound: Boolean) {
        queuePolls++
        if (timed) timedQueuePolls++
        if (!jobFound) emptyQueuePolls++
        queueWaitNs += waitNs.coerceAtLeast(0L)
    }

    @Synchronized fun onQueueDepth(depth: Int) {
        queuePeakDepth = maxOf(queuePeakDepth, depth)
    }

    @Synchronized fun onOutputDequeue(waitNs: Long, waited: Boolean) {
        outputDequeueCalls++
        if (waited) {
            outputDequeueWaitCalls++
            outputDequeueWaitNs += waitNs.coerceAtLeast(0L)
        }
    }

    @Synchronized fun onPendingOutputCount(count: Int) {
        pendingOutputPeak = maxOf(pendingOutputPeak, count)
    }

    @Synchronized fun onPendingOutputTimeout() {
        pendingOutputTimeouts++
    }

    @Synchronized fun onKeyFrameRequestAttempt() {
        keyFrameRequestAttempts++
    }

    @Synchronized fun onCodecInputQueued(presentationTimeUs: Long, receivedNs: Long) {
        val index = nextPendingFrameIndex
        if (pendingFrameOccupied[index]) pendingFrameOverwrites++
        pendingFrameTimesUs[index] = presentationTimeUs
        pendingFrameArrivalNs[index] = receivedNs
        pendingFrameOccupied[index] = true
        nextPendingFrameIndex = (index + 1) % FRAME_CORRELATION_CAPACITY
    }

    @Synchronized fun onSubmittedToSurface(
        presentationTimeUs: Long? = null,
        submittedNs: Long = nanoTime(),
    ) {
        surfaceSubmitted++
        if (presentationTimeUs == null) return
        var index = 0
        while (index < FRAME_CORRELATION_CAPACITY &&
            (!pendingFrameOccupied[index] || pendingFrameTimesUs[index] != presentationTimeUs)
        ) {
            index++
        }
        if (index == FRAME_CORRELATION_CAPACITY) {
            unmatchedSurfaceOutputs++
            return
        }
        pendingFrameOccupied[index] = false
        recordFrameLatency((submittedNs - pendingFrameArrivalNs[index]).coerceAtLeast(0L))
    }

    @Synchronized fun onRecovery() {
        recoveries++
    }

    @Synchronized fun startRuntimeTracking(
        threadCpuTimeNs: Long,
        processAllocatedBytes: Long,
        globalGcCount: Long,
    ) {
        windowStartNs = nanoTime()
        rememberRuntimeCounters(threadCpuTimeNs, processAllocatedBytes, globalGcCount)
    }

    @Synchronized fun isDue(): Boolean = nanoTime() - windowStartNs >= WINDOW_NS

    @Synchronized fun logIfDue(
        threadCpuTimeNs: Long = -1L,
        processAllocatedBytes: Long = -1L,
        globalGcCount: Long = -1L,
        processPssKb: Long = -1L,
    ): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0 && queuePolls == 0L && outputDequeueCalls == 0L) {
            windowStartNs = now
            rememberRuntimeCounters(threadCpuTimeNs, processAllocatedBytes, globalGcCount)
            return null
        }

        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val threadCpuMsPerSecond = rateDelta(threadCpuTimeNs, lastThreadCpuNs, seconds, 1_000_000.0)
        val allocatedBytesPerSecond = rateDelta(processAllocatedBytes, lastProcessAllocBytes, seconds, 1.0)
        val gcPerSecond = rateDelta(globalGcCount, lastGlobalGcCount, seconds, 1.0)
        val globalGcPerMinute = if (gcPerSecond < 0.0) -1.0 else gcPerSecond * 60.0
        val (p50Ms, p95Ms, p99Ms) = frameLatencyPercentilesMs()
        val line = (
            "video stats type=$streamType owner=$decoderOwnerId rx=%.1ffps surfaceSubmit=%.1ffps " +
                "maxGap=%dms kbps=%d recoveries=%d queueEmptyPoll=%.1f/s queueTimedPoll=%.1f/s " +
                "queueWait=%.2fms queuePeak=%d outputDequeue=%.1f/s outputWaitCalls=%.1f/s outputWait=%.2fms " +
                "pendingPeak=%d pendingTimeouts=%d receiveToSurfaceSubmitMs=%.2f/%.2f/%.2f n=%d " +
                "keyframeAttempts=%d latencyUnmatched=%d latencyOverwrites=%d " +
                "threadCpu=%.2fms/s processAllocBytes=%.0f/s processAllocObjects=NOT_AVAILABLE " +
                "threadAllocBytes=NOT_AVAILABLE " +
                "globalGc=%.2f/min pssKb=%d fdCount=NOT_SAMPLED " +
                "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms"
            ).format(
                received / seconds,
                surfaceSubmitted / seconds,
                maxArrivalGapNs / 1_000_000,
                (bytes * 8 / 1000 / seconds).toLong(),
                recoveries,
                emptyQueuePolls / seconds,
                timedQueuePolls / seconds,
                queueWaitNs / 1e6,
                queuePeakDepth,
                outputDequeueCalls / seconds,
                outputDequeueWaitCalls / seconds,
                outputDequeueWaitNs / 1e6,
                pendingOutputPeak,
                pendingOutputTimeouts,
                p50Ms,
                p95Ms,
                p99Ms,
                latencySampleSize,
                keyFrameRequestAttempts,
                unmatchedSurfaceOutputs,
                pendingFrameOverwrites,
                threadCpuMsPerSecond,
                allocatedBytesPerSecond,
                globalGcPerMinute,
                processPssKb,
                touchAvgMs,
                maxTouchLatencyNs / 1_000_000,
                touchSamples,
                TouchLatencyProbe.maxSendNs / 1_000_000,
            )
        TouchLatencyProbe.maxSendNs = 0
        log(line)
        resetWindow(now)
        rememberRuntimeCounters(threadCpuTimeNs, processAllocatedBytes, globalGcCount)
        return line
    }

    private fun recordFrameLatency(latencyNs: Long) {
        latencySamplesNs[latencySampleWriteIndex] = latencyNs
        latencySampleWriteIndex = (latencySampleWriteIndex + 1) % LATENCY_SAMPLE_CAPACITY
        latencySampleSize = minOf(latencySampleSize + 1, LATENCY_SAMPLE_CAPACITY)
    }

    private fun frameLatencyPercentilesMs(): Triple<Double, Double, Double> {
        if (latencySampleSize == 0) return Triple(-1.0, -1.0, -1.0)
        System.arraycopy(latencySamplesNs, 0, sortedLatencySamplesNs, 0, latencySampleSize)
        Arrays.sort(sortedLatencySamplesNs, 0, latencySampleSize)
        val last = latencySampleSize - 1
        return Triple(
            sortedLatencySamplesNs[(last * 50) / 100] / 1e6,
            sortedLatencySamplesNs[(last * 95) / 100] / 1e6,
            sortedLatencySamplesNs[(last * 99) / 100] / 1e6,
        )
    }

    private fun rateDelta(current: Long, previous: Long, seconds: Double, divisor: Double): Double =
        if (current < 0L || previous < 0L || current < previous) -1.0
        else (current - previous) / seconds / divisor

    private fun rememberRuntimeCounters(
        threadCpuTimeNs: Long,
        processAllocatedBytes: Long,
        globalGcCount: Long,
    ) {
        lastThreadCpuNs = threadCpuTimeNs
        lastProcessAllocBytes = processAllocatedBytes
        lastGlobalGcCount = globalGcCount
    }

    private fun resetWindow(nowNs: Long) {
        windowStartNs = nowNs
        received = 0
        surfaceSubmitted = 0
        recoveries = 0
        bytes = 0L
        maxArrivalGapNs = 0L
        touchSamples = 0
        touchLatencySumNs = 0L
        maxTouchLatencyNs = 0L
        queuePolls = 0L
        emptyQueuePolls = 0L
        timedQueuePolls = 0L
        queueWaitNs = 0L
        queuePeakDepth = 0
        outputDequeueCalls = 0L
        outputDequeueWaitCalls = 0L
        outputDequeueWaitNs = 0L
        pendingOutputPeak = 0
        pendingOutputTimeouts = 0L
        keyFrameRequestAttempts = 0L
        latencySampleSize = 0
        latencySampleWriteIndex = 0
        unmatchedSurfaceOutputs = 0L
        pendingFrameOverwrites = 0L
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
        const val FRAME_CORRELATION_CAPACITY = 128
        const val LATENCY_SAMPLE_CAPACITY = 128
    }
}

internal data class VideoRecoveryMetricsSnapshot(
    val streamType: Int,
    val attempts: Long,
    val queued: Long,
    val callbacks: Long,
    val sent: Long,
    val sendFailed: Long,
    val skipped: Long,
    val staleDrops: Long,
    val coalesced: Long,
    val queueRejected: Long,
    val callbackFailures: Long,
    val legacyOutcomes: Long,
) {
    fun toDiagnosticString(): String =
        "keyframeRoute type=$streamType attempts=$attempts queued=$queued callbacks=$callbacks " +
            "sent=$sent sendFailed=$sendFailed skipped=$skipped stale=$staleDrops coalesced=$coalesced " +
            "queueRejected=$queueRejected callbackFailures=$callbackFailures legacy=$legacyOutcomes"
}

internal class VideoRecoveryMetrics {
    private val values = Array(STREAM_COUNT) { LongArray(METRIC_COUNT) }

    @Synchronized fun recordAttempt(type: Int) = increment(type, ATTEMPTS)
    @Synchronized fun recordQueued(type: Int) = increment(type, QUEUED)
    @Synchronized fun recordCallback(type: Int) = increment(type, CALLBACKS)
    @Synchronized fun recordSent(type: Int) = increment(type, SENT)
    @Synchronized fun recordSendFailure(type: Int) = increment(type, SEND_FAILED)
    @Synchronized fun recordSkipped(type: Int) = increment(type, SKIPPED)
    @Synchronized fun recordStaleDrop(type: Int) = increment(type, STALE_DROPS)
    @Synchronized fun recordCoalesced(type: Int) = increment(type, COALESCED)
    @Synchronized fun recordQueueRejected(type: Int) = increment(type, QUEUE_REJECTED)
    @Synchronized fun recordCallbackFailure(type: Int) = increment(type, CALLBACK_FAILURES)
    @Synchronized fun recordLegacyOutcome(type: Int) = increment(type, LEGACY_OUTCOMES)

    @Synchronized fun snapshot(type: Int): VideoRecoveryMetricsSnapshot {
        val slot = slotForType(type)
        val counters = if (slot == null) LongArray(METRIC_COUNT) else values[slot]
        return VideoRecoveryMetricsSnapshot(
            streamType = type,
            attempts = counters[ATTEMPTS],
            queued = counters[QUEUED],
            callbacks = counters[CALLBACKS],
            sent = counters[SENT],
            sendFailed = counters[SEND_FAILED],
            skipped = counters[SKIPPED],
            staleDrops = counters[STALE_DROPS],
            coalesced = counters[COALESCED],
            queueRejected = counters[QUEUE_REJECTED],
            callbackFailures = counters[CALLBACK_FAILURES],
            legacyOutcomes = counters[LEGACY_OUTCOMES],
        )
    }

    private fun increment(type: Int, metric: Int) {
        slotForType(type)?.let { values[it][metric]++ }
    }

    private fun slotForType(type: Int): Int? = when (type) {
        110 -> 0
        111 -> 1
        else -> null
    }

    private companion object {
        const val STREAM_COUNT = 2
        const val ATTEMPTS = 0
        const val QUEUED = 1
        const val CALLBACKS = 2
        const val SENT = 3
        const val SEND_FAILED = 4
        const val STALE_DROPS = 5
        const val COALESCED = 6
        const val QUEUE_REJECTED = 7
        const val CALLBACK_FAILURES = 8
        const val SKIPPED = 9
        const val LEGACY_OUTCOMES = 10
        const val METRIC_COUNT = 11
    }
}
