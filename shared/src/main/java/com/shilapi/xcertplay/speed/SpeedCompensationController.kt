package com.shilapi.xcertplay.speed

import kotlin.math.abs
import kotlin.math.exp

/**
 * Applied compensation values. These are transient: they are layered on top of the user's
 * saved DSP profile and never written back to it.
 */
data class SpeedCompensationGains(
    val volumeDb: Double = 0.0,
    val lowDb: Double = 0.0,
    val midDb: Double = 0.0,
    val highDb: Double = 0.0,
) {
    val isNeutral: Boolean
        get() = volumeDb == 0.0 && lowDb == 0.0 && midDb == 0.0 && highDb == 0.0

    val worstCaseBoostDb: Double get() = volumeDb + lowDb + midDb + highDb

    companion object {
        val NEUTRAL = SpeedCompensationGains()
    }
}

data class SpeedCompensationState(
    val gains: SpeedCompensationGains = SpeedCompensationGains.NEUTRAL,
    val speedKmh: Double? = null,
    val stopped: Boolean = true,
    val stale: Boolean = true,
    val gpsState: SpeedGpsState = SpeedGpsState.STOPPED,
    val testMode: Boolean = false,
    val settings: SpeedCompensationSettings = SpeedCompensationSettings(),
    val lastFixNanos: Long = Long.MIN_VALUE,
    val speedAccuracyMetersPerSecond: Double? = null,
    val boostAllowed: Boolean = true,
) {
    val active: Boolean get() = settings.active || !gains.isNeutral
}

/**
 * Turns validated speed fixes into continuously smoothed compensation gains.
 *
 * Responsibilities are deliberately narrow: it never touches AudioTrack, the system
 * volume, or the saved DSP profile. Sampling (GPS), estimation (time based smoothing and
 * staleness), and DSP application (throttled gain targets) each run on their own time
 * scale. A feature that is switched off, or whose GPS input goes stale, decays the gains
 * smoothly to 0 dB instead of stopping abruptly.
 */
