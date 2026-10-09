package com.shilapi.xcertplay.media

import kotlin.math.min

data class CarPlayRenderRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class CarPlayRenderPoint(val x: Double, val y: Double)

/** Shared fit transform for CarPlay texture rendering and inverse HID touch mapping. */
data class CarPlayRenderGeometry(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceRect: CarPlayRenderRect,
    val viewArea: CarPlayRenderRect,
    val viewWidth: Int,
    val viewHeight: Int,
    val rotationDegrees: Int = 0,
    val generation: Long = 0,
) {
    private val rotation = ((rotationDegrees % 360) + 360) % 360
    private val orientedWidth = if (rotation == 90 || rotation == 270) sourceRect.height else sourceRect.width
    private val orientedHeight = if (rotation == 90 || rotation == 270) sourceRect.width else sourceRect.height
    private val scale = min(viewWidth.toDouble() / orientedWidth, viewHeight.toDouble() / orientedHeight)
    private val contentWidth = orientedWidth * scale
    private val contentHeight = orientedHeight * scale
    private val contentLeft = (viewWidth - contentWidth) / 2.0
    private val contentTop = (viewHeight - contentHeight) / 2.0

    fun mapViewPoint(x: Double, y: Double): CarPlayRenderPoint? {
        if (x < contentLeft || y < contentTop || x > contentLeft + contentWidth || y > contentTop + contentHeight) {
            return null
        }
        val orientedX = ((x - contentLeft) / scale).coerceIn(0.0, orientedWidth.toDouble())
        val orientedY = ((y - contentTop) / scale).coerceIn(0.0, orientedHeight.toDouble())
        val sourcePoint = when (rotation) {
            90 -> CarPlayRenderPoint(
                sourceRect.x + orientedY,
                sourceRect.y + sourceRect.height - orientedX,
            )
            180 -> CarPlayRenderPoint(
                sourceRect.x + sourceRect.width - orientedX,
                sourceRect.y + sourceRect.height - orientedY,
            )
            270 -> CarPlayRenderPoint(
                sourceRect.x + sourceRect.width - orientedY,
                sourceRect.y + orientedX,
            )
            else -> CarPlayRenderPoint(sourceRect.x + orientedX, sourceRect.y + orientedY)
        }
        val canvasX = viewArea.x +
            (sourcePoint.x - sourceRect.x) * viewArea.width / sourceRect.width
        val canvasY = viewArea.y +
            (sourcePoint.y - sourceRect.y) * viewArea.height / sourceRect.height
        return CarPlayRenderPoint(
            (canvasX / canvasWidth).coerceIn(0.0, 1.0),
            (canvasY / canvasHeight).coerceIn(0.0, 1.0),
        )
    }

    fun textureMatrixValues(): FloatArray {
        val s = scale.toFloat()
        val left = contentLeft.toFloat()
        val top = contentTop.toFloat()
        val sx = sourceRect.x.toFloat()
        val sy = sourceRect.y.toFloat()
        val sw = sourceRect.width.toFloat()
        val sh = sourceRect.height.toFloat()
        return when (rotation) {
            90 -> floatArrayOf(0f, -s, left + (sy + sh) * s, s, 0f, top - sx * s, 0f, 0f, 1f)
            180 -> floatArrayOf(-s, 0f, left + (sx + sw) * s, 0f, -s, top + (sy + sh) * s, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, s, left - sy * s, -s, 0f, top + (sx + sw) * s, 0f, 0f, 1f)
            else -> floatArrayOf(s, 0f, left - sx * s, 0f, s, top - sy * s, 0f, 0f, 1f)
        }
    }

    companion object {
        fun create(
            canvasWidth: Int,
            canvasHeight: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
            sourceRect: CarPlayRenderRect = CarPlayRenderRect(0, 0, sourceWidth, sourceHeight),
            viewArea: CarPlayRenderRect = CarPlayRenderRect(0, 0, canvasWidth, canvasHeight),
            rotationDegrees: Int = 0,
            generation: Long = 0,
        ): CarPlayRenderGeometry? {
            if (canvasWidth <= 0 || canvasHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0 ||
                viewWidth <= 0 || viewHeight <= 0 || sourceRect.width <= 0 || sourceRect.height <= 0 ||
                viewArea.width <= 0 || viewArea.height <= 0
            ) return null
            if (sourceRect.x < 0 || sourceRect.y < 0 ||
                sourceRect.x.toLong() + sourceRect.width > sourceWidth ||
                sourceRect.y.toLong() + sourceRect.height > sourceHeight ||
                viewArea.x < 0 || viewArea.y < 0 ||
                viewArea.x.toLong() + viewArea.width > canvasWidth ||
                viewArea.y.toLong() + viewArea.height > canvasHeight
            ) return null
            if (rotationDegrees % 90 != 0) return null
            return CarPlayRenderGeometry(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                sourceRect = sourceRect,
                viewArea = viewArea,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                rotationDegrees = rotationDegrees,
                generation = generation,
            )
        }
    }
}
