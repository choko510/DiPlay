package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneUplinkLifecycleTest {
    @Test
    fun awaitStoppedTreatsZeroAndNegativeTimeoutsAsPolls() {
        val uplink = MicrophoneUplink(microphoneConfig())
        val releaseWorker = CountDownLatch(1)
        val worker = Thread {
            while (releaseWorker.count > 0L) {
                try {
                    releaseWorker.await()
                } catch (_: InterruptedException) {
                    // Keep the fixture blocked until its explicit release.
                }
            }
        }.apply { isDaemon = true }
        MicrophoneUplink::class.java.getDeclaredField("thread").apply {
            isAccessible = true
            set(uplink, worker)
        }
        worker.start()

        try {
            assertFalse(awaitFromDaemon(uplink, 0L))
            assertFalse(awaitFromDaemon(uplink, -1L))
            releaseWorker.countDown()
            assertTrue(awaitFromDaemon(uplink, 1_000L))
        } finally {
            releaseWorker.countDown()
            worker.join(1_000L)
            uplink.close()
        }
    }

    @Test
    fun emptyReadDelayIsLinearAndCappedAtFiftyMilliseconds() {
        assertEquals(5L, microphoneZeroReadBackoffMs(1))
        assertEquals(25L, microphoneZeroReadBackoffMs(5))
        assertEquals(50L, microphoneZeroReadBackoffMs(10))
        assertEquals(50L, microphoneZeroReadBackoffMs(100))
    }

    @Test
    fun closeBeforeStartPreventsAStaleUplinkFromStartingLater() {
        val uplink = MicrophoneUplink(
            microphoneConfig(),
        )

        uplink.close()

        assertFalse(uplink.start())
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
        codec = AudioCodecKind.LPCM,
    )

    private fun awaitFromDaemon(uplink: MicrophoneUplink, timeoutMillis: Long): Boolean {
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(true)
        Thread({
            result.set(uplink.awaitStopped(timeoutMillis))
            finished.countDown()
        }, "mic-await-test").apply { isDaemon = true; start() }
        assertTrue(finished.await(500, TimeUnit.MILLISECONDS))
        return result.get()
    }
}
