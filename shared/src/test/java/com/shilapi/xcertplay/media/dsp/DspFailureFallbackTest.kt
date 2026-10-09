package com.shilapi.xcertplay.media.dsp

import android.media.AudioFormat as AndroidAudioFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.AudioRenderer
import com.shilapi.xcertplay.media.AudioStreamClassifier
import com.shilapi.xcertplay.media.NavigationAudioRoute
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DspFailureFallbackTest {
    @Test
    fun nativeProcessFailureDisablesDspForTheRendererAndLeavesLegacyPcm16Available() {
        val format = AudioFormat(
            codec = AudioCodecKind.LPCM,
            sampleRate = 48_000,
            channels = 2,
            payloadType = 100,
            audioType = "media",
        )
        val classification = AudioStreamClassifier.classify(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationRoute = NavigationAudioRoute.FULL_BAND,
        )
        val renderer = AudioRenderer(
            format = format,
            classification = classification,
            dspRuntimeConfig = DspRuntimeConfig(enabled = true),
            advancedAudioChannelMapping = false,
            navigationAudioRoute = NavigationAudioRoute.FULL_BAND,
            transport = "wired",
            audioManager = null,
            mediaBufferMillis = 300,
            report = {},
        )
        val audioFormat = DspAudioFormat(48_000, 2)
        renderer.dspPipeline = DspPcmPipeline(audioFormat, FailingDspProcessor(audioFormat))
        val source = byteArrayOf(0x34, 0x12, 0xcd.toByte(), 0xab.toByte())

        val handled = renderer.writeDspIfAvailable(
            source = source,
            offset = 0,
            length = source.size,
            encoding = DspPcmEncoding.PCM16,
            sourceSample = 0x1234,
        )
        val legacy = renderer.normalizePcm16(source, 0, source.size, AndroidAudioFormat.ENCODING_PCM_16BIT)

        assertFalse(handled)
        assertNull(renderer.dspPipeline)
        assertNotNull(legacy)
        legacy ?: return
        assertSame(source, legacy.bytes)
        assertArrayEquals(source, legacy.bytes.copyOfRange(legacy.offset, legacy.offset + legacy.length))
        renderer.close()
    }

    private class FailingDspProcessor(override val format: DspAudioFormat) : DspProcessor {
        private val result = DspProcessResult()
        override val latencyFrames: Int = 0

        override fun process(input: ByteBuffer, output: ByteBuffer, frames: Int): DspProcessResult =
            result.failure(frames, latencyFrames, DspBypassReason.NATIVE_FAILURE, errorCode = 1)

        override fun reset() = Unit
        override fun diagnostics(): DspDiagnosticsSnapshot = DspDiagnosticsSnapshot.EMPTY
        override fun close() = Unit
    }
}
