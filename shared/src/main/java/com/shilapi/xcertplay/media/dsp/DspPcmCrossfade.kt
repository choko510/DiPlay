package com.shilapi.xcertplay.media.dsp

internal class DspPcmCrossfade(
    private val channels: Int,
    private val durationFrames: Int,
) {
    private var totalFrames = 0
    private var remainingFrames = 0

    init {
        require(channels in 1..2)
        require(durationFrames > 0)
    }

    val isActive: Boolean
        get() = remainingFrames > 0

    fun begin() {
        totalFrames = durationFrames
        remainingFrames = durationFrames
    }

    fun cancel() {
        totalFrames = 0
        remainingFrames = 0
    }

    fun blend(
        from: FloatArray,
        to: FloatArray,
        destination: FloatArray,
        frameCount: Int,
    ): Boolean {
        val sampleCount = frameCount.toLong() * channels
        if (!isActive || frameCount <= 0 || sampleCount > from.size || sampleCount > to.size ||
            sampleCount > destination.size
        ) {
            return false
        }
        val crossfadeFrames = minOf(frameCount, remainingFrames)
        for (frame in 0 until frameCount) {
            val mix = if (frame < crossfadeFrames) {
                val elapsedFrame = totalFrames - remainingFrames + frame
                if (totalFrames == 1) 1.0f else elapsedFrame.toFloat() / (totalFrames - 1)
            } else {
                1.0f
            }
            val frameOffset = frame * channels
            for (channel in 0 until channels) {
                val sampleOffset = frameOffset + channel
                val fromSample = from[sampleOffset]
                val toSample = to[sampleOffset]
                destination[sampleOffset] = fromSample + (toSample - fromSample) * mix
            }
        }
        remainingFrames -= crossfadeFrames
        return remainingFrames == 0
    }
}
