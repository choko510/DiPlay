package com.shilapi.xcertplay.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
    private val second = 1_000_000_000L

    @Test
    fun aFirstFixIsAcceptedImmediatelyButGenuineZeroIsDistinctFromNoFix() {
        val estimator = SpeedEstimator()
        val noFix = estimator.onTick(0L)
        assertTrue(noFix.stale)
        assertEquals(0.0, noFix.speedKmh!!, 0.0)

        val zero = estimator.onSample(0.0, second)
        assertFalse(zero.stale)
        assertTrue(zero.stopped)
        assertEquals(0.0, zero.speedKmh!!, 0.0)

        val moving = estimator.onSample(60.0, 2 * second)
        assertFalse(moving.stale)
        assertFalse(moving.stopped)
        assertTrue(moving.speedKmh!! in 30.0..60.0)
    }

    @Test
    fun smoothingIsTimeBasedSoIrregularUpdateIntervalsConvergeSimilarly() {
        val estimator = SpeedEstimator()
        estimator.onSample(0.0, second)
        var now = 2 * second
        estimator.onSample(60.0, now)
        val oneSecond = estimator.onSample(60.0, now + second).speedKmh!!
        assertTrue("1 s rise should not overshoot the target", oneSecond in 30.0..60.0)
        assertTrue("1 s rise should already follow the target", oneSecond > 30.0)

        val irregular = SpeedEstimator()
        irregular.onSample(0.0, second)
        now = 2 * second
        irregular.onSample(60.0, now)
        var last = 0.0
        repeat(4) {
            now += 250_000_000L
            last = irregular.onSample(60.0, now).speedKmh!!
        }
        assertTrue("250 ms updates converge at roughly the same rate", last > 30.0)
        assertTrue(last < 60.0)
    }

    @Test
    fun stoppingUsesHysteresisAndReportsTheStopZoneAsZero() {
        val estimator = SpeedEstimator()
        var now = second
        estimator.onSample(0.0, now)
        estimator.onSample(60.0, now + second)
        now += 2 * second
        // Drifting down to the 3 km/h stop zone marks the car as stopped and reports 0.
        estimator.onSample(2.0, now)
        val stopped = estimator.onSample(2.0, now + second)
        assertTrue(stopped.stopped)
        assertEquals(0.0, stopped.speedKmh!!, 0.0)
        // 5 km/h is inside the hysteresis band: the estimate must not flicker back to moving.
        val band = estimator.onSample(5.0, now + 2 * second)
        assertTrue(band.stopped)
        // Above the resume threshold the car is moving again.
        val resumed = estimator.onSample(9.0, now + 3 * second)
        assertFalse(resumed.stopped)
        assertTrue(resumed.speedKmh!! > 0.0)
    }

    @Test
    fun gpsLossMarksTheEstimateStaleInsteadOfReplayingTheLastSpeed() {
        val estimator = SpeedEstimator()
        estimator.onSample(80.0, second)
        val fresh = estimator.onSample(80.0, 2 * second)
        assertFalse(fresh.stale)
        val stillFresh = estimator.onTick(2 * second + 2 * second)
        assertFalse(stillFresh.stale)
        val stale = estimator.onTick(2 * second + 3 * second)
        assertTrue(stale.stale)
        assertFalse(stale.usable)
    }

    @Test
    fun invalidSamplesAreRejectedAndDoNotExtendFreshness() {
        val estimator = SpeedEstimator()
        estimator.onSample(50.0, second)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1_000.0).forEach { invalid ->
            val estimate = estimator.onSample(invalid, 2 * second)
            assertFalse(estimate.stale)
            assertEquals(50.0, estimate.speedKmh!!, 1e-9)
        }
        val stale = estimator.onTick(second + 3 * second)
        assertTrue(stale.stale)
    }

    @Test
    fun aSingleImpossibleJumpIsIgnoredButAConfirmedJumpIsFollowed() {
        val estimator = SpeedEstimator()
        estimator.onSample(0.0, 0L)
        val spike = estimator.onSample(200.0, 100_000_000L)
        assertEquals(0.0, spike.speedKmh!!, 0.0)
        assertTrue(spike.stopped)

        val confirmed = estimator.onSample(200.0, 200_000_000L)
        assertFalse(confirmed.stopped)
        assertTrue(confirmed.speedKmh!! > 0.0)
        assertTrue(confirmed.speedKmh!! < 200.0)
    }

    @Test
    fun samplesOlderThanTheLastAcceptedFixAreIgnored() {
        val estimator = SpeedEstimator()
        estimator.onSample(40.0, 4 * second)
        val outOfOrder = estimator.onSample(5.0, 2 * second)
        assertEquals(40.0, outOfOrder.speedKmh!!, 1e-9)
        val reset = SpeedEstimator()
        reset.onSample(40.0, 4 * second)
        reset.reset()
        assertTrue(reset.onTick(5 * second).stale)
    }
}
