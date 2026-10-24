package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            return (if (source in uninitializedSources) RejectedRecorder(source)
            else Shadow.directlyOn<AudioRecord>(builder, AudioRecord.Builder::class.java.name, "build")).also {
                built += it
            }
        }

        companion object {
            val attempts = CopyOnWriteArrayList<Int>()
            val built = CopyOnWriteArrayList<AudioRecord>()
            var rejectedSources = emptySet<Int>()
            var uninitializedSources = emptySet<Int>()
        }
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
