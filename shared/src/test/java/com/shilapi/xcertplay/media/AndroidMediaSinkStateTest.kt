package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioOwnerToken
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidMediaSinkStateTest {
    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }

    @Test fun replacingTheScreenClearsTheOldDecoderAndCodecBeforeTheNextConfig() {
        val sink = AndroidMediaSink()
        val type = 110

        try {
            sink.onVideoCodec(type, VideoCodec.H265)
            sink.onVideoConfig(type, byteArrayOf())
            val oldDecoder = videoDecoder(sink, type)
            assertNotNull(oldDecoder)

            sink.onScreenStreamActive(type, false)

            assertNull(videoDecoder(sink, type))
            assertNull(pendingVideoCodec(sink, type))

            sink.onScreenStreamActive(type, true)
            sink.onVideoCodec(type, VideoCodec.H264)
            sink.onVideoConfig(type, byteArrayOf())

            assertNotNull(videoDecoder(sink, type))
            assertNotSame(oldDecoder, videoDecoder(sink, type))
            assertEquals(VideoCodec.H264, pendingVideoCodec(sink, type))
        } finally {
            sink.close()
        }
    }

    @Test fun retiredVideoDecoderKeepsItsCapturedDiagnosticHandler() {
        val sink = AndroidMediaSink()
        val oldMessages = mutableListOf<String>()
        val currentMessages = mutableListOf<String>()
        sink.setVideoDiagnosticHandler(110) { oldMessages += it }
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val oldDecoder = decoderFactory.invoke(sink, 110)
        sink.setVideoDiagnosticHandler(110) { currentMessages += it }
        @Suppress("UNCHECKED_CAST")
        val report = oldDecoder.javaClass.getDeclaredField("report").apply { isAccessible = true }
            .get(oldDecoder) as (String) -> Unit

        try {
            report("first frame submitted to surface")

            assertEquals(listOf("first frame submitted to surface"), oldMessages)
            assertTrue(currentMessages.isEmpty())
        } finally {
            sink.close()
        }
    }

    @Test fun staleAudioCallbacksCannotReplaceOrRemoveTheCurrentRenderer() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        val ownerA = AudioOwnerToken(100, 1)
        val ownerB = AudioOwnerToken(100, 2)

        try {
            sink.claimAudioOwner(ownerA)
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerA, rendererToken(sink, 100))

            sink.claimAudioOwner(ownerB)
            sink.onAudioRtp(ownerA, format, ByteArray(12), 0)
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerA, rendererToken(sink, 100))
            assertNull(sink.audioPlaybackClock(ownerA))

            sink.onAudioStopped(ownerA)
            assertNull(rendererToken(sink, 100))
            sink.onAudioStarted(ownerB, format, 0)
            assertEquals(ownerB, rendererToken(sink, 100))
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerB, rendererToken(sink, 100))

            sink.onAudioStopped(ownerA)
            sink.releaseAudioOwner(ownerA)
            assertEquals(ownerB, rendererToken(sink, 100))
            assertNull(sink.audioPlaybackClock(ownerA))
        } finally {
            sink.close()
        }
    }

    @Test fun rendererThreadStartFailurePropagatesAndAllowsARetry() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        val token = AudioOwnerToken(100, 1)
        val threadFactoryCalls = AtomicInteger()
        sink.audioRendererThreadFactoryForTest = { task, name ->
            if (threadFactoryCalls.getAndIncrement() == 0) {
                object : Thread(task, name) {
                    override fun start() {
                        throw IllegalStateException("injected renderer worker start failure")
                    }
                }
            } else {
                Thread(task, name)
            }
        }
        sink.claimAudioOwner(token)

        try {
            try {
                sink.onAudioStarted(token, format, 0)
                fail("renderer worker start failure should be returned to the engine")
            } catch (_: IllegalStateException) {
                // Expected injected start failure.
            }
            assertNull(rendererToken(sink, 100))

            sink.audioRendererThreadFactoryForTest = null
            sink.onAudioStarted(token, format, 0)
            assertEquals(token, rendererToken(sink, 100))
        } finally {
            sink.close()
        }
    }

    @Test fun staleMicrophoneStopCannotRemoveTheCurrentUplink() {
        val sink = AndroidMediaSink()
        val ownerA = AudioOwnerToken(100, 1)
        val ownerB = AudioOwnerToken(100, 2)
        val uplink = MicrophoneUplink(
            MicrophoneConfig(
                audioType = "telephony",
                sampleRate = 16_000,
                channels = 1,
                payloadType = 100,
                frameMillis = 20,
                host = InetAddress.getLoopbackAddress(),
                port = 1,
                key = ByteArray(32),
            ),
        )
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(ownerB, uplink)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        val ownersField = AndroidMediaSink::class.java.getDeclaredField("activeAudioOwners").apply {
            isAccessible = true
        }
        val owners = ownersField.get(sink) as Map<*, *>
        uplinks[100] = entry
        sink.claimAudioOwner(ownerB)

        try {
            sink.onMicrophoneStopped(ownerA)
            assertSame(entry, uplinks[100])
            sink.releaseAudioOwner(ownerA)
            assertEquals(ownerB, owners[100])
            sink.onMicrophoneStopped(ownerB)
            assertNull(uplinks[100])
        } finally {
            sink.close()
        }
    }

    @Test fun sameTokenInactiveMicrophoneUplinkIsRecreated() {
        val sink = AndroidMediaSink()
        val token = AudioOwnerToken(100, 1)
        val inactive = FakeMicrophoneUplink()
        val replacement = FakeMicrophoneUplink()
        val creations = AtomicInteger()
        sink.microphoneUplinkFactoryForTest = { _, _ ->
            creations.incrementAndGet()
            replacement
        }
        val config = microphoneConfig()
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(token, inactive)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        sink.claimAudioOwner(token)
        uplinks[100] = entry

        try {
            assertTrue(sink.onMicrophoneStarted(token, config))

            assertEquals(1, creations.get())
            assertTrue(inactive.closed)
            assertSame(replacement, microphoneUplink(sink, 100))
            assertTrue(replacement.isActive)
        } finally {
            sink.close()
        }
    }

    @Test fun sameTokenActiveMicrophoneUplinkIsNotStartedTwice() {
        val sink = AndroidMediaSink()
        val token = AudioOwnerToken(100, 1)
        val active = FakeMicrophoneUplink().apply { activate() }
        val creations = AtomicInteger()
        sink.microphoneUplinkFactoryForTest = { _, _ ->
            creations.incrementAndGet()
            FakeMicrophoneUplink()
        }
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(token, active)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        sink.claimAudioOwner(token)
        uplinks[100] = entry

        try {
            assertTrue(sink.onMicrophoneStarted(token, microphoneConfig()))

            assertEquals(0, creations.get())
            assertSame(active, microphoneUplink(sink, 100))
            assertFalse(active.closed)
        } finally {
            sink.close()
        }
    }

    private fun microphoneConfig() = MicrophoneConfig(
        audioType = "telephony",
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 1,
        key = ByteArray(32),
    )

    private fun microphoneUplink(sink: AndroidMediaSink, type: Int): MicrophoneUplinkController? {
        val field = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        val entry = (field.get(sink) as Map<*, *>)[type] ?: return null
        val uplinkField = entry.javaClass.getDeclaredField("uplink").apply { isAccessible = true }
        return uplinkField.get(entry) as MicrophoneUplinkController
    }

    private class FakeMicrophoneUplink : MicrophoneUplinkController {
        private var active = false
        var closed = false
            private set

        override val isActive: Boolean
            get() = active && !closed

        override fun start(): Boolean = true

        override fun activate() {
            active = true
        }

        override fun close() {
            active = false
            closed = true
        }
    }

    private fun rendererToken(sink: AndroidMediaSink, type: Int): AudioOwnerToken? {
        val field = AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply {
            isAccessible = true
        }
        val renderers = field.get(sink) as Map<*, *>
        val entry = renderers[type] ?: return null
        val token = entry.javaClass.getDeclaredField("token").apply { isAccessible = true }
        return token.get(entry) as AudioOwnerToken
    }

    private fun videoDecoder(sink: AndroidMediaSink, type: Int): Any? =
        (field(sink, "videoDecoders")[type])

    private fun pendingVideoCodec(sink: AndroidMediaSink, type: Int): VideoCodec? =
        (field(sink, "pendingVideoCodec")[type]) as? VideoCodec

    private fun field(sink: AndroidMediaSink, name: String): Map<*, *> =
        sink.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(sink) as Map<*, *>
}
