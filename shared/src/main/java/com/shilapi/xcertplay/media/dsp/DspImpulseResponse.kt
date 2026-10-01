package com.shilapi.xcertplay.media.dsp

class DspImpulseResponse(
    val id: String,
    val sampleRate: Int,
    val channels: Int,
    samples: FloatArray,
) {
    private val sampleData: FloatArray
    val frameCount: Int

    fun sampleAt(frame: Int, channel: Int): Float {
        require(frame in 0 until frameCount && channel in 0 until channels)
        return sampleData[frame * channels + channel]
    }

    init {
        require(IR_ID.matches(id))
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        require(channels in 1..2)
        require(samples.isNotEmpty() && samples.size % channels == 0)
        val frames = samples.size / channels
        require(frames <= MAX_FRAMES)
        require(samples.all(Float::isFinite))
        frameCount = frames
        sampleData = samples.copyOf()
    }

    internal fun copySamples(): FloatArray = sampleData.copyOf()

    companion object {
        const val MAX_FRAMES = 65_536
        val SUPPORTED_SAMPLE_RATES = setOf(44_100, 48_000, 96_000)
        private val IR_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
