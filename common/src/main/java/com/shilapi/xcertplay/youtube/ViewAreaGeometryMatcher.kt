package com.shilapi.xcertplay.youtube

import com.shilapi.xcertplay.airplay.CarPlayRect
import kotlin.math.abs

internal data class ViewAreaGeometryMatch(val index: Int, val exactDimensions: Boolean)

internal object ViewAreaGeometryMatcher {
    private const val ASPECT_RATIO_TOLERANCE = 0.015

    fun match(
        areas: List<CarPlayRect>,
        frameWidth: Int,
        frameHeight: Int,
    ): ViewAreaGeometryMatch? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val frameRatio = frameWidth.toDouble() / frameHeight
        val candidates = areas.mapIndexedNotNull { index, area ->
            val areaRatio = area.width.toDouble() / area.height
            val relativeError = abs(frameRatio - areaRatio) / areaRatio
            if (relativeError <= ASPECT_RATIO_TOLERANCE) {
                ViewAreaGeometryMatch(index, frameWidth == area.width && frameHeight == area.height)
            } else {
                null
            }
        }
        return candidates.singleOrNull()
    }
}
