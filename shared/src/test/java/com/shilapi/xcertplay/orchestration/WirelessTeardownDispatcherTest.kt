package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessTeardownDispatcherTest {
    @Test
    fun blockingWirelessTeardownRunsOnDaemonWorker() {
        val callerThread = Thread.currentThread()
        val workerThread = AtomicReference<Thread>()
        val completed = CountDownLatch(1)

        WirelessTeardownDispatcher().dispatch {
            workerThread.set(Thread.currentThread())
            completed.countDown()
        }

        assertTrue("teardown worker did not start", completed.await(2, TimeUnit.SECONDS))
        assertNotSame(callerThread, workerThread.get())
        assertTrue(workerThread.get().isDaemon)
    }
}
