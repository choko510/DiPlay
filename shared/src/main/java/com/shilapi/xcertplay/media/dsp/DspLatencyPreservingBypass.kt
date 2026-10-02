package com.shilapi.xcertplay.media.dsp

internal class DspLatencyPreservingBypass(
    private val format: DspAudioFormat,
    private val latencyFrames: Int,
    seed: Long = System.nanoTime(),
) {
    private val ring = FloatArray(DspBufferSizing.floatByteCount(latencyFrames, format.channels) / Float.SIZE_BYTES)
    private val inputFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
    private val delayedFloats = FloatArray(DspBufferSizing.MAX_DECODER_PCM_FRAMES * format.channels)
    private val dither = DspTpdfDither(seed)
    private val pcm16 = ByteArray(
        DspBufferSizing.pcm16ByteCount(DspBufferSizing.MAX_DECODER_PCM_FRAMES, format.channels),
    )
    private var ringPosition = 0
    private var processedLength = 0

    init {
        require(latencyFrames > 0)
    }

    val output: ByteArray
        get() = pcm16

    val outputLength: Int
        get() = processedLength

    fun process(
        source: ByteArray,
        offset: Int,
        length: Int,
        encoding: DspPcmEncoding,
    ): Int {
        processedLength = 0
        val frames = DspPcmConverter.pcmToFloatArray(source, offset, length, encoding, format, inputFloats)
        if (frames <= 0 || frames > DspBufferSizing.MAX_DECODER_PCM_FRAMES) return -1
        for (frame in 0 until frames) {
            for (channel in 0 until format.channels) {
                val sampleIndex = frame * format.channels + channel
                val ringIndex = ringPosition * format.channels + channel
                delayedFloats[sampleIndex] = ring[ringIndex]
                ring[ringIndex] = inputFloats[sampleIndex]
            }
            ringPosition++
            if (ringPosition == latencyFrames) ringPosition = 0
        }
        if (!DspPcmConverter.floatArrayToPcm16(
                source = delayedFloats,
                frames = frames,
                format = format,
                destination = pcm16,
                offset = 0,
                dither = dither,
            )
        ) {
            return -1
        }
        processedLength = DspBufferSizing.pcm16ByteCount(frames, format.channels)
        return processedLength
    }

    fun reset() {
        ring.fill(0f)
        inputFloats.fill(0f)
        delayedFloats.fill(0f)
        dither.reset()
        ringPosition = 0
        processedLength = 0
    }
}
