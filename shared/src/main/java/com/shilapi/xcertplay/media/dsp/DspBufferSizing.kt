package com.shilapi.xcertplay.media.dsp

internal object DspBufferSizing {
    const val PROCESSING_CHUNK_FRAMES = 512
    const val MAX_DECODER_PCM_FRAMES = 16_384
    const val MAX_DECODER_PCM_BYTES = MAX_DECODER_PCM_FRAMES * 2 * Float.SIZE_BYTES

    fun floatByteCount(frames: Int, channels: Int): Int {
        require(frames >= 0)
        require(channels in 1..2)
        val byteCount = frames.toLong() * channels * Float.SIZE_BYTES
        require(byteCount <= Int.MAX_VALUE)
        return byteCount.toInt()
    }

    fun pcm16ByteCount(frames: Int, channels: Int): Int {
        require(frames >= 0)
        require(channels in 1..2)
        val byteCount = frames.toLong() * channels * Short.SIZE_BYTES
        require(byteCount <= Int.MAX_VALUE)
        return byteCount.toInt()
    }

    fun growCapacity(currentCapacity: Int, requiredCapacity: Int): Int {
        require(currentCapacity >= 0)
        require(requiredCapacity >= 0)
        var capacity = maxOf(currentCapacity, 1)
        while (capacity < requiredCapacity) {
            val doubled = capacity.toLong() * 2
            capacity = if (doubled >= Int.MAX_VALUE) Int.MAX_VALUE else doubled.toInt()
        }
        return capacity
    }
}
