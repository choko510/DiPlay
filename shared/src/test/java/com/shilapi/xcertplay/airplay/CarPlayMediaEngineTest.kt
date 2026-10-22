package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun replacedScreenStreamCannotDeliverStaleCallbacksOrCloseTheSession() {
        val codecs = AtomicInteger()
        val configs = AtomicInteger()
        val frames = AtomicInteger()
        val screenStates = CopyOnWriteArrayList<Boolean>()
        val sink = object : MediaSink {
            override fun onVideoCodec(type: Int, codec: VideoCodec) {
                codecs.incrementAndGet()
            }

            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                configs.incrementAndGet()
            }

            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                frames.incrementAndGet()
            }

            override fun onScreenStreamActive(type: Int, active: Boolean) {
                screenStates += active
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        val streamKey = CarPlayMediaEngine.StreamKey(session, 110)

        try {
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
            val oldScreen = screenStreams(engine)[streamKey] as ScreenStream
            val oldListener = screenListener(oldScreen)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
            val currentScreen = screenStreams(engine)[streamKey] as ScreenStream
            val currentListener = screenListener(currentScreen)
            assertNotSame(oldScreen, currentScreen)

            oldListener.onCodec(VideoCodec.H264)
            oldListener.onConfig(byteArrayOf(0x67, 0x42))
            oldListener.onFrame(byteArrayOf(0, 0, 0, 1, 0x65))
            oldListener.onClosed(IOException("replaced stream closed"))

            assertEquals(0, codecs.get())
            assertEquals(0, configs.get())
            assertEquals(0, frames.get())
            assertFalse(session.isClosed)
            assertEquals(listOf(true, false, true), screenStates.toList())

            currentListener.onCodec(VideoCodec.H265)
            currentListener.onConfig(byteArrayOf(0x40, 0x01))
            currentListener.onFrame(byteArrayOf(0, 0, 0, 1, 0x26))

            assertEquals(1, codecs.get())
            assertEquals(1, configs.get())
            assertEquals(1, frames.get())

            currentListener.onClosed(null)
            assertTrue(session.isClosed)
            assertEquals(listOf(true, false, true, false), screenStates.toList())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun screenReplacementWaitsForAnInFlightFrameBeforeResettingTheSink() {
        val frameEnteredSink = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementActive = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val activeStarts = AtomicInteger()
        val frameObservedReplacement = AtomicBoolean(false)
        val callbackFailure = AtomicReference<Throwable?>()
        val replacementFailure = AtomicReference<Throwable?>()
        val screenStates = CopyOnWriteArrayList<Boolean>()
        val engineReference = AtomicReference<CarPlayMediaEngine>()
        val screenReference = AtomicReference<ScreenStream>()
        lateinit var session: AirPlaySession
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                frameEnteredSink.countDown()
                check(releaseFrame.await(3, TimeUnit.SECONDS))
                val key = CarPlayMediaEngine.StreamKey(session, type)
                frameObservedReplacement.set(screenStreams(engineReference.get())[key] !== screenReference.get())
            }

            override fun onScreenStreamActive(type: Int, active: Boolean) {
                screenStates += active
                if (active && activeStarts.incrementAndGet() == 2) replacementActive.countDown()
            }
        }
        val engine = CarPlayMediaEngine(sink)
        engineReference.set(engine)
        session = audioSession(engine)
        val streamKey = CarPlayMediaEngine.StreamKey(session, 110)
        assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
        val oldScreen = screenStreams(engine)[streamKey] as ScreenStream
        screenReference.set(oldScreen)
        val oldListener = screenListener(oldScreen)
        val callbackThread = Thread({
            try {
                oldListener.onFrame(byteArrayOf(0, 0, 0, 1, 0x65))
            } catch (error: Throwable) {
                callbackFailure.set(error)
            }
        }, "screen-frame-callback-test").apply { isDaemon = true }

        try {
            callbackThread.start()
            assertTrue(frameEnteredSink.await(2, TimeUnit.SECONDS))
            val replaceThread = Thread({
                replacementStarted.countDown()
                try {
                    assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
                } catch (error: Throwable) {
                    replacementFailure.set(error)
                } finally {
                    replacementFinished.countDown()
                }
            }, "screen-stream-replacement-test").apply { isDaemon = true }
            replaceThread.start()

            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            assertFalse(replacementActive.await(200, TimeUnit.MILLISECONDS))
            releaseFrame.countDown()
            assertTrue(replacementActive.await(2, TimeUnit.SECONDS))
            assertTrue(replacementFinished.await(2, TimeUnit.SECONDS))
            callbackThread.join(2_000)

            assertFalse(callbackThread.isAlive)
            assertNull(callbackFailure.get())
            assertNull(replacementFailure.get())
            assertFalse(frameObservedReplacement.get())
            assertEquals(listOf(true, false, true), screenStates.toList())
        } finally {
            releaseFrame.countDown()
            callbackThread.join(2_000)
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun oldSessionScreenCallbacksAndTeardownCannotReachTheCurrentSessionSink() {
        val frames = AtomicInteger()
        val screenStates = CopyOnWriteArrayList<Boolean>()
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                frames.incrementAndGet()
            }

            override fun onScreenStreamActive(type: Int, active: Boolean) {
                screenStates += active
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val oldSession = audioSession(engine)
        val currentSession = audioSession(engine)
        val oldKey = CarPlayMediaEngine.StreamKey(oldSession, 110)
        val currentKey = CarPlayMediaEngine.StreamKey(currentSession, 110)

        try {
            assertNotNull(engine.onScreen(oldSession, 110, mapOf("streamConnectionID" to 1L)))
            val oldScreen = screenStreams(engine)[oldKey] as ScreenStream
            val oldListener = screenListener(oldScreen)

            assertNotNull(engine.onScreen(currentSession, 110, mapOf("streamConnectionID" to 2L)))
            val currentScreen = screenStreams(engine)[currentKey] as ScreenStream
            val currentListener = screenListener(currentScreen)
            assertNotSame(oldScreen, currentScreen)
            assertEquals(listOf(true, false, true), screenStates.toList())

            oldListener.onFrame(byteArrayOf(0, 0, 0, 1, 0x65))
            engine.onSessionClosed(oldSession)

            assertEquals(0, frames.get())
            assertFalse(currentSession.isClosed)
            assertEquals(listOf(true, false, true), screenStates.toList())

            currentListener.onFrame(byteArrayOf(0, 0, 0, 1, 0x26))
            assertEquals(1, frames.get())
            assertEquals(1, screenStreams(engine).size)
        } finally {
            engine.onSessionClosed(oldSession)
            engine.onSessionClosed(currentSession)
            oldSession.close()
            currentSession.close()
        }
    }

    @Test
    fun invalidAudioSetupKeepsTheCurrentOwnerAndFeedbackState() {
        val sink = TrackingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        try {
            assertNotNull(engine.onAudio(session, 100, validAudioSetup(1L)))
            val owner = sink.currentOwner.get()
            assertEquals(AudioOwnerToken(100, 1), owner)

            val invalidSetups = listOf(
                mapOf("streamConnectionID" to 2L),
                mapOf("streamConnectionID" to 2L, "audioFormat" to 0L),
                mapOf("streamConnectionID" to 2L, "audioFormat" to 0x70000000L),
                mapOf("streamConnectionID" to 2L, "audioFormat" to 0x30000000L),
                mapOf("streamConnectionID" to 2L, "audioFormat" to 0x02000000L),
                mapOf("streamConnectionID" to 2L, "audioFormat" to 0x10000000L),
                mapOf("audioFormat" to 0x8000L),
            )
            invalidSetups.forEach { setup -> assertNull(engine.onAudio(session, 100, setup)) }

            assertEquals(owner, sink.currentOwner.get())
            assertTrue(sink.stopped.isEmpty())
            val feedback = engine.onFeedback(session)!!
            assertEquals(1, (feedback["streams"] as List<*>).size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun failedReplacementStartRestoresTheStillActiveOwner() {
        val sink = TrackingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        try {
            assertNotNull(engine.onAudio(session, 100, validAudioSetup(1L)))
            val ownerA = sink.currentOwner.get()
            assertEquals(AudioOwnerToken(100, 1), ownerA)

            engine.audioStreamTestHooksForTest = AudioStreamTestHooks(
                threadFactory = { task, name ->
                    if (name == "airplay-rtcp-rx") {
                        object : Thread(task, name) {
                            override fun start() {
                                throw IllegalStateException("injected control worker start failure")
                            }
                        }
                    } else {
                        Thread(task, name)
                    }
                },
            )

            assertNull(engine.onAudio(session, 100, validAudioSetup(2L)))

            assertEquals(ownerA, sink.currentOwner.get())
            assertTrue(sink.stopped.contains(AudioOwnerToken(100, 2)))
            assertFalse(sink.stopped.contains(ownerA))
            assertEquals(1, (engine.onFeedback(session)!!["streams"] as List<*>).size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun audioSinkStartFailureIsRetriedBeforeTheNextRtpSubmission() {
        val startAttempts = AtomicInteger()
        val rtpPackets = AtomicInteger()
        val sink = object : MediaSink {
            override fun onAudioStarted(token: AudioOwnerToken, format: AudioFormat, firstSample: Int) {
                if (startAttempts.incrementAndGet() == 1) {
                    throw IllegalStateException("injected sink start failure")
                }
            }

            override fun onAudioRtp(token: AudioOwnerToken, format: AudioFormat, rtp: ByteArray, sample: Int) {
                rtpPackets.incrementAndGet()
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        try {
            assertNotNull(engine.onAudio(session, 100, validAudioSetup(1L)))
            val listener = audioListenerForCurrentState(engine, 100)

            try {
                listener.onStarted(1234)
                fail("injected sink start failure should be observed by the stream callback")
            } catch (_: IllegalStateException) {
                // Expected first start failure.
            }

            listener.onRtp(ByteArray(12), 1234)

            assertEquals(2, startAttempts.get())
            assertEquals(1, rtpPackets.get())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun microphoneStartFailureRetriesAfterBackoffUntilItSucceeds() {
        val now = AtomicLong(1_000_000_000L)
        val microphoneAttempts = AtomicInteger()
        val rtpPackets = AtomicInteger()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean {
                return when (microphoneAttempts.incrementAndGet()) {
                    2, 4 -> true
                    else -> false
                }
            }

            override fun onAudioRtp(token: AudioOwnerToken, format: AudioFormat, rtp: ByteArray, sample: Int) {
                rtpPackets.incrementAndGet()
            }
        }
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        engine.monotonicTimeForTest = { now.get() }
        val session = audioSession(engine, withRemoteAddress = true)
        try {
            assertNotNull(engine.onAudio(session, 100, validMicrophoneAudioSetup(1L)))
            val listener = audioListenerForCurrentState(engine, 100)

            listener.onStarted(1234)
            assertEquals(1, microphoneAttempts.get())

            listener.onRtp(ByteArray(12), 1234)
            now.addAndGet(749_000_000L)
            listener.onRtp(ByteArray(12), 1234)
            assertEquals(1, microphoneAttempts.get())

            now.addAndGet(1_000_000L)
            listener.onRtp(ByteArray(12), 1234)
            assertEquals(2, microphoneAttempts.get())

            now.addAndGet(750_000_000L)
            listener.onRtp(ByteArray(12), 1234)
            assertEquals(3, microphoneAttempts.get())

            now.addAndGet(749_000_000L)
            listener.onRtp(ByteArray(12), 1234)
            assertEquals(3, microphoneAttempts.get())

            now.addAndGet(1_000_000L)
            listener.onRtp(ByteArray(12), 1234)
            assertEquals(4, microphoneAttempts.get())
            assertEquals(6, rtpPackets.get())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun explicitInputFalseKeepsAudioOutputButDoesNotStartMicrophone() {
        val audioStarts = AtomicInteger()
        val microphoneStarts = AtomicInteger()
        val sink = object : MediaSink {
            override fun onAudioStarted(token: AudioOwnerToken, format: AudioFormat, firstSample: Int) {
                audioStarts.incrementAndGet()
            }

            override fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean {
                microphoneStarts.incrementAndGet()
                return true
            }
        }
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = audioSession(engine, withRemoteAddress = true)

        try {
            val setup = validMicrophoneAudioSetup(1L) + ("input" to false)
            assertNotNull(engine.onAudio(session, 100, setup))
            audioListenerForCurrentState(engine, 100).onStarted(1_234)

            assertEquals(1, audioStarts.get())
            assertEquals(0, microphoneStarts.get())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun advertisedVoiceInputsReachTheExistingUplink() {
        val microphoneTypes = CopyOnWriteArrayList<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean {
                microphoneTypes += config.audioType
                return true
            }
        }
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = audioSession(engine, withRemoteAddress = true)

        try {
            val voiceTypes = listOf("default", "compatibility", "telephony", "speechRecognition")
            voiceTypes.forEachIndexed { index, audioType ->
                val setup = validMicrophoneAudioSetup(index.toLong() + 1L) + ("audioType" to audioType)
                assertNotNull(engine.onAudio(session, 100, setup))
                audioListenerForCurrentState(engine, 100).onStarted(1_234)
            }

            assertEquals(
                listOf("default", "compatibility", "telephony", "speechrecognition"),
                microphoneTypes.toList(),
            )
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun microphoneInputRespectsPermissionAndAdvertisedMonoFormat() {
        val microphoneStarts = AtomicInteger()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(token: AudioOwnerToken, config: MicrophoneConfig): Boolean {
                microphoneStarts.incrementAndGet()
                return true
            }
        }
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = audioSession(engine, withRemoteAddress = true)

        try {
            assertNotNull(
                engine.onAudio(
                    session,
                    100,
                    validMicrophoneAudioSetup(1L) + mapOf("audioType" to "default", "audioFormat" to 0x8000L),
                ),
            )
            audioListenerForCurrentState(engine, 100).onStarted(1_234)
            assertEquals(0, microphoneStarts.get())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }

        val disabledEngine = CarPlayMediaEngine(sink, microphoneEnabled = false)
        val disabledSession = audioSession(disabledEngine, withRemoteAddress = true)
        try {
            assertNotNull(disabledEngine.onAudio(disabledSession, 100, validMicrophoneAudioSetup(2L)))
            audioListenerForCurrentState(disabledEngine, 100).onStarted(1_234)
            assertEquals(0, microphoneStarts.get())
        } finally {
            disabledEngine.onSessionClosed(disabledSession)
            disabledSession.close()
        }
    }

    @Test
    fun closedSessionCannotCommitAudioAfterPrepareFinishes() {
        val sink = TrackingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        val prepared = CountDownLatch(1)
        val resumeSetup = CountDownLatch(1)
        val sockets = CopyOnWriteArrayList<DatagramSocket>()
        val workerFactoryCalls = AtomicInteger()
        val setupResult = AtomicReference<Map<String, Any?>?>()
        val setupFailure = AtomicReference<Throwable?>()
        engine.audioStreamTestHooksForTest = AudioStreamTestHooks(
            socketFactory = { DatagramSocket(null).also(sockets::add) },
            threadFactory = { task, name ->
                workerFactoryCalls.incrementAndGet()
                Thread(task, name)
            },
        )
        engine.beforeAudioOwnerCommitForTest = { _, _ ->
            prepared.countDown()
            check(resumeSetup.await(3, TimeUnit.SECONDS))
        }

        try {
            val setupWorker = Thread {
                try {
                    setupResult.set(engine.onAudio(session, 100, validAudioSetup(1L)))
                } catch (error: Throwable) {
                    setupFailure.set(error)
                }
            }.apply { isDaemon = true }
            setupWorker.start()

            assertTrue(prepared.await(3, TimeUnit.SECONDS))
            session.close()
            resumeSetup.countDown()
            setupWorker.join(3_000)

            assertFalse(setupWorker.isAlive)
            assertNull(setupFailure.get())
            assertNull(setupResult.get())
            assertNull(sink.currentOwner.get())
            assertTrue(sink.claims.isEmpty())
            assertEquals(0, workerFactoryCalls.get())
            assertEquals(2, sockets.size)
            assertTrue(sockets.all { it.isClosed })
            assertNull(engine.onFeedback(session))
        } finally {
            resumeSetup.countDown()
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun oldSessionTeardownCannotStopCurrentSessionOrLeakItsFeedback() {
        val sink = TrackingSink()
        val engine = CarPlayMediaEngine(sink)
        val sessionA = audioSession(engine)
        val sessionB = audioSession(engine)
        try {
            assertNotNull(engine.onAudio(sessionA, 100, validAudioSetup(1L)))
            assertNotNull(engine.onAudio(sessionB, 100, validAudioSetup(2L)))
            val ownerB = sink.currentOwner.get()
            assertEquals(AudioOwnerToken(100, 2), ownerB)

            engine.onTeardown(sessionA, 100)
            sessionA.close()

            assertEquals(ownerB, sink.currentOwner.get())
            assertNull(engine.onFeedback(sessionA))
            assertEquals(1, (engine.onFeedback(sessionB)!!["streams"] as List<*>).size)
            assertFalse(sink.stopped.contains(ownerB))
        } finally {
            engine.onSessionClosed(sessionB)
            sessionB.close()
            sessionA.close()
        }
    }

    @Test
    fun delayedGenerationBStartAndRollbackCannotDamageGenerationC() {
        val sink = TrackingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = audioSession(engine)
        val bCommitted = CountDownLatch(1)
        val resumeB = CountDownLatch(1)
        val bResult = AtomicReference<Map<String, Any?>?>()
        val bFailure = AtomicReference<Throwable?>()
        engine.beforeAudioReceiverStartForTest = { token ->
            if (token.generation == 2L) {
                bCommitted.countDown()
                check(resumeB.await(3, TimeUnit.SECONDS))
                throw IllegalStateException("injected delayed B start failure")
            }
        }

        try {
            assertNotNull(engine.onAudio(session, 100, validAudioSetup(1L)))
            val bWorker = Thread {
                try {
                    bResult.set(engine.onAudio(session, 100, validAudioSetup(2L)))
                } catch (error: Throwable) {
                    bFailure.set(error)
                }
            }.apply { isDaemon = true }
            bWorker.start()

            assertTrue(bCommitted.await(3, TimeUnit.SECONDS))
            assertNotNull(engine.onAudio(session, 100, validAudioSetup(3L)))
            val cOwner = sink.currentOwner.get()
            assertEquals(AudioOwnerToken(100, 3), cOwner)

            resumeB.countDown()
            bWorker.join(3_000)

            assertFalse(bWorker.isAlive)
            assertNull(bFailure.get())
            assertNull(bResult.get())
            assertEquals(cOwner, sink.currentOwner.get())
            assertTrue(sink.stopped.contains(AudioOwnerToken(100, 2)))
            assertFalse(sink.stopped.contains(cOwner))
        } finally {
            resumeB.countDown()
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    @Suppress("UNCHECKED_CAST")
    private fun screenStreams(engine: CarPlayMediaEngine): Map<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>

    private fun screenListener(screen: ScreenStream): ScreenStream.Listener =
        ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(screen) as ScreenStream.Listener

    private fun audioSession(media: AirPlayMediaHandler, withRemoteAddress: Boolean = false): AirPlaySession {
        val session = AirPlaySession(
            socket = if (withRemoteAddress) connectedSocket() else Socket(),
            config = AirPlayConfig(
                deviceName = "audio-test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = media,
        )
        val pairVerifyField = AirPlaySession::class.java.getDeclaredField("pairVerify").apply {
            isAccessible = true
        }
        val pairVerify = pairVerifyField.get(session)
        PairVerify::class.java.getDeclaredField("sharedSecret").apply {
            isAccessible = true
            set(pairVerify, ByteArray(32) { it.toByte() })
        }
        return session
    }

    private fun connectedSocket(): Socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
        Socket().apply { connect(InetSocketAddress(server.inetAddress, server.localPort)) }
    }

    private fun validAudioSetup(connectionId: Long): Map<String, Any?> = mapOf(
        "streamConnectionID" to connectionId,
        "audioType" to "media",
        "audioFormat" to 0x8000L,
    )

    private fun validMicrophoneAudioSetup(connectionId: Long): Map<String, Any?> = mapOf(
        "streamConnectionID" to connectionId,
        "audioType" to "telephony",
        "audioFormat" to 0x4000L,
        "dataPort" to 12345,
    )

    private fun audioListenerForCurrentState(engine: CarPlayMediaEngine, type: Int): AudioStream.Listener {
        val statesField = CarPlayMediaEngine::class.java.getDeclaredField("audioStates").apply {
            isAccessible = true
        }
        val states = statesField.get(engine) as Map<*, *>
        val state = states.values.single()
        val slotsField = CarPlayMediaEngine::class.java.getDeclaredField("audioSlots").apply {
            isAccessible = true
        }
        val slot = (slotsField.get(engine) as Map<*, *>)[type]
        val listenerFactory = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "audioListener"
        }.apply { isAccessible = true }
        return listenerFactory.invoke(engine, state, slot) as AudioStream.Listener
    }

    private class TrackingSink : MediaSink {
        val currentOwner = AtomicReference<AudioOwnerToken?>()
        val stopped = CopyOnWriteArrayList<AudioOwnerToken>()
        val claims = CopyOnWriteArrayList<AudioOwnerToken>()

        override fun claimAudioOwner(token: AudioOwnerToken) {
            claims.add(token)
            currentOwner.set(token)
        }

        override fun releaseAudioOwner(token: AudioOwnerToken) {
            currentOwner.compareAndSet(token, null)
        }

        override fun onAudioStopped(token: AudioOwnerToken) {
            stopped.add(token)
            currentOwner.compareAndSet(token, null)
        }
    }
}
