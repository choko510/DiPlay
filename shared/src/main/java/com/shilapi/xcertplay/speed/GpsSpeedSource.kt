package com.shilapi.xcertplay.speed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Built-in GPS speed source.
 *
 * Only the on-device GNSS provider is used. The fused provider is accepted as a fallback
 * (it still uses the internal GNSS receiver on head units) but the network provider is
 * never subscribed, so no internet connection or iPhone-supplied speed is involved.
 *
 * A single LocationManager subscription is held per instance and removed on [stop].
 */
class GpsSpeedSource(
    context: Context,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val maxFixAgeNanos: Long = DEFAULT_MAX_FIX_AGE_NANOS,
    private val maxSpeedAccuracyMetersPerSecond: Double = DEFAULT_MAX_SPEED_ACCURACY_MPS,
    private val updateIntervalMillis: Long = DEFAULT_UPDATE_INTERVAL_MILLIS,
) : SpeedSource {
    private val applicationContext = context.applicationContext
    private val locationManager =
        applicationContext.getSystemService(LocationManager::class.java)
    private val lock = Any()

    @Volatile private var listener: SpeedFixListener? = null
    @Volatile private var subscribedProvider: String? = null
    @Volatile private var status = SpeedSourceStatus(SpeedGpsState.STOPPED)
    @Volatile private var lastAcceptedNanos = Long.MIN_VALUE

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = subscribedProvider ?: return
            if (location.provider != provider) return
            val fix = evaluate(location, provider, nowNanos())
            if (fix.valid) lastAcceptedNanos = fix.elapsedRealtimeNanos
            status = SpeedSourceStatus(
                state = if (fix.valid) SpeedGpsState.RECEIVING else status.state,
                provider = provider,
                lastFixNanos = lastAcceptedNanos,
                rejectReason = fix.rejectReason,
            )
            listener?.onSpeedFix(fix)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider != subscribedProvider) return
            status = status.copy(state = SpeedGpsState.WAITING_FIX, rejectReason = null)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider != subscribedProvider) return
            status = status.copy(state = SpeedGpsState.PROVIDER_DISABLED, rejectReason = "provider_disabled")
            // Let the controller decay to neutral immediately instead of freezing the last speed.
            listener?.onSpeedFix(
                SpeedFix(
                    speedKmh = null,
                    elapsedRealtimeNanos = nowNanos(),
                    source = sourceFor(provider),
                    rejectReason = "provider_disabled",
                ),
            )
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: SpeedFixListener): Boolean {
        synchronized(lock) {
            if (subscribedProvider != null) {
                this.listener = listener
                return true
            }
            if (!hasLocationPermission()) {
                status = SpeedSourceStatus(SpeedGpsState.PERMISSION_DENIED, rejectReason = "permission_denied")
                this.listener = listener
                return false
            }
            val provider = selectProvider()
            if (provider == null) {
                status = SpeedSourceStatus(SpeedGpsState.NO_PROVIDER, rejectReason = "no_provider")
                this.listener = listener
                return false
            }
            this.listener = listener
            lastAcceptedNanos = Long.MIN_VALUE
            if (!locationManager.isProviderEnabled(provider)) {
                status = SpeedSourceStatus(
                    SpeedGpsState.PROVIDER_DISABLED,
                    provider = provider,
                    rejectReason = "provider_disabled",
                )
                return false
            }
            status = SpeedSourceStatus(SpeedGpsState.WAITING_FIX, provider = provider)
            return try {
                locationManager.requestLocationUpdates(
                    provider,
                    updateIntervalMillis,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
                subscribedProvider = provider
                true
            } catch (error: SecurityException) {
                status = SpeedSourceStatus(
                    SpeedGpsState.PERMISSION_DENIED,
                    provider = provider,
                    rejectReason = "permission_denied",
                )
                false
            } catch (error: Exception) {
                Log.w(TAG, "Could not subscribe to $provider error=${error.javaClass.simpleName}")
                status = SpeedSourceStatus(
                    SpeedGpsState.NO_PROVIDER,
                    provider = provider,
                    rejectReason = "subscribe_failed",
                )
                false
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            listener = null
            val provider = subscribedProvider
            subscribedProvider = null
            lastAcceptedNanos = Long.MIN_VALUE
            status = SpeedSourceStatus(SpeedGpsState.STOPPED, provider = provider)
            if (provider == null) return
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
    }

    override fun status(): SpeedSourceStatus = status

    /** Drops the current fix so a re-enabled feature does not replay an old speed. */
    fun invalidate() {
        lastAcceptedNanos = Long.MIN_VALUE
    }

    private fun hasLocationPermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun selectProvider(): String? {
        val providers = runCatching { locationManager.allProviders }.getOrDefault(emptyList())
        return when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            else -> null
        }
    }

    internal fun evaluate(location: Location, provider: String, nowNanos: Long): SpeedFix {
        val source = sourceFor(provider)
        fun reject(reason: String): SpeedFix = SpeedFix(
            speedKmh = null,
            elapsedRealtimeNanos = nowNanos,
            source = source,
            speedAccuracyMetersPerSecond = null,
            rejectReason = reason,
        )
        val fixNanos = location.elapsedRealtimeNanos
        val ageNanos = nowNanos - fixNanos
        if (fixNanos <= 0L || ageNanos < 0L || ageNanos > maxFixAgeNanos) return reject("stale_fix")
        if (!location.hasSpeed()) return reject("missing_speed")
        val speedMetersPerSecond = location.speed.toDouble()
        if (!speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0.0 ||
            speedMetersPerSecond * KILOMETERS_PER_HOUR_PER_MPS > MAX_PLAUSIBLE_SPEED_KMH
        ) {
            return reject("implausible_speed")
        }
        var accuracy: Double? = null
        if (location.hasSpeedAccuracy()) {
            val reported = location.speedAccuracyMetersPerSecond.toDouble()
            if (!reported.isFinite() || reported < 0.0 || reported > maxSpeedAccuracyMetersPerSecond) {
                return reject("poor_speed_accuracy")
            }
            accuracy = reported
        }
        return SpeedFix(
            speedKmh = speedMetersPerSecond * KILOMETERS_PER_HOUR_PER_MPS,
            elapsedRealtimeNanos = fixNanos,
            source = source,
            speedAccuracyMetersPerSecond = accuracy,
        )
    }

    private fun sourceFor(provider: String): SpeedFixSource =
        if (provider == LocationManager.GPS_PROVIDER) SpeedFixSource.GPS else SpeedFixSource.FUSED

    companion object {
        private const val TAG = "SpeedGpsSource"
        private const val KILOMETERS_PER_HOUR_PER_MPS = 3.6
        internal const val MAX_PLAUSIBLE_SPEED_KMH = 300.0
        internal const val DEFAULT_MAX_FIX_AGE_NANOS = 5_000_000_000L
        internal const val DEFAULT_MAX_SPEED_ACCURACY_MPS = 8.0
        internal const val DEFAULT_UPDATE_INTERVAL_MILLIS = 1_000L
    }
}
