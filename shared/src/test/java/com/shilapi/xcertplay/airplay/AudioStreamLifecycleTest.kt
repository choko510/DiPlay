package com.shilapi.xcertplay.airplay

import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioStreamLifecycleTest {
    @Test
    fun prepareBindsPortsWithoutStartingWorkersAndIsIdempotent() {
        val workerStarts = AtomicInteger()
        val stream = AudioStream(
            key = ByteArray(32),
            testHooks = AudioStreamTestHooks(
                threadFactory = { task, name ->
                    Thread({ workerStarts.incrementAndGet(); task.run() }, name)
                },
            ),
        )

        try {
            val first = stream.prepare()
            val second = stream.prepare()

            assertEquals(first, second)
            assertTrue(first.dataPort in 1..65535)
            assertTrue(first.controlPort in 1..65535)
            assertNull(field(stream, "dataThread"))
            assertNull(field(stream, "controlThread"))
            assertEquals(0, workerStarts.get())
        } finally {
            stream.close()
        }
    }

    @Test
    fun controlBindFailureClosesTheAlreadyBoundDataSocketAndStartsNoWorkers() {
        val bindCount = AtomicInteger()
        val dataSocket = arrayOfNulls<DatagramSocket>(1)
        val workerStarts = AtomicInteger()
        val stream = AudioStream(
            key = ByteArray(32),
            testHooks = AudioStreamTestHooks(
                socketFactory = {
                    if (bindCount.incrementAndGet() == 1) {
                        DatagramSocket(null).also { dataSocket[0] = it }
                    } else {
                        throw SocketException("injected control bind failure")
                    }
                },
                threadFactory = { task, name ->
                    Thread({ workerStarts.incrementAndGet(); task.run() }, name)
                },
            ),
        )

        try {
            stream.prepare()
            throw AssertionError("prepare should fail when the control socket cannot be bound")
        } catch (_: SocketException) {
            // Expected fault-injection result.
        } finally {
            stream.close()
        }

        assertTrue(dataSocket[0]?.isClosed == true)
        assertNull(field(stream, "dataThread"))
        assertNull(field(stream, "controlThread"))
        assertEquals(0, workerStarts.get())
    }

    @Test
    fun dataBindFailureClosesTheSocketAndStartsNoWorkers() {
        val created = arrayOfNulls<DatagramSocket>(1)
        val workerStarts = AtomicInteger()
        val stream = AudioStream(
            key = ByteArray(32),
            testHooks = AudioStreamTestHooks(
                socketFactory = {
                    object : DatagramSocket(null as SocketAddress?) {
                        override fun bind(address: SocketAddress?) {
                            throw SocketException("injected data bind failure")
                        }
                    }.also {
                        created[0] = it
                    }
                },
                threadFactory = { task, name ->
                    Thread({ workerStarts.incrementAndGet(); task.run() }, name)
                },
            ),
        )

        try {
            stream.prepare()
            throw AssertionError("prepare should fail when the data socket cannot be bound")
        } catch (_: SocketException) {
            // Expected fault-injection result.
        } finally {
            stream.close()
        }

        assertTrue(created[0]?.isClosed == true)
        assertNull(field(stream, "dataThread"))
        assertNull(field(stream, "controlThread"))
        assertEquals(0, workerStarts.get())
    }

    @Test
    fun startIsIdempotentAndCloseBeforeStartPreventsActivation() {
        val workerStarted = CountDownLatch(2)
        val workerExited = CountDownLatch(2)
        val workerFactoryCalls = AtomicInteger()
        val stream = AudioStream(
            key = ByteArray(32),
            testHooks = AudioStreamTestHooks(
                threadFactory = { task, name ->
                    workerFactoryCalls.incrementAndGet()
                    Thread({
                        workerStarted.countDown()
                        try {
                            task.run()
                        } finally {
                            workerExited.countDown()
                        }
                    }, name)
                },
            ),
        )
        val listener = object : AudioStream.Listener {}

        stream.prepare()
        stream.start(listener)
        stream.start(listener)
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
        assertEquals(2, workerFactoryCalls.get())
        stream.close()
        assertTrue(workerExited.await(2, TimeUnit.SECONDS))
        stream.close()

        val closedBeforeStart = AudioStream(key = ByteArray(32))
        closedBeforeStart.prepare()
        closedBeforeStart.close()
        closedBeforeStart.start(listener)
        assertNull(field(closedBeforeStart, "dataThread"))
        assertNull(field(closedBeforeStart, "controlThread"))
    }

    @Test
    fun failureStartingControlWorkerClosesSocketsAndJoinsStartedWorker() {
        val dataWorkerExited = CountDownLatch(1)
        val stream = AudioStream(
            key = ByteArray(32),
            testHooks = AudioStreamTestHooks(
                threadFactory = { task, name ->
                    if (name == "airplay-rtcp-rx") {
                        object : Thread(task, name) {
                            override fun start() {
                                throw IllegalStateException("injected control worker start failure")
                            }
                        }
                    } else {
                        Thread({
                            try {
                                task.run()
                            } finally {
                                dataWorkerExited.countDown()
                            }
                        }, name)
                    }
                },
            ),
        )
        stream.prepare()

        try {
            stream.start(object : AudioStream.Listener {})
            throw AssertionError("start should fail when the control worker cannot start")
        } catch (_: IllegalStateException) {
            // Expected fault-injection result.
        }
        stream.close()

        assertTrue(dataWorkerExited.await(2, TimeUnit.SECONDS))
        assertTrue((field(stream, "dataSocket") as DatagramSocket).isClosed)
        assertTrue((field(stream, "controlSocket") as DatagramSocket).isClosed)
        assertFalse((field(stream, "dataThread") as Thread).isAlive)
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
