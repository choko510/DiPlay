package com.shilapi.xcertplay

import java.util.ArrayDeque

internal object DspSplitMeasureDiagnostics {
    private const val MAX_MEASUREMENTS = 64
    private const val MAX_REPORTED_MEASUREMENTS = 16
    private val measurements = ArrayDeque<String>(MAX_MEASUREMENTS)

    @Synchronized
    fun record(measurement: String): Boolean {
        if (measurements.contains(measurement)) return false
        if (measurements.size == MAX_MEASUREMENTS) measurements.removeFirst()
        measurements.addLast(measurement)
        return true
    }

    @Synchronized
    fun recentMeasurements(): String = measurements.toList()
        .takeLast(MAX_REPORTED_MEASUREMENTS)
        .joinToString(" || ")
}
