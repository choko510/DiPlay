package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioOwnerToken
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidMediaSinkStateTest {
    @Test fun retiredVideoObserverCannotClaimAReplacementStreamFrame() {
        val generations = VideoStreamGenerations()
        val oldMain = generations.advance(110)
        val alt = generations.advance(111)
        val newMain = generations.advance(110)

        assertFalse(generations.isCurrent(110, oldMain))
        assertTrue(generations.isCurrent(110, newMain))
        assertTrue(generations.isCurrent(111, alt))
    }

    @Test fun recoveryRequestsForMainAndAltDoNotShareAnInFlightGate() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        val mainStarted = CountDownLatch(1)
        val allowMain = CountDownLatch(1)
        val altCompleted = CountDownLatch(1)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        sink.setVideoRecoveryHandler(110) {
            mainStarted.countDown()
            check(allowMain.await(5, TimeUnit.SECONDS))
        }
        sink.setVideoRecoveryHandler(111) { altCompleted.countDown() }

        try {
            requestRecovery.invoke(sink, 110, sink.videoStreamGenerationForTest(110))
            assertTrue(mainStarted.await(5, TimeUnit.SECONDS))
            requestRecovery.invoke(sink, 111, sink.videoStreamGenerationForTest(111))
            assertTrue(altCompleted.await(5, TimeUnit.SECONDS))
        } finally {
            allowMain.countDown()
            sink.close()
        }
    }

    @Test fun aRetiredVideoDecoderCannotRequestAKeyframeThroughItsReplacementHandler() {
        val sink = AndroidMediaSink()
        val type = 110
        val staleRequests = AtomicInteger()
        val currentRequest = CountDownLatch(1)
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        fun recoveryCallback(decoder: Any): () -> Unit {
            val callback = decoder.javaClass.getDeclaredField("requestKeyFrame").apply { isAccessible = true }
                .get(decoder) as Function0<*>
            return { check(callback.invoke() == Unit) }
        }

        sink.onScreenStreamActive(type, true)
        sink.setVideoRecoveryHandler(type) { staleRequests.incrementAndGet() }
        val oldDecoder = checkNotNull(decoderFactory.invoke(sink, type))
        val oldRecovery = recoveryCallback(oldDecoder)

        try {
            sink.onScreenStreamActive(type, false)
            sink.onScreenStreamActive(type, true)
            sink.setVideoRecoveryHandler(type) { currentRequest.countDown() }
            oldRecovery()

            assertEquals(0, staleRequests.get())
            assertFalse(currentRequest.await(50, TimeUnit.MILLISECONDS))

            val newRecovery = recoveryCallback(checkNotNull(decoderFactory.invoke(sink, type)))
            newRecovery()
            assertTrue(currentRequest.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }

    @Test fun retiredVideoDecoderCannotRequestAKeyframeThroughTheReplacementHandler() {
        val sink = AndroidMediaSink()
        val oldRequests = AtomicInteger()
        val newRequests = CountDownLatch(1)
        val type = 110
        sink.onScreenStreamActive(type, true)
        sink.setVideoRecoveryHandler(type) { oldRequests.incrementAndGet() }
        val oldGeneration = sink.videoStreamGenerationForTest(type)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }

        try {
            sink.onScreenStreamActive(type, false)
            sink.onScreenStreamActive(type, true)
            sink.setVideoRecoveryHandler(type) { newRequests.countDown() }

            requestRecovery.invoke(sink, type, oldGeneration)
            assertEquals(0, oldRequests.get())
            assertFalse(newRequests.await(50, TimeUnit.MILLISECONDS))

            requestRecovery.invoke(sink, type, sink.videoStreamGenerationForTest(type))
            assertTrue(newRequests.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }

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

    @Test fun replacementWaitsForTheOldVideoDecoderStopAckWithoutBlockingTheStreamOwner() {
        val sink = AndroidMediaSink()
        val type = 110
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        sink.onScreenStreamActive(type, true)
        sink.onVideoCodec(type, VideoCodec.H264)
        sink.onVideoConfig(type, byteArrayOf(1))
        val oldDecoder = videoDecoder(sink, type)
        sink.beforeVideoDecoderStopForTest = {
            stopEntered.countDown()
            check(releaseStop.await(5, TimeUnit.SECONDS))
        }

        try {
            sink.onScreenStreamActive(type, false)
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

            sink.onScreenStreamActive(type, true)
            sink.onVideoCodec(type, VideoCodec.H264)
            sink.onVideoConfig(type, byteArrayOf(2))
            assertNull(videoDecoder(sink, type))

            releaseStop.countDown()
            sink.onVideoConfig(type, byteArrayOf(2))
            val replacement = videoDecoder(sink, type)
            assertNotNull(replacement)
            assertNotSame(oldDecoder, replacement)
        } finally {
            releaseStop.countDown()
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
