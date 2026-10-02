package com.shilapi.xcertplay.airplay

data class AudioPlaybackClock(
    val streamType: Int,
    val baseRtpSample: Long,
    val playedFrames: Long,
    val sourceSampleRate: Int,
    val outputSampleRate: Int,
    val monotonicTimestampNs: Long,
    val source: String,
    val algorithmicLatencyFrames: Int = 0,
) {
    val contentFrames: Long
        get() = (playedFrames - algorithmicLatencyFrames.coerceAtLeast(0).toLong()).coerceAtLeast(0L)

    val samplePosition: Long
        get() = (baseRtpSample + framesToSourceSamples(contentFrames, sourceSampleRate, outputSampleRate)) and UINT32_MASK
}

/** Maps AudioTrack frame positions back to the RTP sample clock for one track generation. */
internal class AudioPlaybackClockMapper(private val streamType: Int) {
    private var baseRtpSample: Long? = null
    private var baseFramePosition = 0L
    private var sourceSampleRate = 0
    private var outputSampleRate = 0

    fun reset(sourceRate: Int, outputRate: Int) {
        baseRtpSample = null
        baseFramePosition = 0L
        sourceSampleRate = sourceRate.coerceAtLeast(1)
        outputSampleRate = outputRate.coerceAtLeast(1)
    }

    fun onPcmWritten(sourceSample: Long, firstTrackFramePosition: Long = 0L) {
        if (baseRtpSample == null) {
            baseRtpSample = sourceSample and UINT32_MASK
            baseFramePosition = firstTrackFramePosition
        }
    }

    fun snapshot(
        framePosition: Long,
        timestampNs: Long,
        source: String,
        algorithmicLatencyFrames: Int = 0,
    ): AudioPlaybackClock? {
        val baseSample = baseRtpSample ?: return null
        return AudioPlaybackClock(
            streamType = streamType,
            baseRtpSample = baseSample,
            playedFrames = (framePosition - baseFramePosition).coerceAtLeast(0),
            sourceSampleRate = sourceSampleRate,
            outputSampleRate = outputSampleRate,
            monotonicTimestampNs = timestampNs,
            source = source,
            algorithmicLatencyFrames = algorithmicLatencyFrames.coerceAtLeast(0),
        )
    }
}

/** Extends an unsigned 32-bit track frame counter and ignores backward read glitches. */
internal class Unsigned32FrameTracker {
    private var lastRawFrame: Long? = null
    private var extendedFrame = 0L

    fun reset() {
        lastRawFrame = null
        extendedFrame = 0L
    }

    fun update(rawFrame: Long): Long {
        val raw = rawFrame and UINT32_MASK
        val previous = lastRawFrame
        if (previous == null) {
            extendedFrame = raw
        } else {
            val delta = (raw - previous) and UINT32_MASK
            if (delta > UINT32_HALF_RANGE) return extendedFrame
            extendedFrame += delta
        }
        lastRawFrame = raw
        return extendedFrame
    }
}

internal class RtpSampleTimestampMapper(private val sampleRate: Int) {
    private var lastRawSample: Long? = null
    private var extendedSample = 0L

    fun reset() {
        lastRawSample = null
        extendedSample = 0L
    }

    fun presentationTimeUs(sample: Int): Long {
        val raw = sample.toLong() and UINT32_MASK
        val previous = lastRawSample
        if (previous == null) {
            extendedSample = raw
        } else {
            val unsignedDelta = (raw - previous) and UINT32_MASK
            val delta = if (unsignedDelta > UINT32_HALF_RANGE) unsignedDelta - UINT32_MODULUS else unsignedDelta
            extendedSample += delta
        }
        lastRawSample = raw
        return extendedSample * 1_000_000L / sampleRate.coerceAtLeast(1)
    }

    fun sampleAtPresentationTimeUs(presentationTimeUs: Long): Long =
        ((presentationTimeUs.coerceAtLeast(0) * sampleRate.coerceAtLeast(1) + 500_000L) /
            1_000_000L) and UINT32_MASK
}

private fun framesToSourceSamples(frames: Long, sourceRate: Int, outputRate: Int): Long {
    val rate = outputRate.coerceAtLeast(1).toLong()
    val source = sourceRate.coerceAtLeast(1).toLong()
    val wholeSeconds = frames / rate
    val remainderFrames = frames % rate
    return wholeSeconds * source + remainderFrames * source / rate
}

internal fun framesToNanos(frames: Long, sampleRate: Int): Long {
    val rate = sampleRate.coerceAtLeast(1).toLong()
    val wholeSeconds = frames / rate
    val remainderFrames = frames % rate
    return wholeSeconds * 1_000_000_000L + remainderFrames * 1_000_000_000L / rate
}

private const val UINT32_MASK = 0xffff_ffffL
private const val UINT32_MODULUS = 0x1_0000_0000L
private const val UINT32_HALF_RANGE = 0x8000_0000L
