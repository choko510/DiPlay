package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [MicrophoneUplinkRecorderFailureTest.RecorderBuilder::class])
class MicrophoneUplinkRecorderFailureTest {
    @Before
    fun resetRecorderState() {
        RecorderBuilder.attempts.clear()
        RecorderBuilder.built.clear()
        RecorderBuilder.rejectedSources = emptySet()
        RecorderBuilder.uninitializedSources = emptySet()
        RecorderBuilder.scriptedRecorder = null
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun rejectedSpeechRecognitionSourceDoesNotFallBackAndASeparateUplinkCanStart() {
        RecorderBuilder.rejectedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val rejected = MicrophoneUplink(config("speechrecognition"))

        try {
            assertFalse(rejected.start())
            assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), RecorderBuilder.attempts.toList())
        } finally {
            rejected.close()
        }

        RecorderBuilder.rejectedSources = emptySet()
        val next = MicrophoneUplink(config("speechrecognition"))
        try {
            assertTrue(next.start())
            assertEquals(
                listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_RECOGNITION),
                RecorderBuilder.attempts.toList(),
            )
        } finally {
            next.close()
        }
    }

    @Test
    fun uninitializedSpeechRecognitionRecorderIsReleasedWithoutFallback() {
        RecorderBuilder.uninitializedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val uplink = MicrophoneUplink(config("speechrecognition"))

        try {
            assertFalse(uplink.start())
            assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), RecorderBuilder.attempts.toList())
            assertEquals(1, RecorderBuilder.built.size)
            assertTrue((RecorderBuilder.built.single() as RejectedRecorder).released)
        } finally {
            uplink.close()
        }
    }

    @Test
    fun rejectedTelephonySourceDoesNotTryAnotherCaptureRoute() {
        RecorderBuilder.rejectedSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        val uplink = MicrophoneUplink(config("telephony"))

        try {
            assertFalse(uplink.start())
            assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts.toList())
        } finally {
            uplink.close()
        }
    }

    @Test
    fun zeroReadsSendNoPacketsAndCaptureResumesAfterPositiveRead() {
        val receiver = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = 100
        }
        val scripted = ScriptedCaptureRecorder()
        RecorderBuilder.scriptedRecorder = scripted
        val uplink = MicrophoneUplink(
            config("default").copy(
                host = InetAddress.getLoopbackAddress(),
                port = receiver.localPort,
            ),
        )

        try {
            assertTrue(uplink.start())
            uplink.activate()
            assertTrue(scripted.zeroReadsReturned.await(2, TimeUnit.SECONDS))
            assertNoPacket(receiver)

            scripted.allowPositiveRead.countDown()
            receiver.soTimeout = 2_000
            val received = DatagramPacket(ByteArray(2_048), 2_048)
            receiver.receive(received)
            assertEquals(12 + 640 + 16 + 8, received.length)

            receiver.soTimeout = 100
            assertNoPacket(receiver)
        } finally {
            scripted.allowPositiveRead.countDown()
            uplink.close()
            receiver.close()
            RecorderBuilder.scriptedRecorder = null
        }
    }

    private fun assertNoPacket(receiver: DatagramSocket) {
        try {
            receiver.receive(DatagramPacket(ByteArray(2_048), 2_048))
            fail("A microphone packet was sent without a positive AudioRecord read")
        } catch (_: SocketTimeoutException) {
            // No captured frame is available for transmission.
        }
    }

    private fun config(audioType: String) = MicrophoneConfig(
        audioType = audioType,
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 9,
        key = ByteArray(32),
        codec = AudioCodecKind.LPCM,
    )

    @Implements(AudioRecord.Builder::class)
    class RecorderBuilder {
        @RealObject
        private lateinit var builder: AudioRecord.Builder

        @Implementation
        fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            attempts += source
            if (source in rejectedSources) throw UnsupportedOperationException("Unsupported test capture source")
            return (scriptedRecorder ?: if (source in uninitializedSources) RejectedRecorder(source)
            else Shadow.directlyOn<AudioRecord>(builder, AudioRecord.Builder::class.java.name, "build")).also {
                built += it
            }
        }

        companion object {
            val attempts = CopyOnWriteArrayList<Int>()
            val built = CopyOnWriteArrayList<AudioRecord>()
            var rejectedSources = emptySet<Int>()
            var uninitializedSources = emptySet<Int>()
            @Volatile var scriptedRecorder: AudioRecord? = null
        }
    }

    class ScriptedCaptureRecorder : AudioRecord(
        MediaRecorder.AudioSource.MIC,
        16_000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        4_096,
    ) {
        val zeroReadsReturned = CountDownLatch(1)
        val allowPositiveRead = CountDownLatch(1)
        private val reads = AtomicInteger()
        private val frameSent = AtomicInteger()

        override fun getState(): Int = STATE_INITIALIZED

        override fun startRecording() = Unit

        override fun stop() = Unit

        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, readMode: Int): Int {
            when (reads.incrementAndGet()) {
                in 1..3 -> {
                    if (reads.get() == 3) zeroReadsReturned.countDown()
                    return 0
                }
                else -> {
                    if (!allowPositiveRead.await(2, TimeUnit.SECONDS)) return -1
                    if (frameSent.getAndIncrement() > 0) return 0
                    val frameBytes = minOf(sizeInBytes, 640)
                    audioData.fill(0x11, offsetInBytes, offsetInBytes + frameBytes)
                    return frameBytes
                }
            }
        }

        override fun release() = Unit
    }

    class RejectedRecorder(source: Int) : AudioRecord(
        source,
        16_000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        4096,
    ) {
        var released = false
            private set

        override fun getState(): Int = STATE_UNINITIALIZED

        override fun release() {
            released = true
            super.release()
        }
    }
}
