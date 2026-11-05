package com.shilapi.xcertplay.media

import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import java.nio.ByteBuffer
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpusEncoderTest {
    @Test
    fun runtimeCodecFailureImmediatelyMarksMicrophoneInactiveAndStopsCapture() {
        val configuration = configuration()
        val codec = FakeOpusCodec(FailurePoint.QUEUE)
        val encoder = OpusEncoder(configuration, OpusCodecFactory { codec })
        val microphone = MicrophoneUplink(
            MicrophoneConfig(
                audioType = "telephony",
                sampleRate = configuration.sampleRate,
                channels = configuration.channels,
                payloadType = 100,
                frameMillis = 20,
                host = InetAddress.getLoopbackAddress(),
                port = 1,
                key = ByteArray(32),
                codec = AudioCodecKind.OPUS,
            ),
        )
        val runningField = MicrophoneUplink::class.java.getDeclaredField("running").apply { isAccessible = true }
        val running = runningField.get(microphone) as java.util.concurrent.atomic.AtomicBoolean
        running.set(true)
        MicrophoneUplink::class.java.getDeclaredField("activated").apply {
            isAccessible = true
            setBoolean(microphone, true)
        }
        MicrophoneUplink::class.java.getDeclaredField("opusEncoder").apply {
            isAccessible = true
            set(microphone, encoder)
        }

        try {
            assertTrue(microphone.isActive)

            val socket = DatagramSocket()
            try {
                MicrophoneUplink::class.java.getDeclaredMethod(
                    "sendFrame",
                    DatagramSocket::class.java,
                    MicrophoneCounters::class.java,
                    ByteArray::class.java,
                ).apply { isAccessible = true }
                    .invoke(microphone, socket, MicrophoneCounters(), ByteArray(configuration.frameBytes))
            } finally {
                socket.close()
            }
            assertFalse(running.get())
            assertFalse(microphone.isActive)
            assertTrue(encoder.hasFailed)
            assertEquals(1, codec.stops)
            assertEquals(1, codec.releases)
        } finally {
            microphone.close()
        }
    }

    @Test
    fun initializationFailureReleasesCodecCreatedBeforeConfigureThrows() {
        val codec = FakeOpusCodec(FailurePoint.CONFIGURE)
        val encoder = OpusEncoder(configuration(), OpusCodecFactory { codec })

        assertFalse(encoder.available)
        assertEquals(0, codec.stops)
        assertEquals(1, codec.releases)
    }

    @Test
    fun inputQueueAndOutputExceptionsFailTheCodecAndReleaseItOnce() {
        listOf(FailurePoint.INPUT, FailurePoint.QUEUE, FailurePoint.OUTPUT).forEach { failure ->
            val codec = FakeOpusCodec(failure)
            val encoder = OpusEncoder(configuration(), OpusCodecFactory { codec })

            assertTrue(encoder.available)
            assertTrue(encoder.encode(ByteArray(configuration().frameBytes)).isEmpty())
            assertTrue(encoder.hasFailed)
            assertEquals(1, codec.stops)
            assertEquals(1, codec.releases)
            encoder.close()
            assertEquals(1, codec.releases)
        }
    }

    @Test
    fun temporaryNoOutputDoesNotMarkTheEncoderFailed() {
        val codec = FakeOpusCodec()
        val encoder = OpusEncoder(configuration(), OpusCodecFactory { codec })

        assertTrue(encoder.available)
        assertTrue(encoder.encode(ByteArray(configuration().frameBytes)).isEmpty())
        assertFalse(encoder.hasFailed)
        assertEquals(0, codec.releases)
        encoder.close()
        assertEquals(1, codec.releases)
    }

    @Test
    fun unavailableOrFailedCodecDoesNotEscapeAnEncodeFailureIntoMicrophoneCapture() {
        val configuration = configuration()
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

    private fun configuration() = OpusEncoderConfiguration(
        sampleRate = 16_000,
        channels = 1,
        bitrate = 48_000,
        frameMillis = 20,
    )

    private enum class FailurePoint { CONFIGURE, INPUT, QUEUE, OUTPUT }

    private class FakeOpusCodec(private val failure: FailurePoint? = null) : OpusCodec {
        var stops = 0
            private set
        var releases = 0
            private set

        override fun configure(format: android.media.MediaFormat) {
            if (failure == FailurePoint.CONFIGURE) error("injected configure failure")
        }

        override fun start() = Unit

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            if (failure == FailurePoint.INPUT) error("injected input failure")
            return if (failure == null) MediaCodec.INFO_TRY_AGAIN_LATER else 0
        }

        override fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(1024)

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if (failure == FailurePoint.QUEUE) error("injected queue failure")
        }

        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            if (failure == FailurePoint.OUTPUT) error("injected output failure")
            return MediaCodec.INFO_TRY_AGAIN_LATER
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? = null

        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit

        override fun stop() {
            stops++
        }

        override fun close() {
            releases++
        }
    }
}
