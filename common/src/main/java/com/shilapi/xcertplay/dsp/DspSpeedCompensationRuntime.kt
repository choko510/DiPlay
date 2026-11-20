package com.shilapi.xcertplay.dsp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.dsp.DspSpeedCompensationConfig
import com.shilapi.xcertplay.speed.GpsSpeedSource
import com.shilapi.xcertplay.speed.SpeedCompensationController
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedCompensationState
import com.shilapi.xcertplay.speed.SpeedFix
import com.shilapi.xcertplay.speed.SpeedGpsState
import com.shilapi.xcertplay.speed.SpeedSource
import com.shilapi.xcertplay.speed.SpeedSourceStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class DspSpeedCompensationStatus(
    val state: SpeedCompensationState,
    val sourceStatus: SpeedSourceStatus,
    val dspEnabled: Boolean,
    val locationPermissionGranted: Boolean,
    val running: Boolean,
) {
    val gpsState: SpeedGpsState get() = sourceStatus.state
}

/**
 * Owns the single GPS speed subscription and publishes the speed compensation layer to the
 * DSP runtime config.
 *
 * Lifecycle rules:
 *  - the GPS subscription exists only while the feature is enabled, DSP is enabled, and a
 *    host (CarPlay screen or DSP settings) is started;
 *  - manual test mode replaces GPS input and stops the GPS subscription;
 *  - leaving every host decays the compensation to neutral instead of freezing it;
 *  - the saved DSP profile, presets, and workspaces are never modified.
 */
