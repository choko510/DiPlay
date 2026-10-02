package com.shilapi.xcertplay.media.dsp

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspPipelineBenchmarkInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test
    fun cold1537FrameStereoPipelineCallDoesNotAllocate() {
        val format = DspAudioFormat(48_000, 2)
        val source = inputBlock(format, frames = 1_537)
        val pipeline = DspPcmPipeline(format, IdentityDspProcessor(format), seed = 11)
        try {
            Debug.startAllocCounting()
            var outputLength = 0
            val allocations = try {
                Debug.resetThreadAllocCount()
                outputLength = pipeline.process(source, 0, source.size, DspPcmEncoding.PCM16)
                Debug.getThreadAllocCount()
            } finally {
                Debug.stopAllocCounting()
            }
            assertEquals(source.size, outputLength)
            assertEquals("cold large-block Kotlin allocations", 0, allocations)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun benchmarks44100Mono() = benchmarkMatrix(sampleRate = 44_100, channels = 1)

    @Test
    fun benchmarks44100Stereo() = benchmarkMatrix(sampleRate = 44_100, channels = 2)

    @Test
    fun benchmarks48000Mono() = benchmarkMatrix(sampleRate = 48_000, channels = 1)

    @Test
    fun benchmarks48000Stereo() = benchmarkMatrix(sampleRate = 48_000, channels = 2)

    private fun benchmarkMatrix(sampleRate: Int, channels: Int) {
        assertTrue(NativeDspLibrary.ensureLoaded())
        val format = DspAudioFormat(sampleRate, channels)
        val source = inputBlock(format)
        for (benchmarkCase in benchmarkCases(sampleRate, channels)) {
            val processor = NativeDspProcessor.createOrNull(format, benchmarkCase.config)
            assertNotNull("native graph: ${benchmarkCase.name}", processor)
            processor ?: continue
            val pipeline = DspPcmPipeline(format, processor, forceInitialLatencySilence = false)
            try {
                var block = 0
                while (block < WARMUP_BLOCKS) {
                    assertEquals(source.size, pipeline.process(source, 0, source.size, DspPcmEncoding.PCM16))
                    block++
                }
                assertNoSteadyStateAllocations(pipeline, source)
                val pipelineRuns = ArrayList<DspTimingSummary>(MEASURED_RUNS)
                repeat(MEASURED_RUNS) { runIndex ->
                    pipeline.resetTimingMetrics()
                    block = 0
                    while (block < MEASURED_BLOCKS) {
                        assertEquals(source.size, pipeline.process(source, 0, source.size, DspPcmEncoding.PCM16))
                        block++
                    }
                    val diagnostics = pipeline.diagnostics()
                    val native = diagnostics.nativeProcessUs
                    val fullPipeline = diagnostics.pipelineProcessUs
                    assertEquals(MEASURED_BLOCKS.toLong(), native.sampleCount)
                    assertEquals(MEASURED_BLOCKS.toLong(), fullPipeline.sampleCount)
                    pipelineRuns += fullPipeline
                    Log.i(
                        TAG,
                        "DSP_BENCHMARK case=${benchmarkCase.name} sampleRate=$sampleRate channels=$channels " +
                            "run=${runIndex + 1} blocks=$MEASURED_BLOCKS nativeProcessUs=${native.toMetricString()} " +
                            "pipelineProcessUs=${fullPipeline.toMetricString()}",
                    )
                }
                val blockDurationUs =
                    DspBufferSizing.PROCESSING_CHUNK_FRAMES * 1_000_000L / sampleRate
                val p99CeilingUs = blockDurationUs * 150L / 100L
                val medianPipelineP99Us = pipelineRuns.map(DspTimingSummary::p99Us).sorted()[MEASURED_RUNS / 2]
                assertTrue(
                    "${benchmarkCase.name} median pipeline p99=${medianPipelineP99Us}us exceeded " +
                        "${p99CeilingUs}us over $MEASURED_RUNS runs",
                    medianPipelineP99Us < p99CeilingUs,
                )
            } finally {
                pipeline.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertNoSteadyStateAllocations(pipeline: DspPcmPipeline, source: ByteArray) {
        Debug.startAllocCounting()
        try {
            Debug.resetThreadAllocCount()
            var block = 0
            while (block < ALLOCATION_CHECK_BLOCKS) {
                if (pipeline.process(source, 0, source.size, DspPcmEncoding.PCM16) != source.size) {
                    throw AssertionError("DSP pipeline rejected allocation-check block $block")
                }
                block++
            }
            assertEquals(0, Debug.getThreadAllocCount())
        } finally {
            Debug.stopAllocCounting()
        }
    }

    private fun benchmarkCases(sampleRate: Int, channels: Int): List<BenchmarkCase> {
        val peq15 = peqBands()
        val compressor = DspCompressorConfig(
            enabled = true,
            thresholdDb = -24.0,
            ratio = 4.0,
            attackMs = 5.0,
            releaseMs = 80.0,
            kneeDb = 3.0,
            makeupDb = 1.0,
        )
        val limiter = DspSafetyLimiterConfig(enabled = true, thresholdDb = -1.0)
        val bass = DspBassConfig(enabled = true, gainDb = 5.0, frequencyHz = 90.0)
        val monoBass = DspMonoBassConfig(enabled = true, cutoffHz = 120)
        val multiband = DspMultibandConfig(
            enabled = true,
            low = compressor,
            mid = compressor.copy(thresholdDb = -28.0, ratio = 3.0),
            high = compressor.copy(thresholdDb = -32.0, ratio = 2.0),
        )
        val dynamicEq = DspDynamicEqConfig(
            enabled = true,
            bands = doubleArrayOf(63.0, 250.0, 1_000.0, 4_000.0, 10_000.0).mapIndexed { index, frequency ->
                DspDynamicEqBandConfig(
                    enabled = true,
                    frequencyHz = frequency,
                    thresholdDb = -32.0,
                    ratio = 3.0,
                    attackMs = 8.0,
                    releaseMs = 100.0,
                    maxBoostDb = 3.0,
                    maxCutDb = 6.0,
                    mode = if (index % 2 == 0) DspDynamicEqMode.CUT else DspDynamicEqMode.BOOST,
                )
            },
        )
        val base = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false)
        val peq = DspRuntimeConfig(enabled = true, autoHeadroomEnabled = false, peqBands = peq15)

        return listOf(
            BenchmarkCase("Gain", base.copyConfig(gainDb = 3.0)),
            BenchmarkCase("PEQ15", peq),
            BenchmarkCase(
                "PEQ_Compressor_Limiter",
                DspRuntimeConfig(
                    enabled = true,
                    autoHeadroomEnabled = false,
                    peqBands = peq15,
                    compressor = compressor,
                    safetyLimiter = limiter,
                ),
            ),
            BenchmarkCase(
                "Bass_Stereo_MonoBass",
                DspRuntimeConfig(
                    enabled = true,
                    autoHeadroomEnabled = false,
                    bass = bass,
                    stereoWidth = 1.35,
                    monoBass = monoBass,
                    safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                ),
            ),
            BenchmarkCase(
                "Multiband3",
                DspRuntimeConfig(
                    enabled = true,
                    autoHeadroomEnabled = false,
                    multiband = multiband,
                    safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                ),
            ),
            BenchmarkCase(
                "DynamicEQ",
                DspRuntimeConfig(
                    enabled = true,
                    autoHeadroomEnabled = false,
                    dynamicEq = dynamicEq,
                    safetyLimiter = DspSafetyLimiterConfig(enabled = false),
                ),
            ),
            BenchmarkCase("Convolver4k", convolverConfig(sampleRate, channels, 4_096)),
            BenchmarkCase("Convolver16k", convolverConfig(sampleRate, channels, 16_384)),
            BenchmarkCase("Convolver64k", convolverConfig(sampleRate, channels, 65_536)),
            BenchmarkCase(
                "FullHeavyChain",
                DspRuntimeConfig(
                    enabled = true,
                    gainDb = 2.0,
                    autoHeadroomEnabled = false,
                    peqBands = peq15,
                    bass = bass,
                    compressor = compressor,
                    safetyLimiter = limiter,
                    stereoWidth = 1.25,
                    monoBass = monoBass,
                    convolver = convolverConfig(sampleRate, channels, 16_384).convolver,
                    multiband = multiband,
                    dynamicEq = dynamicEq,
                ),
            ),
        )
    }

    private fun DspRuntimeConfig.copyConfig(gainDb: Double): DspRuntimeConfig = DspRuntimeConfig(
        enabled = enabled,
        gainDb = gainDb,
        autoHeadroomEnabled = autoHeadroomEnabled,
        safetyLimiter = DspSafetyLimiterConfig(enabled = false),
    )

    private fun peqBands(): List<DspEqBand> = doubleArrayOf(
        25.0, 40.0, 63.0, 100.0, 160.0,
        250.0, 400.0, 630.0, 1_000.0, 1_600.0,
        2_500.0, 4_000.0, 6_300.0, 10_000.0, 16_000.0,
    ).mapIndexed { index, frequency ->
        DspEqBand(
            type = DspEqType.PEAK,
            frequencyHz = frequency,
            gainDb = if (index % 2 == 0) 1.5 else -1.5,
            q = 1.1,
        )
    }

    private fun convolverConfig(sampleRate: Int, channels: Int, frames: Int): DspRuntimeConfig {
        val id = "bench_${sampleRate}_${channels}_$frames"
        val samples = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val value = if (frame == 0) 0.5f else (0.02 * kotlin.math.exp(-frame / (frames / 8.0))).toFloat()
            for (channel in 0 until channels) samples[frame * channels + channel] = value
        }
        val impulseResponse = DspImpulseResponse(id, sampleRate, channels, samples)
        return DspRuntimeConfig(
            enabled = true,
            autoHeadroomEnabled = false,
            safetyLimiter = DspSafetyLimiterConfig(enabled = false),
            convolver = DspConvolverConfig(
                enabled = true,
                impulseResponseId = id,
                wet = 1.0,
                impulseResponse = impulseResponse,
            ),
        )
    }

    private fun inputBlock(
        format: DspAudioFormat,
        frames: Int = DspBufferSizing.PROCESSING_CHUNK_FRAMES,
    ): ByteArray {
        val result = ByteArray(DspBufferSizing.pcm16ByteCount(frames, format.channels))
        var offset = 0
        for (frame in 0 until frames) {
            val sample = (
                0.68 * sin(2.0 * PI * 997.0 * frame / format.sampleRate) +
                    0.12 * sin(2.0 * PI * 7_003.0 * frame / format.sampleRate)
                ).coerceIn(-0.95, 0.95)
            val left = (sample * Short.MAX_VALUE).roundToInt().toShort().toInt()
            val right = (-sample * Short.MAX_VALUE).roundToInt().toShort().toInt()
            repeat(format.channels) { channel ->
                val value = if (channel == 0) left else right
                result[offset++] = value.toByte()
                result[offset++] = (value shr 8).toByte()
            }
        }
        return result
    }

    private fun DspTimingSummary.toMetricString(): String =
        "avg:${averageUs.toLong()},p50:$p50Us,p95:$p95Us,p99:$p99Us,max:$maxUs,count:$sampleCount"

    private data class BenchmarkCase(val name: String, val config: DspRuntimeConfig)

    private companion object {
        const val TAG = "DspPipelineBenchmark"
        const val WARMUP_BLOCKS = 64
        const val ALLOCATION_CHECK_BLOCKS = 64
        const val MEASURED_BLOCKS = 256
        const val MEASURED_RUNS = 3
    }
}
