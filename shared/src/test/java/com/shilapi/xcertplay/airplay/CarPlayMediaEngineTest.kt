package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun audioSession(media: AirPlayMediaHandler): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
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

    private fun validAudioSetup(connectionId: Long): Map<String, Any?> = mapOf(
        "streamConnectionID" to connectionId,
        "audioType" to "media",
        "audioFormat" to 0x8000L,
    )

    private class TrackingSink : MediaSink {
        val currentOwner = AtomicReference<AudioOwnerToken?>()
        val stopped = CopyOnWriteArrayList<AudioOwnerToken>()

        override fun claimAudioOwner(token: AudioOwnerToken) {
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
