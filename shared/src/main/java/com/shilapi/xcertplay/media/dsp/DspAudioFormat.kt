package com.shilapi.xcertplay.media.dsp

internal data class DspAudioFormat(
    val sampleRate: Int,
    val channels: Int,
) {
    init {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        require(channels in 1..MAX_CHANNELS)
    }

    private companion object {
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000
        const val MAX_CHANNELS = 2
    }
}
