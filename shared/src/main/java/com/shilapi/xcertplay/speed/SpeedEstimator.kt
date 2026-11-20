package com.shilapi.xcertplay.speed

import kotlin.math.abs
import kotlin.math.exp

/**
 * Tuning for the GPS speed estimator. The defaults are deliberately conservative:
 * light smoothing on acceleration, faster decay on braking, and a stop/resume
 * hysteresis so a parked car never hunts between 0 and a few km/h.
 */
data class SpeedEstimatorConfig(
    val riseTimeConstantSeconds: Double = 1.0,
    val fallTimeConstantSeconds: Double = 0.5,
    val stopSpeedKmh: Double = 3.0,
    val resumeSpeedKmh: Double = 6.0,
    val staleTimeoutNanos: Long = 2_500_000_000L,
    val maxPlausibleSpeedKmh: Double = 300.0,
    val maxJumpKmh: Double = 60.0,
    val minJumpIntervalSeconds: Double = 0.4,
    val jumpRecoverySamples: Int = 2,
) {
    init {
        require(riseTimeConstantSeconds > 0.0)
        require(fallTimeConstantSeconds > 0.0)
        require(stopSpeedKmh >= 0.0 && resumeSpeedKmh > stopSpeedKmh)
        require(staleTimeoutNanos > 0L)
        require(maxPlausibleSpeedKmh > resumeSpeedKmh)
        require(maxJumpKmh > 0.0)
        require(minJumpIntervalSeconds >= 0.0)
        require(jumpRecoverySamples >= 1)
    }
}

data class SpeedEstimate(
    val speedKmh: Double?,
    val stopped: Boolean,
    val stale: Boolean,
) {
    val usable: Boolean get() = speedKmh != null && !stale
}

/**
 * Time based speed estimator.
 *
 * GPS speed arrives at roughly 1 Hz but the interval is not guaranteed, so every
 * smoothing coefficient is derived from the measured interval instead of assuming a
 * fixed update rate. Impossible samples (NaN, negative, too fast) are rejected without
 * resetting the last good value, and stale data is reported separately from a genuine
 * 0 km/h fix.
 */
class SpeedEstimator(private val config: SpeedEstimatorConfig = SpeedEstimatorConfig()) {
    private var lastAcceptedNanos: Long = Long.MIN_VALUE
    private var lastRawKmh: Double = Double.NaN
    private var smoothedKmh: Double = 0.0
    private var stopped: Boolean = true
    private var pendingJumpKmh: Double = Double.NaN
    private var pendingJumpCount: Int = 0
    private var hasFix: Boolean = false

    fun reset() {
        lastAcceptedNanos = Long.MIN_VALUE
        lastRawKmh = Double.NaN
        smoothedKmh = 0.0
        stopped = true
        pendingJumpKmh = Double.NaN
        pendingJumpCount = 0
        hasFix = false
    }

    /**
     * Feeds one raw GPS sample. Returns the estimate after the sample, or the previous
     * estimate when the sample was rejected.
     */
    @Synchronized
    fun onSample(speedKmh: Double, elapsedRealtimeNanos: Long): SpeedEstimate {
        if (!speedKmh.isFinite() || speedKmh < 0.0 || speedKmh > config.maxPlausibleSpeedKmh) {
            return estimate(elapsedRealtimeNanos)
        }
        if (lastAcceptedNanos != Long.MIN_VALUE && elapsedRealtimeNanos <= lastAcceptedNanos) {
            // Monotonic clock went backwards (or a duplicate fix); ignore it.
            return estimate(elapsedRealtimeNanos)
        }
        val intervalSeconds = if (lastAcceptedNanos == Long.MIN_VALUE) {
            0.0
        } else {
            (elapsedRealtimeNanos - lastAcceptedNanos) / NANOS_PER_SECOND
        }
        if (isImplausibleJump(speedKmh, intervalSeconds)) {
            if (!pendingJumpKmh.isFinite() || abs(speedKmh - pendingJumpKmh) > config.maxJumpKmh) {
                pendingJumpKmh = speedKmh
                pendingJumpCount = 1
            } else {
                pendingJumpCount++
            }
            if (pendingJumpCount < config.jumpRecoverySamples) {
                return estimate(elapsedRealtimeNanos)
            }
        }
        pendingJumpKmh = Double.NaN
        pendingJumpCount = 0

        if (!hasFix || intervalSeconds <= 0.0) {
            smoothedKmh = speedKmh
        } else {
            val timeConstant = if (speedKmh >= smoothedKmh) {
                config.riseTimeConstantSeconds
            } else {
                config.fallTimeConstantSeconds
            }
            val alpha = 1.0 - exp(-intervalSeconds / timeConstant)
            smoothedKmh += (speedKmh - smoothedKmh) * alpha
        }
        hasFix = true
        lastAcceptedNanos = elapsedRealtimeNanos
        lastRawKmh = speedKmh
        updateStopState()
        return estimate(elapsedRealtimeNanos)
    }

    /** Re-evaluates staleness. Called periodically even when no GPS callback arrives. */
    @Synchronized
    fun onTick(elapsedRealtimeNanos: Long): SpeedEstimate = estimate(elapsedRealtimeNanos)

    private fun isImplausibleJump(speedKmh: Double, intervalSeconds: Double): Boolean {
        if (!lastRawKmh.isFinite()) return false
        if (intervalSeconds > config.minJumpIntervalSeconds) return false
        return abs(speedKmh - lastRawKmh) > config.maxJumpKmh
    }

    private fun updateStopState() {
        if (stopped) {
            if (smoothedKmh >= config.resumeSpeedKmh) stopped = false
        } else if (smoothedKmh <= config.stopSpeedKmh) {
            stopped = true
        }
    }

    private fun estimate(nowNanos: Long): SpeedEstimate {
        if (!hasFix || lastAcceptedNanos == Long.MIN_VALUE) {
            return SpeedEstimate(speedKmh = 0.0, stopped = true, stale = true)
        }
        val age = nowNanos - lastAcceptedNanos
        val stale = age > config.staleTimeoutNanos || age < 0L
        if (stale) {
            // Report the last estimate as unusable; callers ramp to neutral instead of
            // replaying a stale speed indefinitely.
            return SpeedEstimate(speedKmh = if (stopped) 0.0 else smoothedKmh, stopped = stopped, stale = true)
        }
        return SpeedEstimate(
            speedKmh = if (stopped) 0.0 else smoothedKmh.coerceAtLeast(0.0),
            stopped = stopped,
            stale = false,
        )
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
