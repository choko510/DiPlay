package com.shilapi.xcertplay.media.dsp

import android.media.AudioFormat as AndroidAudioFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.AudioRenderer
import com.shilapi.xcertplay.media.AudioStreamClassifier
import com.shilapi.xcertplay.media.NavigationAudioRoute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class DspOffLegacyPcmTest {
    @Test
    fun disabledDspKeepsPcm16OnTheLegacyPassThroughPath() {
        val runtimeConfig = DspRuntimeConfig.disabled()
        assertFalse(DspStreamPolicy.shouldProcess(runtimeConfig.enabled, DspStreamRole.MEDIA))
        val audioFormat = AudioFormat(
            codec = AudioCodecKind.LPCM,
            sampleRate = 48_000,
            channels = 2,
            payloadType = 100,
            audioType = "media",
        )
        val classification = AudioStreamClassifier.classify(
            audioType = audioFormat.audioType,
            payloadType = audioFormat.payloadType,
            mappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationRoute = NavigationAudioRoute.FULL_BAND,
        )
        val renderer = AudioRenderer(
            format = audioFormat,
            classification = classification,
            dspRuntimeConfig = runtimeConfig,
            advancedAudioChannelMapping = false,
            navigationAudioRoute = NavigationAudioRoute.FULL_BAND,
            transport = "wired",
            audioManager = null,
            mediaBufferMillis = 300,
            report = {},
        )
        val source = byteArrayOf(0x55, 0x34, 0x12, 0xcd.toByte(), 0xab.toByte(), 0x66)

        val normalized = renderer.normalizePcm16(
            source,
            offset = 1,
            length = 4,
            encoding = AndroidAudioFormat.ENCODING_PCM_16BIT,
        )

        assertSame(source, normalized?.bytes)
        assertEquals(1, normalized?.offset)
        assertEquals(4, normalized?.length)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xcd.toByte(), 0xab.toByte()), source.copyOfRange(1, 5))
        renderer.close()
    }
}
