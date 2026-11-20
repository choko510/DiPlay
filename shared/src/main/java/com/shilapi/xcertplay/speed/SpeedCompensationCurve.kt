package com.shilapi.xcertplay.speed

/**
 * One editable point of the speed compensation curve.
 *
 * Gains are boost-only so that the reserved headroom is a simple upper bound; the
 * compensation never replaces the user's saved EQ, it is layered on top of it.
 */
data class SpeedCurvePoint(
    val speedKmh: Double,
    val volumeDb: Double,
    val lowDb: Double,
    val midDb: Double,
    val highDb: Double,
) {
    init {
        require(speedKmh.isFinite() && speedKmh in 0.0..MAX_SPEED_KMH)
        require(volumeDb.isFinite() && volumeDb in 0.0..MAX_VOLUME_DB)
        require(lowDb.isFinite() && lowDb in 0.0..MAX_LOW_DB)
        require(midDb.isFinite() && midDb in 0.0..MAX_MID_DB)
        require(highDb.isFinite() && highDb in 0.0..MAX_HIGH_DB)
    }

    fun withGains(volumeDb: Double, lowDb: Double, midDb: Double, highDb: Double): SpeedCurvePoint =
        copy(
            volumeDb = volumeDb.coerceIn(0.0, MAX_VOLUME_DB),
            lowDb = lowDb.coerceIn(0.0, MAX_LOW_DB),
            midDb = midDb.coerceIn(0.0, MAX_MID_DB),
            highDb = highDb.coerceIn(0.0, MAX_HIGH_DB),
        )

    companion object {
        const val MAX_SPEED_KMH = 250.0
        const val MAX_VOLUME_DB = 6.0
        const val MAX_LOW_DB = 6.0
        const val MAX_MID_DB = 3.0
        const val MAX_HIGH_DB = 4.0
    }
}

/**
 * Piecewise linear speed -> compensation curve. Speeds below the first point and above
 * the last point are clamped, so the curve can never extrapolate to unbounded gain.
 */
data class SpeedCompensationCurve(val points: List<SpeedCurvePoint>) {
    init {
        require(points.size >= MIN_POINTS) { "SPEED_CURVE_POINTS: ${points.size} < $MIN_POINTS" }
        require(points.first().speedKmh == 0.0) { "SPEED_CURVE_ORIGIN: first point must be 0 km/h" }
        require(points.zipWithNext().all { (lower, upper) -> upper.speedKmh > lower.speedKmh }) {
            "SPEED_CURVE_ORDER: speeds must strictly increase"
        }
    }

    fun volumeDbAt(speedKmh: Double): Double = interpolate(speedKmh) { it.volumeDb }

    fun lowDbAt(speedKmh: Double): Double = interpolate(speedKmh) { it.lowDb }

    fun midDbAt(speedKmh: Double): Double = interpolate(speedKmh) { it.midDb }

    fun highDbAt(speedKmh: Double): Double = interpolate(speedKmh) { it.highDb }

    val maxVolumeDb: Double get() = points.maxOf(SpeedCurvePoint::volumeDb)
    val maxLowDb: Double get() = points.maxOf(SpeedCurvePoint::lowDb)
    val maxMidDb: Double get() = points.maxOf(SpeedCurvePoint::midDb)
    val maxHighDb: Double get() = points.maxOf(SpeedCurvePoint::highDb)

    /** Worst-case additive boost used to reserve auto-headroom for the whole curve. */
    val worstCaseBoostDb: Double get() = maxVolumeDb + maxLowDb + maxMidDb + maxHighDb

    val isNeutral: Boolean get() = worstCaseBoostDb <= 0.0

    fun scaledBy(factor: Double): SpeedCompensationCurve = scale(factor)

