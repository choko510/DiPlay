package com.shilapi.xcertplay

import java.util.ArrayDeque

internal object DspSplitMeasureDiagnostics {
    private const val MAX_MEASUREMENTS = 64
    private const val MAX_REPORTED_MEASUREMENTS = 16
    private sealed interface MeasurementSignature {
        fun render(): String
    }

    private data class UnboundedBodySignature(
        val minHeight: Int,
        val maxHeight: Int,
        val capHeight: Int,
    ) : MeasurementSignature {
        override fun render(): String =
            "minHeight=$minHeight maxHeight=$maxHeight capHeight=$capHeight"
    }

    private data class ViewMeasurementSignature(val measurement: String) : MeasurementSignature {
        override fun render(): String = measurement
    }

    private val measurements = ArrayDeque<MeasurementSignature>(MAX_MEASUREMENTS)

    @Synchronized
    fun recordUnboundedBody(minHeight: Int, maxHeight: Int, capHeight: Int): Boolean {
        if (measurements.any {
                it is UnboundedBodySignature && it.minHeight == minHeight &&
                    it.maxHeight == maxHeight && it.capHeight == capHeight
            }
        ) return false
        if (measurements.size == MAX_MEASUREMENTS) measurements.removeFirst()
        measurements.addLast(UnboundedBodySignature(minHeight, maxHeight, capHeight))
        return true
    }

    @Synchronized
    fun record(measurement: String): Boolean {
        if (measurements.any { it is ViewMeasurementSignature && it.measurement == measurement }) return false
        if (measurements.size == MAX_MEASUREMENTS) measurements.removeFirst()
        measurements.addLast(ViewMeasurementSignature(measurement))
        return true
    }

    @Synchronized
    fun recentMeasurements(): String = measurements.toList()
        .takeLast(MAX_REPORTED_MEASUREMENTS)
        .joinToString(" || ", transform = MeasurementSignature::render)
}
