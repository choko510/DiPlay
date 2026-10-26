package com.shilapi.xcertplay

import java.util.ArrayDeque

internal class DspSplitMeasureDiagnostics {
    companion object {
        private const val MAX_MEASUREMENTS = 64
        private const val MAX_REPORTED_MEASUREMENTS = 16
        private const val MAX_LOGGED_MEASUREMENTS = 64
        private val active = DspSplitMeasureDiagnostics()

        fun recordUnboundedBody(minHeight: Int, maxHeight: Int, capHeight: Int): Boolean =
            active.recordUnboundedBody(minHeight, maxHeight, capHeight)

        fun recordViewMeasurement(
            widthMode: Int,
            widthSize: Int,
            heightMode: Int,
            heightSize: Int,
            measuredWidth: Int,
            measuredHeight: Int,
        ): Boolean = active.recordViewMeasurement(
            widthMode,
            widthSize,
            heightMode,
            heightSize,
            measuredWidth,
            measuredHeight,
        )

        fun recentMeasurements(): String = active.recentMeasurements()

        private fun measureMode(mode: Int): String = when (mode) {
            0x40000000 -> "EXACTLY"
            0x80000000.toInt() -> "AT_MOST"
            else -> "UNSPECIFIED"
        }
    }

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

    private data class ViewMeasurementSignature(
        val widthMode: Int,
        val widthSize: Int,
        val heightMode: Int,
        val heightSize: Int,
        val measuredWidth: Int,
        val measuredHeight: Int,
    ) : MeasurementSignature {
        override fun render(): String =
            "widthMode=${measureMode(widthMode)} widthSize=$widthSize " +
                "heightMode=${measureMode(heightMode)} heightSize=$heightSize " +
                "measured=${measuredWidth}x$measuredHeight"
    }

    private val measurements = ArrayDeque<MeasurementSignature>(MAX_MEASUREMENTS)
    private var loggedMeasurementCount = 0

    @Synchronized
    fun recordUnboundedBody(minHeight: Int, maxHeight: Int, capHeight: Int): Boolean {
        if (measurements.any {
                it is UnboundedBodySignature && it.minHeight == minHeight &&
                    it.maxHeight == maxHeight && it.capHeight == capHeight
            }
        ) return false
        if (measurements.size == MAX_MEASUREMENTS) measurements.removeFirst()
        measurements.addLast(UnboundedBodySignature(minHeight, maxHeight, capHeight))
        return allowLog()
    }

    @Synchronized
    fun recordViewMeasurement(
        widthMode: Int,
        widthSize: Int,
        heightMode: Int,
        heightSize: Int,
        measuredWidth: Int,
        measuredHeight: Int,
    ): Boolean {
        if (measurements.any {
                it is ViewMeasurementSignature && it.widthMode == widthMode && it.widthSize == widthSize &&
                    it.heightMode == heightMode && it.heightSize == heightSize &&
                    it.measuredWidth == measuredWidth && it.measuredHeight == measuredHeight
            }
        ) return false
        if (measurements.size == MAX_MEASUREMENTS) measurements.removeFirst()
        measurements.addLast(
            ViewMeasurementSignature(widthMode, widthSize, heightMode, heightSize, measuredWidth, measuredHeight),
        )
        return allowLog()
    }

    @Synchronized
    fun recentMeasurements(): String = measurements.toList()
        .takeLast(MAX_REPORTED_MEASUREMENTS)
        .joinToString(" || ", transform = MeasurementSignature::render)

    private fun allowLog(): Boolean {
        if (loggedMeasurementCount >= MAX_LOGGED_MEASUREMENTS) return false
        loggedMeasurementCount++
        return true
    }
}
