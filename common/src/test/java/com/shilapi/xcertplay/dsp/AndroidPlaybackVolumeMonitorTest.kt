package com.shilapi.xcertplay.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidPlaybackVolumeMonitorTest {
    @Test
    fun routeChangesProduceDistinctTokensWithoutExposingDeviceAddress() {
        val first = playbackVolumeReading(
            deviceType = 11,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            usesMusicStream = true,
            streamIndex = 4,
            streamMaximum = 16,
            volumeFixed = false,
        )
        val second = playbackVolumeReading(
            deviceType = 11,
            deviceAddress = "11:22:33:44:55:66",
            usesMusicStream = true,
            streamIndex = 4,
            streamMaximum = 16,
            volumeFixed = false,
        )

        assertFalse(first.trustworthy)
        assertNotEquals(first.routeToken, second.routeToken)
        assertFalse(first.routeToken!!.contains("AA:BB"))
        assertEquals(0.25, first.volumeFraction!!, 0.0)
    }

    @Test
    fun missingRouteIdentityUnsupportedStreamAndFixedVolumeRemainUnavailable() {
        val noAddress = playbackVolumeReading(11, "", true, 4, 16, false)
        val wrongStream = playbackVolumeReading(11, "device", false, 4, 16, false)
        val fixed = playbackVolumeReading(11, "device", true, 4, 16, true)
        val unknownRoute = playbackVolumeReading(null, "device", true, 4, 16, false)

        listOf(noAddress, wrongStream, fixed, unknownRoute).forEach { reading ->
            assertFalse(reading.trustworthy)
            assertNull(reading.routeToken)
            assertNull(reading.volumeFraction)
        }
    }

    @Test
    fun unchangedAndroidIndexDoesNotCreateANewVolumeReading() {
        val before = playbackVolumeReading(11, "device", true, 4, 16, false)
        val afterOemKnobChange = playbackVolumeReading(11, "device", true, 4, 16, false)

        assertEquals(before, afterOemKnobChange)
        assertFalse(afterOemKnobChange.trustworthy)
    }

    @Test
    fun calibrationRejectsReadingFromDifferentPlaybackRoute() {
        val firstRoute = DspVolumeReading("route-a", 0.2, trustworthy = true)
        val secondRoute = DspVolumeReading("route-b", 0.8, trustworthy = true)
        val calibration = DspLoudnessCalibrationController()

        assertTrue(calibration.captureQuietPoint(firstRoute))
        assertNull(calibration.captureReferencePoint(secondRoute))
        assertNull(calibration.calibration)
    }

    @Test
    fun releasingOneAudioOwnerKeepsAnotherOwnersRouteActive() {
        val owners = PlaybackRouteOwners()
        val first = owners.update(1L, 11, "first-device", true)
        val second = owners.update(2L, 12, "second-device", true)

        assertEquals("second-device", second?.deviceAddress)
        assertEquals("second-device", owners.update(1L, null, null, false)?.deviceAddress)
        assertNull(owners.update(2L, null, null, false))
        assertEquals("first-device", first?.deviceAddress)
    }

    @Test
    fun concurrentRouteRemovalCannotPublishAheadOfAnEarlierSelection() {
        val owners = PlaybackRouteOwners()
        val firstPublishEntered = CountDownLatch(1)
        val releaseFirstPublish = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val secondPublishEntered = CountDownLatch(1)
        val secondThread = AtomicReference<Thread?>()
        val lastPublishedRoute = AtomicReference<PlaybackRoute?>()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                owners.updateAndPublish(1L, 11, "first-device", true) { route ->
                    firstPublishEntered.countDown()
                    check(releaseFirstPublish.await(5, TimeUnit.SECONDS))
                    lastPublishedRoute.set(route)
                }
            }
            assertTrue(firstPublishEntered.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                secondThread.set(Thread.currentThread())
                secondCallStarted.countDown()
                owners.updateAndPublish(1L, null, null, false) { route ->
                    lastPublishedRoute.set(route)
                    secondPublishEntered.countDown()
                }
            }
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS))
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (secondThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, secondThread.get()?.state)
            assertEquals(1L, secondPublishEntered.count)

            releaseFirstPublish.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondPublishEntered.count == 0L)
            assertNull(lastPublishedRoute.get())
        } finally {
            releaseFirstPublish.countDown()
            executor.shutdownNow()
        }
    }
}
