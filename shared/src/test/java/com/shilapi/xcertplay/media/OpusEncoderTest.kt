package com.shilapi.xcertplay.media

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpusEncoderTest {
    @Test
    fun unavailableOrFailedCodecDoesNotEscapeAnEncodeFailureIntoMicrophoneCapture() {
        val configuration = OpusEncoderConfiguration(
            sampleRate = 16_000,
            channels = 1,
            bitrate = 48_000,
            frameMillis = 20,
        )
        val encoder = OpusEncoder(configuration)

        try {
            val encoded = encoder.encode(ByteArray(configuration.frameBytes))
            assertTrue(encoded.isEmpty() || encoded.all(ByteArray::isNotEmpty))
            encoder.close()
            assertTrue(encoder.encode(ByteArray(configuration.frameBytes)).isEmpty())
        } finally {
            encoder.close()
        }
    }
}
