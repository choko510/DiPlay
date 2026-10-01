package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRendererLifecycleTest {
    @Test
    fun startRunsWorkerOnlyOnce() {
        val lifecycle = AudioRendererLifecycle()
        val starts = AtomicInteger()
        val worker = Thread {
            starts.incrementAndGet()
            lifecycle.stopped()
        }

        assertTrue(lifecycle.start(worker))
        assertFalse(lifecycle.start(worker))
        worker.join(1_000)

        assertFalse(worker.isAlive)
        assertTrue(lifecycle.hasStarted)
        assertFalse(lifecycle.isRunning)
        assertTrue(starts.get() == 1)
    }

    @Test
    fun closeBeforeStartPreventsWorkerStartAndSubmitState() {
        val lifecycle = AudioRendererLifecycle()
        val starts = AtomicInteger()
        val worker = Thread { starts.incrementAndGet() }

        lifecycle.close(worker)

        assertFalse(lifecycle.start(worker))
        assertFalse(lifecycle.isRunning)
        assertFalse(lifecycle.hasStarted)
        assertTrue(starts.get() == 0)
    }

    @Test
    fun closeIsIdempotentAndBoundsWorkerJoin() {
        val lifecycle = AudioRendererLifecycle(closeJoinMillis = 500)
        val workerEntered = CountDownLatch(1)
        val worker = Thread {
            workerEntered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        assertTrue(lifecycle.start(worker))
        assertTrue(workerEntered.await(1, TimeUnit.SECONDS))
        lifecycle.close(worker)
        lifecycle.close(worker)

        assertFalse(worker.isAlive)
        assertFalse(lifecycle.isRunning)
    }

    @Test
    fun concurrentStartAndCloseNeverStartsTwice() {
        repeat(20) {
            val lifecycle = AudioRendererLifecycle()
            val go = CountDownLatch(1)
            val starts = AtomicInteger()
            val worker = Thread { starts.incrementAndGet() }
            val starter = Thread { go.await(); lifecycle.start(worker) }
            val closer = Thread { go.await(); lifecycle.close(worker) }

            starter.start()
            closer.start()
            go.countDown()
            starter.join(1_000)
            closer.join(1_000)
            worker.join(1_000)

            assertFalse(starter.isAlive)
            assertFalse(closer.isAlive)
            assertFalse(worker.isAlive)
            assertTrue(starts.get() <= 1)
            assertFalse(lifecycle.isRunning)
        }
    }
}
