package com.shilapi.xcertplay.media.dsp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspFinalIntegrationInstrumentedTest {
    @Test
    fun allFourModulesRemainChunkInvariantForEveryCombinationAndOutputFormat() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        for (sampleRate in listOf(44_100, 48_000)) {
            for (channels in 1..2) {
                val format = DspAudioFormat(sampleRate, channels)
                val input = inputBlock(format, frames = TOTAL_FRAMES)
                for (combination in 0 until 16) {
                    val config = integrationConfig(combination)
                    val whole = createPipeline(format, config)
                    val chunked = createPipeline(format, config)
                    try {
                        val expected = processWhole(whole, input)
                        val actual = processChunked(chunked, input, format)
                        assertArrayEquals(
                            "sampleRate=$sampleRate channels=$channels featureMask=$combination",
                            expected,
                            actual,
                        )
                        assertEquals(TOTAL_FRAMES * channels * Short.SIZE_BYTES, expected.size)
                        assertTrue(whole.algorithmicLatencyFrames >= 0)
                        assertEquals(whole.algorithmicLatencyFrames, chunked.algorithmicLatencyFrames)
                    } finally {
                        whole.close()
                        chunked.close()
                    }
                }
            }
        }
    }

    @Test
    fun highQualityHarmonicAliasRejectionCoversBothSampleRatesAndChannelCounts() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        for (sampleRate in listOf(44_100, 48_000)) {
            val inputFrequency = if (sampleRate == 44_100) 13_500.0 else 14_000.0
            val aliasFrequency = sampleRate - 3.0 * inputFrequency
            for (channels in 1..2) {
                val format = DspAudioFormat(sampleRate, channels)
                val processor = NativeDspProcessor.createOrNull(
                    format,
                    DspRuntimeConfig(
                        enabled = true,
                        autoHeadroomEnabled = false,
                        safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                        harmonic = DspHarmonicConfig(
                            enabled = true,
                            mode = DspHarmonicMode.ODD,
                            quality = DspHarmonicQuality.HIGH,
                            exciterEnabled = true,
                            exciterAmount = 0.5,
                            exciterCutoffHz = 4_000.0,
                        ),
                    ),
                )
                assertNotNull("native harmonic graph sampleRate=$sampleRate channels=$channels", processor)
                processor ?: continue
                try {
                    val components = measureHarmonicComponents(processor, format, inputFrequency, aliasFrequency)
                    val aliasRatio = components.second / components.first.coerceAtLeast(1e-12)
                    Log.i(
                        TAG,
                        "HARMONIC_ALIAS sampleRate=$sampleRate channels=$channels " +
                            "fundamental=${components.first} alias=${components.second} ratio=$aliasRatio",
                    )
                    assertTrue(
                        "folded alias sampleRate=$sampleRate channels=$channels alias=${components.second} " +
                            "fundamental=${components.first}",
                        components.second < components.first * 0.05,
                    )
                } finally {
                    processor.close()
                }
            }
        }
    }

    private fun integrationConfig(mask: Int): DspRuntimeConfig = DspRuntimeConfig(
        enabled = true,
        autoHeadroomEnabled = true,
        seatAlignment = DspSeatAlignmentConfig(
            enabled = (mask and SEAT_ALIGNMENT_BIT) != 0,
            preset = DspSeatPreset.DRIVER,
            nearSpeaker = DspSeatNearSpeaker.RIGHT,
            distanceDifferenceCm = 45.0,
            leftTrimDb = 1.0,
            rightTrimDb = -1.0,
        ),
        advancedMs = DspAdvancedMsConfig(
            enabled = (mask and ADVANCED_MS_BIT) != 0,
            crossoverHz = 250.0,
            midLowGainDb = -1.0,
            midHighGainDb = 1.0,
            sideLowGainDb = -1.0,
            sideHighGainDb = 1.0,
            lowBandWidth = 0.4,
            highBandWidth = 1.3,
            sideCompressor = DspMsSideCompressorConfig(enabled = true, thresholdDb = -30.0, ratio = 2.0),
        ),
        cabinCorrection = DspCabinCorrectionConfig(
            enabled = (mask and CABIN_CORRECTION_BIT) != 0,
            notches = listOf(
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 60.0, gainDb = -5.0),
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 120.0, gainDb = -4.0),
                DspCabinCorrectionNotch(enabled = true, frequencyHz = 240.0, gainDb = -3.0),
            ),
        ),
        harmonic = DspHarmonicConfig(
            enabled = (mask and HARMONIC_BIT) != 0,
            mode = DspHarmonicMode.MIXED,
            quality = DspHarmonicQuality.MEDIUM,
            exciterEnabled = true,
            exciterAmount = 0.2,
            exciterCutoffHz = 5_000.0,
            virtualBassEnabled = true,
            virtualBassAmount = 0.15,
            virtualBassCutoffHz = 120.0,
        ),
    )

    private fun createPipeline(format: DspAudioFormat, config: DspRuntimeConfig): DspPcmPipeline {
        val processor = NativeDspProcessor.createOrNull(format, config)
        assertNotNull("native graph for $format", processor)
        return DspPcmPipeline(
            format = format,
            processor = requireNotNull(processor),
            seed = DITHER_SEED,
            forceInitialLatencySilence = false,
        )
    }

    private fun processWhole(pipeline: DspPcmPipeline, input: ByteArray): ByteArray {
        assertEquals(input.size, pipeline.process(input, 0, input.size, DspPcmEncoding.PCM16))
        return pipeline.output.copyOfRange(0, pipeline.outputLength)
    }

    private fun processChunked(
        pipeline: DspPcmPipeline,
        input: ByteArray,
        format: DspAudioFormat,
    ): ByteArray {
        val output = ByteArray(input.size)
        var frameOffset = 0
        var chunkIndex = 0
        while (frameOffset < TOTAL_FRAMES) {
            val frames = minOf(CHUNK_FRAMES[chunkIndex % CHUNK_FRAMES.size], TOTAL_FRAMES - frameOffset)
            val byteOffset = frameOffset * format.channels * Short.SIZE_BYTES
            val byteLength = frames * format.channels * Short.SIZE_BYTES
            assertEquals(byteLength, pipeline.process(input, byteOffset, byteLength, DspPcmEncoding.PCM16))
            System.arraycopy(pipeline.output, 0, output, byteOffset, byteLength)
            frameOffset += frames
            chunkIndex++
        }
        return output
    }

    private fun measureHarmonicComponents(
        processor: NativeDspProcessor,
        format: DspAudioFormat,
        inputFrequency: Double,
        aliasFrequency: Double,
    ): Pair<Double, Double> {
        val framesPerBlock = 512
        val totalFrames = 16_384
        val input = ByteBuffer.allocateDirect(framesPerBlock * format.channels * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(framesPerBlock * format.channels * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        var fundamentalSin = 0.0
        var fundamentalCos = 0.0
        var aliasSin = 0.0
        var aliasCos = 0.0
        var measuredFrames = 0
        var baseFrame = 0
        while (baseFrame < totalFrames) {
            repeat(framesPerBlock) { frameInBlock ->
                val absoluteFrame = baseFrame + frameInBlock
                val sample = (0.5 * sin(2.0 * PI * inputFrequency * absoluteFrame / format.sampleRate)).toFloat()
                repeat(format.channels) { channel -> input.putFloat(if (channel == 0) sample else -sample) }
            }
            input.flip()
            assertEquals(DspProcessStatus.SUCCESS, processor.process(input, output, framesPerBlock).status)
            output.flip()
            repeat(framesPerBlock) { frameInBlock ->
                val absoluteFrame = baseFrame + frameInBlock
                if (absoluteFrame >= 1_024) {
                    val sample = output.getFloat(frameInBlock * format.channels * Float.SIZE_BYTES).toDouble()
                    val fundamentalPhase = 2.0 * PI * inputFrequency * absoluteFrame / format.sampleRate
                    val aliasPhase = 2.0 * PI * aliasFrequency * absoluteFrame / format.sampleRate
                    fundamentalSin += sample * sin(fundamentalPhase)
                    fundamentalCos += sample * kotlin.math.cos(fundamentalPhase)
                    aliasSin += sample * sin(aliasPhase)
                    aliasCos += sample * kotlin.math.cos(aliasPhase)
                    measuredFrames++
                }
            }
            input.clear()
            output.clear()
            baseFrame += framesPerBlock
        }
        val scale = 2.0 / measuredFrames
        val fundamental = scale * sqrt(fundamentalSin * fundamentalSin + fundamentalCos * fundamentalCos)
        val alias = scale * sqrt(aliasSin * aliasSin + aliasCos * aliasCos)
        return fundamental to alias
    }

    private fun inputBlock(format: DspAudioFormat, frames: Int): ByteArray {
        val output = ByteArray(frames * format.channels * Short.SIZE_BYTES)
        var offset = 0
        for (frame in 0 until frames) {
            val sample = (
                0.48 * sin(2.0 * PI * 997.0 * frame / format.sampleRate) +
                    0.19 * sin(2.0 * PI * 6_137.0 * frame / format.sampleRate)
                ).coerceIn(-0.9, 0.9)
            val left = (sample * Short.MAX_VALUE).roundToInt().toShort()
            val right = (sample * -0.73 * Short.MAX_VALUE).roundToInt().toShort()
            repeat(format.channels) { channel ->
                val value = if (channel == 0) left else right
                output[offset++] = value.toByte()
                output[offset++] = (value.toInt() shr 8).toByte()
            }
        }
        return output
    }

    private companion object {
        const val TAG = "DspFinalIntegration"
        const val TOTAL_FRAMES = 1_024
        const val DITHER_SEED = 0x4453505354455034L
        const val SEAT_ALIGNMENT_BIT = 1
        const val ADVANCED_MS_BIT = 2
        const val CABIN_CORRECTION_BIT = 4
        const val HARMONIC_BIT = 8
        val CHUNK_FRAMES = intArrayOf(1, 127, 256, 389, 251)
    }
}