    fun withPoint(index: Int, speedKmh: Double, volumeDb: Double, lowDb: Double, midDb: Double, highDb: Double): SpeedCompensationCurve {
        require(index in points.indices) { "SPEED_CURVE_INDEX: $index out of range" }
        val updated = points.toMutableList()
        updated[index] = SpeedCurvePoint(
            speedKmh = if (index == 0) 0.0 else speedKmh.coerceIn(0.0, SpeedCurvePoint.MAX_SPEED_KMH),
            volumeDb = volumeDb.coerceIn(0.0, SpeedCurvePoint.MAX_VOLUME_DB),
            lowDb = lowDb.coerceIn(0.0, SpeedCurvePoint.MAX_LOW_DB),
            midDb = midDb.coerceIn(0.0, SpeedCurvePoint.MAX_MID_DB),
            highDb = highDb.coerceIn(0.0, SpeedCurvePoint.MAX_HIGH_DB),
        )
        // Keep points strictly ordered even when a user drags a speed slider past its neighbour.
        for (position in 1 until updated.size) {
            if (updated[position].speedKmh <= updated[position - 1].speedKmh) {
                val minimum = updated[position - 1].speedKmh + 1.0
                if (minimum > SpeedCurvePoint.MAX_SPEED_KMH) return this
                updated[position] = updated[position].copy(speedKmh = minimum)
            }
        }
        return SpeedCompensationCurve(updated)
    }

    private fun scale(factor: Double): SpeedCompensationCurve {
        require(factor.isFinite() && factor >= 0.0)
        return SpeedCompensationCurve(
            points.map { point ->
                point.withGains(
                    volumeDb = point.volumeDb * factor,
                    lowDb = point.lowDb * factor,
                    midDb = point.midDb * factor,
                    highDb = point.highDb * factor,
                )
            },
        )
    }

    private inline fun interpolate(speedKmh: Double, value: (SpeedCurvePoint) -> Double): Double {
        if (speedKmh.isNaN()) return 0.0
        val speed = speedKmh.coerceIn(0.0, SpeedCurvePoint.MAX_SPEED_KMH)
        if (speed <= points.first().speedKmh) return value(points.first())
        if (speed >= points.last().speedKmh) return value(points.last())
        for (index in 1 until points.size) {
            val upper = points[index]
            if (speed <= upper.speedKmh) {
                val lower = points[index - 1]
                val span = upper.speedKmh - lower.speedKmh
                val fraction = if (span <= 0.0) 1.0 else (speed - lower.speedKmh) / span
                return value(lower) + (value(upper) - value(lower)) * fraction
            }
        }
        return value(points.last())
    }

    companion object {
        const val MIN_POINTS = 2

        /** Speeds offered by the curve editor; the values stay user editable. */
        val DEFAULT_SPEEDS_KMH = listOf(0.0, 30.0, 60.0, 100.0, 120.0)

        /** No compensation at all; used by the OFF preset and reset. */
        val NEUTRAL = SpeedCompensationCurve(
            DEFAULT_SPEEDS_KMH.map { speed ->
                SpeedCurvePoint(speed, volumeDb = 0.0, lowDb = 0.0, midDb = 0.0, highDb = 0.0)
            },
        )

        /**
         * Standard curve. Listening in a moving car needs a little loudness and presence,
         * but the amounts stay small enough that the reserved headroom keeps peaks safe.
         */
        val STANDARD = SpeedCompensationCurve(
            listOf(
                SpeedCurvePoint(0.0, volumeDb = 0.0, lowDb = 0.0, midDb = 0.0, highDb = 0.0),
                SpeedCurvePoint(30.0, volumeDb = 0.7, lowDb = 0.4, midDb = 0.0, highDb = 0.2),
                SpeedCurvePoint(60.0, volumeDb = 1.5, lowDb = 0.9, midDb = 0.2, highDb = 0.5),
                SpeedCurvePoint(100.0, volumeDb = 2.6, lowDb = 1.6, midDb = 0.4, highDb = 1.0),
                SpeedCurvePoint(120.0, volumeDb = 3.0, lowDb = 2.0, midDb = 0.5, highDb = 1.3),
            ),
        )
    }
}
