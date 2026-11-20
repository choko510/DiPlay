package com.shilapi.xcertplay.speed

enum class SpeedGpsState {
    STOPPED,
    WAITING_FIX,
    RECEIVING,
    PERMISSION_DENIED,
    NO_PROVIDER,
    PROVIDER_DISABLED,
}

enum class SpeedFixSource {
    GPS,
    FUSED,
    TEST,
}

/**
 * One speed sample. [speedKmh] is null when the platform reported a fix that cannot be
 * trusted (missing speed, poor speed accuracy, stale, non-finite). A genuine 0 km/h fix
 * is valid and distinct from "no speed available".
 */
data class SpeedFix(
    val speedKmh: Double?,
    val elapsedRealtimeNanos: Long,
    val source: SpeedFixSource,
    val speedAccuracyMetersPerSecond: Double? = null,
    val rejectReason: String? = null,
) {
    val valid: Boolean get() = speedKmh != null
}

data class SpeedSourceStatus(
    val state: SpeedGpsState,
    val provider: String? = null,
    val lastFixNanos: Long = Long.MIN_VALUE,
    val rejectReason: String? = null,
)

fun interface SpeedFixListener {
    fun onSpeedFix(fix: SpeedFix)
}

/**
 * Platform speed source. Implementations must expose a single underlying subscription and
 * must never require network access.
 */
interface SpeedSource {
    fun start(listener: SpeedFixListener): Boolean

    fun stop()

    fun status(): SpeedSourceStatus
}
