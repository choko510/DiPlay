package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    @Synchronized fun offer(job: VideoJob) {
        var queuedJob = job
        if (queuedJob is VideoJob.Config && jobs.any { it is VideoJob.Config }) {
            discardFrames()
            jobs.removeIf { it is VideoJob.Config }
            jobs.offer(VideoJob.Resync)
        }
        if (queuedJob is VideoJob.SurfaceChanged) {
            val superseded = jobs.filterIsInstance<VideoJob.SurfaceChanged>()
            if (superseded.isNotEmpty()) {
                val retirements = superseded.flatMap(VideoJob.SurfaceChanged::retirements)
                jobs.removeIf { it is VideoJob.SurfaceChanged }
                queuedJob = queuedJob.copy(retirements = retirements + queuedJob.retirements)
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
        val retirements = jobs.filterIsInstance<VideoJob.SurfaceChanged>().flatMap(VideoJob.SurfaceChanged::retirements)
        jobs.clear()
        return retirements
    }

    internal val queuedJobsForTest: Int get() = jobs.size

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
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
