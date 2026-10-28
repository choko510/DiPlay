package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(
        val surface: Surface?,
        val retirements: List<VideoSurfaceRetirement> = emptyList(),
    ) : VideoJob
    data object Resync : VideoJob
}

internal data class VideoSurfaceRetirement(
    val surface: Surface,
    val onApplied: () -> Unit,
    val createdNs: Long = System.nanoTime(),
)

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    private val surfaceRetirements = ConcurrentLinkedQueue<VideoSurfaceRetirement>()
    private val surfaceRetirementCount = AtomicInteger()

    @Synchronized fun offer(job: VideoJob) {
        var queuedJob = if (job is VideoJob.SurfaceChanged && job.retirements.isNotEmpty()) {
            surfaceRetirementCount.addAndGet(job.retirements.size)
            surfaceRetirements.addAll(job.retirements)
            job.copy(retirements = emptyList())
        } else {
            job
        }
        if (queuedJob is VideoJob.Config && jobs.any { it is VideoJob.Config }) {
            discardFrames()
            jobs.removeIf { it is VideoJob.Config }
            jobs.offer(VideoJob.Resync)
        }
        if (queuedJob is VideoJob.SurfaceChanged) {
            val superseded = jobs.filterIsInstance<VideoJob.SurfaceChanged>()
            if (superseded.isNotEmpty()) {
                jobs.removeIf { it is VideoJob.SurfaceChanged }
            }
        }
        if (queuedJob is VideoJob.Resync && jobs.lastOrNull() is VideoJob.Resync) return
        if (queuedJob is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + queuedJob.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (queuedJob.nalus.size > maxBytes) return
        }
        jobs.offer(queuedJob)
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
    }

    @Synchronized fun drainSurfaceRetirements(): List<VideoSurfaceRetirement> {
        jobs.clear()
        return takeSurfaceRetirements()
    }

    fun takeSurfaceRetirements(): List<VideoSurfaceRetirement> {
        val retirements = ArrayList<VideoSurfaceRetirement>()
        while (true) {
            val retirement = surfaceRetirements.poll() ?: break
            surfaceRetirementCount.decrementAndGet()
            retirements.add(retirement)
        }
        return retirements
    }

    internal val queuedSurfaceRetirementCount: Int
        get() = surfaceRetirementCount.get()

    internal fun oldestQueuedSurfaceRetirementNs(): Long? = surfaceRetirements.peek()?.createdNs

    internal val queuedJobsForTest: Int get() = jobs.size

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
}

/** A bounded wake-up hint, not exact input/output pairing: codecs may drop or reorder output. */
internal class VideoPendingOutputState(
    private val maxPendingOutputs: Int = 64,
    private val waitLimitNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    private val maxDequeueWaitUs: Long = 8_000L,
) {
    var pendingOutputCount: Int = 0
        private set
    var waitTimeoutCount: Long = 0
        private set
    private var lastQueuedNs = 0L

    init {
        require(maxPendingOutputs > 0)
        require(waitLimitNs > 0L)
        require(maxDequeueWaitUs > 0L)
    }

    fun onInputQueued(nowNs: Long) {
        lastQueuedNs = nowNs
        if (pendingOutputCount < maxPendingOutputs) pendingOutputCount++
    }

    fun onOutputDequeued() {
        if (pendingOutputCount == 0) return
        pendingOutputCount--
        if (pendingOutputCount == 0) lastQueuedNs = 0L
    }

    fun nextDequeueTimeoutUs(nowNs: Long): Long {
        if (pendingOutputCount == 0) return 0L
        val elapsedNs = (nowNs - lastQueuedNs).coerceAtLeast(0L)
        val remainingNs = waitLimitNs - elapsedNs
        if (remainingNs <= 0L) {
            pendingOutputCount = 0
            lastQueuedNs = 0L
            waitTimeoutCount++
            return 0L
        }
        val remainingUs = ((remainingNs + 999L) / 1_000L).coerceAtLeast(1L)
        return minOf(maxDequeueWaitUs, remainingUs)
    }

    fun reset() {
        pendingOutputCount = 0
        lastQueuedNs = 0L
    }
}

internal fun drainVideoCodecOutput(
    running: () -> Boolean,
    dequeue: (Long) -> Int,
    onFormatChanged: () -> Unit,
    onOutputBuffer: (Int) -> Boolean,
    firstTimeoutUs: Long = 0L,
) {
    var timeoutUs = firstTimeoutUs
    while (running()) {
        val index = dequeue(timeoutUs)
        timeoutUs = 0L
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged()
            index >= 0 -> if (!onOutputBuffer(index)) return
            else -> return
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