class SpeedCompensationController(
    private val estimator: SpeedEstimator = SpeedEstimator(),
    initialSettings: SpeedCompensationSettings = SpeedCompensationSettings(),
    private val smoothingTimeConstantSeconds: Double = 0.3,
    private val minimumEmitIntervalNanos: Long = 150_000_000L,
    private val minimumEmitDeltaDb: Double = 0.05,
    private val onStateChanged: (SpeedCompensationState) -> Unit = {},
) {
    private val lock = Any()
    private var settings = initialSettings
    private var gains = SpeedCompensationGains.NEUTRAL
    private var estimate = SpeedEstimate(speedKmh = 0.0, stopped = true, stale = true)
    private var gpsState = SpeedGpsState.STOPPED
    private var testSpeedKmh: Double? = null
    private var lastTickNanos = Long.MIN_VALUE
    private var lastEmitNanos = Long.MIN_VALUE
    private var lastEmittedGains = SpeedCompensationGains.NEUTRAL
    private var lastFixNanos = Long.MIN_VALUE
    private var lastSpeedAccuracy: Double? = null
    private var started = false
    private var boostAllowed = true

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            // A new session must not replay the speed of the previous one while the GPS has not
            // delivered a fresh fix yet.
            estimator.reset()
            lastTickNanos = Long.MIN_VALUE
            lastEmitNanos = Long.MIN_VALUE
            lastEmittedGains = SpeedCompensationGains.NEUTRAL
            estimate = SpeedEstimate(0.0, stopped = true, stale = true)
            gpsState = SpeedGpsState.WAITING_FIX
        }
        publish(force = true)
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            estimator.reset()
            gains = SpeedCompensationGains.NEUTRAL
            lastEmittedGains = SpeedCompensationGains.NEUTRAL
            estimate = SpeedEstimate(0.0, stopped = true, stale = true)
            testSpeedKmh = null
            gpsState = SpeedGpsState.STOPPED
        }
        publish(force = true)
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun updateSettings(settings: SpeedCompensationSettings) {
        synchronized(lock) {
            if (this.settings == settings) return
            this.settings = settings
        }
        publish(force = true)
    }

    fun currentSettings(): SpeedCompensationSettings = synchronized(lock) { settings }

    fun onGpsStatus(status: SpeedSourceStatus) {
        synchronized(lock) {
            gpsState = status.state
        }
    }

    fun onFix(fix: SpeedFix) {
        synchronized(lock) {
            if (testSpeedKmh != null) return
            if (fix.elapsedRealtimeNanos > lastFixNanos) lastFixNanos = fix.elapsedRealtimeNanos
            lastSpeedAccuracy = fix.speedAccuracyMetersPerSecond
            if (fix.speedKmh == null) {
                // A rejected fix does not extend freshness; staleness handles the decay.
                return
            }
            estimate = estimator.onSample(fix.speedKmh, fix.elapsedRealtimeNanos)
            gpsState = SpeedGpsState.RECEIVING
        }
    }

    /**
     * Enables the manual speed simulation used by the settings screen. Pass null to return
     * to real GPS input. The simulation is never persisted.
     */
    fun setTestSpeed(speedKmh: Double?) {
        synchronized(lock) {
            val previous = testSpeedKmh
            val next = speedKmh?.takeIf { it.isFinite() }?.coerceIn(0.0, SpeedCurvePoint.MAX_SPEED_KMH)
            testSpeedKmh = next
            if (next != null) {
                // The simulation takes effect immediately instead of waiting for the next tick.
                estimate = SpeedEstimate(next, stopped = next <= estimatorStopSpeed(), stale = false)
            } else if (previous != null) {
                // Returning to real GPS input must not reuse the simulated speed.
                estimator.reset()
                estimate = SpeedEstimate(0.0, stopped = true, stale = true)
            }
        }
        publish(force = true)
    }

    /**
     * Gates every applied gain, e.g. while Auto Headroom is disabled and the boost cannot be
     * matched by a reserved preamp attenuation. Disallowed gains ramp down like a normal target.
     */
    fun setBoostAllowed(allowed: Boolean) {
        synchronized(lock) {
            if (boostAllowed == allowed) return
            boostAllowed = allowed
        }
        publish(force = true)
    }

    fun isBoostAllowed(): Boolean = synchronized(lock) { boostAllowed }

    fun testSpeed(): Double? = synchronized(lock) { testSpeedKmh }

    /**
     * Drives smoothing and emits gain targets. Must be called periodically while active;
     * `elapsedRealtimeNanos` keeps the behaviour independent of wall-clock changes.
     */
    fun tick(nowNanos: Long) {
        val changed: Boolean
        synchronized(lock) {
            if (!started) return
            val dt = if (lastTickNanos == Long.MIN_VALUE) {
                0.0
            } else {
                ((nowNanos - lastTickNanos).coerceAtLeast(0L) / NANOS_PER_SECOND).coerceAtMost(MAX_TICK_SECONDS)
            }
            lastTickNanos = nowNanos
            val simulated = testSpeedKmh
            if (simulated != null) {
                estimate = SpeedEstimate(simulated, stopped = simulated <= estimatorStopSpeed(), stale = false)
            } else {
                estimate = estimator.onTick(nowNanos)
            }
            val target = targetGains(settings, estimate)
            // No time has elapsed on the first tick after start: keep the current gains and let
            // the following ticks ramp smoothly instead of snapping to the target.
            val alpha = if (dt <= 0.0) 0.0 else 1.0 - exp(-dt / smoothingTimeConstantSeconds)
            val previous = gains
            gains = SpeedCompensationGains(
                volumeDb = step(previous.volumeDb, target.volumeDb, alpha),
                lowDb = step(previous.lowDb, target.lowDb, alpha),
                midDb = step(previous.midDb, target.midDb, alpha),
                highDb = step(previous.highDb, target.highDb, alpha),
            )
            // Compare against the last value the DSP actually received, not against the previous
            // tick: a long tail of sub-threshold steps would otherwise never be published.
            val delta = maxOf(
                abs(gains.volumeDb - lastEmittedGains.volumeDb),
                abs(gains.lowDb - lastEmittedGains.lowDb),
                abs(gains.midDb - lastEmittedGains.midDb),
                abs(gains.highDb - lastEmittedGains.highDb),
            )
            val becameNeutral = !previous.isNeutral && gains.isNeutral
            val intervalElapsed = lastEmitNanos == Long.MIN_VALUE ||
                nowNanos - lastEmitNanos >= minimumEmitIntervalNanos
            changed = becameNeutral || (intervalElapsed && delta >= minimumEmitDeltaDb)
            if (changed) {
                lastEmitNanos = nowNanos
                lastEmittedGains = gains
            }
        }
        if (changed) publish(force = false)
    }

    fun state(): SpeedCompensationState = synchronized(lock) {
        SpeedCompensationState(
            gains = gains,
            speedKmh = if (estimate.stale) null else estimate.speedKmh,
            stopped = estimate.stopped,
            stale = estimate.stale,
            gpsState = gpsState,
            testMode = testSpeedKmh != null,
            settings = settings,
            lastFixNanos = lastFixNanos,
            speedAccuracyMetersPerSecond = lastSpeedAccuracy,
            boostAllowed = boostAllowed,
        )
    }

    /** Forces the next [tick] to emit, e.g. after the renderer recreated its AudioTrack. */
    fun invalidate() {
        synchronized(lock) {
            lastEmitNanos = Long.MIN_VALUE
        }
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val state = state()
        synchronized(lock) {
            lastEmittedGains = state.gains
            if (force) lastEmitNanos = lastTickNanos
        }
        onStateChanged(state)
    }

    private fun targetGains(settings: SpeedCompensationSettings, estimate: SpeedEstimate): SpeedCompensationGains {
        if (!boostAllowed || !settings.active || !estimate.usable) return SpeedCompensationGains.NEUTRAL
        val speed = estimate.speedKmh ?: return SpeedCompensationGains.NEUTRAL
        val curve = settings.curve
        return SpeedCompensationGains(
            volumeDb = if (settings.volumeActive) curve.volumeDbAt(speed) else 0.0,
            lowDb = if (settings.eqActive) curve.lowDbAt(speed) else 0.0,
            midDb = if (settings.eqActive) curve.midDbAt(speed) else 0.0,
            highDb = if (settings.eqActive) curve.highDbAt(speed) else 0.0,
        )
    }

    private fun step(current: Double, target: Double, alpha: Double): Double {
        val next = current + (target - current) * alpha
        return if (abs(target - next) < SNAP_DB) target else next
    }

    private fun estimatorStopSpeed(): Double = estimatorStopSpeedKmh

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MAX_TICK_SECONDS = 0.5
        const val SNAP_DB = 0.01
        val estimatorStopSpeedKmh = SpeedEstimatorConfig().stopSpeedKmh
    }
}