internal class DspSpeedCompensationRuntime(
    context: Context,
    private val publishConfig: (DspSpeedCompensationConfig) -> Unit,
    private val dspEnabledProvider: () -> Boolean,
    private val autoHeadroomEnabledProvider: () -> Boolean = { true },
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val store: DspSpeedCompensationStore = DspSpeedCompensationStore(context),
    private val sourceFactory: (Context) -> SpeedSource = { GpsSpeedSource(it) },
) {
    private val applicationContext = context.applicationContext
    private val source: SpeedSource = sourceFactory(applicationContext)
    private val controller = SpeedCompensationController(
        initialSettings = store.load(),
        onStateChanged = ::onConfigurationChanged,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val hostCount = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    // Auto-headroom reserve must not move while compensation gains are still audible, otherwise
    // every decay tick would re-apply the preamp (and rebuild the pipeline). Hold the largest
    // reserve until the layer is exactly neutral, then release it once.
    private var heldReserveDb = 0.0
    // Headroom must be audible-neutral for a moment after it is claimed, so the boost cannot ride
    // on top of the pipeline swap that applies the attenuation.
    private var reservePrimedAtNanos: Long? = null
    private var sourceSubscribed = false
    private val ticker = object : Runnable {
        override fun run() {
            forwardSourceStatus()
            controller.tick(nowNanos())
            if (keepRunning()) {
                updateSourceSubscription()
                handler.postDelayed(this, TICK_INTERVAL_MS)
                return
            }
            running.set(false)
            stopSource()
            if (controller.isStarted()) controller.stop()
        }
    }

    val preferences: DspSpeedCompensationStore get() = store

    fun settings(): SpeedCompensationSettings = controller.currentSettings()

    fun status(): DspSpeedCompensationStatus = DspSpeedCompensationStatus(
        state = controller.state(),
        sourceStatus = source.status(),
        dspEnabled = dspEnabledProvider(),
        locationPermissionGranted = hasLocationPermission(),
        running = running.get(),
    )

    fun attachHost() {
        hostCount.incrementAndGet()
        refresh()
    }

    fun detachHost() {
        val remaining = hostCount.updateAndGet { current -> if (current > 0) current - 1 else 0 }
        if (remaining == 0) {
            // The manual test speed is a settings-screen diagnostic; it must never outlive the
            // screen that enabled it.
            controller.setTestSpeed(null)
            stopSource()
        }
        refresh()
    }

    fun onDspEnabledChanged() {
        refresh()
    }

    fun updateSettings(settings: SpeedCompensationSettings) {
        store.save(settings)
        previewSettings(settings)
    }

    /** Applies settings to the live controller without touching the persisted preferences. */
    fun previewSettings(settings: SpeedCompensationSettings) {
        controller.updateSettings(settings)
        refresh()
    }

    fun resetSettings() {
        store.reset()
        updateSettings(SpeedCompensationSettings())
    }

    fun setTestSpeed(speedKmh: Double?) {
        controller.setTestSpeed(speedKmh)
        refresh()
    }

    fun testSpeed(): Double? = controller.testSpeed()

    fun hasLocationPermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Call after a runtime permission dialog so the source can retry immediately. */
    fun onLocationPermissionResult() {
        if (!hasLocationPermission()) {
            controller.onGpsStatus(
                SpeedSourceStatus(SpeedGpsState.PERMISSION_DENIED, rejectReason = "permission_denied"),
            )
        }
        refresh()
    }

    private fun refresh() {
        if (keepRunning()) {
            if (!controller.isStarted()) controller.start()
            updateSourceSubscription()
            if (running.compareAndSet(false, true)) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            forwardSourceStatus()
            controller.tick(nowNanos())
            return
        }
        if (running.compareAndSet(true, false)) {
            handler.removeCallbacks(ticker)
        }
        stopSource()
        forwardSourceStatus()
        if (controller.isStarted()) controller.stop()
    }

    /**
     * GPS is only subscribed while real speed can actually drive the layer: never in test mode,
     * never without a host, and never while the gains are just fading out.
     */
    private fun updateSourceSubscription() {
        val state = controller.state()
        val wantsGps = state.settings.active && !state.testMode && state.boostAllowed &&
            hostCount.get() > 0
        if (wantsGps) {
            if (!sourceSubscribed) {
                sourceSubscribed = source.start { fix -> onFix(fix) }
            }
            return
        }
        stopSource()
    }

    private fun stopSource() {
        if (!sourceSubscribed) return
        sourceSubscribed = false
        source.stop()
    }

    /**
     * The controller also keeps running while compensation gains are still fading out, so that
     * switching the feature off never snaps the audio level back instantly.
     */
    private fun keepRunning(): Boolean {
        syncBoostAllowed()
        val state = controller.state()
        armReservePriming(state)
        syncBoostPrimed()
        return isLive(state)
    }

    /**
     * The headroom is applied by a pipeline swap, so the boost is held for a moment afterwards
     * instead of ramping on top of a swap that is still being applied.
     */
    private fun armReservePriming(state: SpeedCompensationState) {
        if (heldReserveDb > 0.0 || reservePrimedAtNanos != null) return
        if (!state.boostAllowed || !hasUsableSpeed(state) || !isLive(state)) return
        reservePrimedAtNanos = nowNanos()
        controller.setBoostPrimed(false)
    }

    private fun syncBoostPrimed() {
        val primedAt = reservePrimedAtNanos ?: return
        if (nowNanos() - primedAt >= RESERVE_PRIME_NANOS) {
            reservePrimedAtNanos = null
            controller.setBoostPrimed(true)
        }
    }

    /**
     * Speed boost adds positive gain at runtime, so it is only allowed while Auto Headroom can
     * reserve the matching attenuation. Without it the boost would reach the limiter as a
     * distorted signal that the driver never asked for.
     */
    private fun syncBoostAllowed() {
        controller.setBoostAllowed(autoHeadroomEnabledProvider())
    }

    /**
     * The layer may only claim auto-headroom while it can actually drive gains: DSP enabled, a
     * host (or the test simulation) present, and the feature switched on. Otherwise the layer is
     * neutral and its reserved headroom is released.
     */
    private fun isLive(state: SpeedCompensationState): Boolean {
        if (!dspEnabledProvider()) return false
        val hostAvailable = hostCount.get() > 0 || state.testMode
        return (state.settings.active && hostAvailable) || !state.gains.isNeutral
    }

    /** A non-stale speed estimate is required before the layer may claim headroom. */
    private fun hasUsableSpeed(state: SpeedCompensationState): Boolean =
        state.speedKmh != null && !state.sourceBlocked

    private fun onFix(fix: SpeedFix) {
        controller.onFix(fix)
        forwardSourceStatus()
    }

    /**
     * The manual test simulation has no platform source, so its (stopped) status must not gate the
     * boost; only a real GPS failure should.
     */
    private fun forwardSourceStatus() {
        if (controller.state().testMode) return
        controller.onGpsStatus(source.status())
    }

    private fun onConfigurationChanged(state: SpeedCompensationState) {
        val gains = state.gains
        val live = isLive(state)
        // Waiting for a fix, a denied permission, or a disabled provider must never attenuate the
        // user's music: headroom is only claimed once a usable speed can actually drive a boost.
        val canBoost = live && state.boostAllowed && hasUsableSpeed(state)
        val requestedReserve = if (canBoost && state.settings.active) state.settings.reservedBoostDb else 0.0
        val previousReserveDb = heldReserveDb
        heldReserveDb = if (gains.isNeutral) {
            requestedReserve
        } else {
            maxOf(heldReserveDb, requestedReserve, gains.worstCaseBoostDb)
        }
        if (heldReserveDb > 0.0 && previousReserveDb == 0.0 &&
            reservePrimedAtNanos == null
        ) {
            reservePrimedAtNanos = nowNanos()
            controller.setBoostPrimed(false)
        } else if (heldReserveDb == 0.0) {
            reservePrimedAtNanos = null
        }
        val reserved = if (canBoost || !gains.isNeutral) heldReserveDb else 0.0
        publishConfig(
            DspSpeedCompensationConfig(
                // Disabled while neutral keeps the stage bit-exact and free of per-frame work.
                enabled = canBoost || !gains.isNeutral,
                volumeGainDb = gains.volumeDb.coerceAtMost(DspSpeedCompensationConfig.MAX_VOLUME_GAIN_DB),
                lowGainDb = gains.lowDb.coerceAtMost(DspSpeedCompensationConfig.MAX_LOW_GAIN_DB),
                midGainDb = gains.midDb.coerceAtMost(DspSpeedCompensationConfig.MAX_MID_GAIN_DB),
                highGainDb = gains.highDb.coerceAtMost(DspSpeedCompensationConfig.MAX_HIGH_GAIN_DB),
                reservedBoostDb = reserved.coerceAtMost(DspSpeedCompensationConfig.MAX_RESERVED_BOOST_DB),
            ),
        )
    }

    private companion object {
        const val TICK_INTERVAL_MS = 200L
        const val RESERVE_PRIME_NANOS = 350_000_000L
    }
}
