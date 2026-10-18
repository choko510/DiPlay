package com.shilapi.xcertplay.media

data class CarPlayLocalScaleGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val scale: Double,
    val left: Double,
    val top: Double,
) {
    val contentWidth: Double = sourceWidth * scale
    val contentHeight: Double = sourceHeight * scale
    val right: Double = left + contentWidth
    val bottom: Double = top + contentHeight

    data class MappedPoint(val x: Double, val y: Double, val insideContent: Boolean)

    fun normalizedX(x: Double): Double = ((x - left) / contentWidth).coerceIn(0.0, 1.0)

    fun normalizedY(y: Double): Double = ((y - top) / contentHeight).coerceIn(0.0, 1.0)

    fun containsPoint(x: Double, y: Double): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun mapViewportPoint(x: Double, y: Double): MappedPoint {
        return MappedPoint(
            x = normalizedX(x),
            y = normalizedY(y),
            insideContent = containsPoint(x, y),
        )
    }

    companion object {
        fun fit(
            sourceWidth: Int,
            sourceHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int,
        ): CarPlayLocalScaleGeometry? {
            if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
                return null
            }
            val scale = minOf(
                viewportWidth.toDouble() / sourceWidth,
                viewportHeight.toDouble() / sourceHeight,
            )
            val contentWidth = sourceWidth * scale
            val contentHeight = sourceHeight * scale
            return CarPlayLocalScaleGeometry(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                scale = scale,
                left = (viewportWidth - contentWidth) / 2.0,
                top = (viewportHeight - contentHeight) / 2.0,
            )
        }
    }
}
