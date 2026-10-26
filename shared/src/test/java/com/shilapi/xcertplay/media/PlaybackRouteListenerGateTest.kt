package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PlaybackRouteListenerGateTest {
    @Test
    fun callbackFromReleasedTrackCannotPublishAfterRelease() {
        val gate = PlaybackRouteListenerGate<Any>()
        val track = Any()
        val publications = AtomicInteger()
        val generation = gate.bind(track)

        assertTrue(gate.release(track) { publications.incrementAndGet() })
        assertFalse(gate.publishIfCurrent(track, generation) { publications.incrementAndGet() })
        assertEquals(1, publications.get())
    }

    @Test
    fun releaseWaitsForCurrentPublicationThenClearsRoute() {
        val gate = PlaybackRouteListenerGate<Any>()
        val track = Any()
        val generation = gate.bind(track)
        val callbackEntered = CountDownLatch(1)
        val continueCallback = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val releaseThread = AtomicReference<Thread?>()
        val publications = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val callback = executor.submit {
                gate.publishIfCurrent(track, generation) {
                    publications.incrementAndGet()
                    callbackEntered.countDown()
                    check(continueCallback.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            val release = executor.submit {
                releaseThread.set(Thread.currentThread())
                releaseEntered.countDown()
                gate.release(track) { publications.incrementAndGet() }
            }
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (releaseThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, releaseThread.get()?.state)
            assertEquals(1, publications.get())

            continueCallback.countDown()
            callback.get(5, TimeUnit.SECONDS)
            release.get(5, TimeUnit.SECONDS)
            assertEquals(2, publications.get())
            assertFalse(gate.publishIfCurrent(track, generation) { publications.incrementAndGet() })
            assertEquals(2, publications.get())
        } finally {
            continueCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun rebindingRejectsCallbackFromPreviousTrackGeneration() {
        val gate = PlaybackRouteListenerGate<Any>()
        val firstTrack = Any()
        val secondTrack = Any()
        val firstGeneration = gate.bind(firstTrack)
        val secondGeneration = gate.bind(secondTrack)
        val publications = AtomicInteger()

        assertFalse(gate.publishIfCurrent(firstTrack, firstGeneration) { publications.incrementAndGet() })
        assertTrue(gate.publishIfCurrent(secondTrack, secondGeneration) { publications.incrementAndGet() })
        assertEquals(1, publications.get())
    }
}
