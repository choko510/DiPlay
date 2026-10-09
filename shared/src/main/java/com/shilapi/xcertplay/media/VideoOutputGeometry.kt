package com.shilapi.xcertplay.media

data class VideoOutputGeometry(
    val codedWidth: Int,
    val codedHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val rotationDegrees: Int,
) {
    val visibleWidth: Int get() = cropRight + 1 - cropLeft
    val visibleHeight: Int get() = cropBottom + 1 - cropTop
    val displayWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleHeight else visibleWidth
    val displayHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleWidth else visibleHeight

    companion object {
        fun create(
            codedWidth: Int,
            codedHeight: Int,
            cropLeft: Int? = null,
            cropTop: Int? = null,
            cropRight: Int? = null,
            cropBottom: Int? = null,
            rotationDegrees: Int? = null,
        ): VideoOutputGeometry? {
            if (codedWidth <= 0 || codedHeight <= 0) return null
            val left = cropLeft ?: 0
            val top = cropTop ?: 0
            val right = cropRight ?: (codedWidth - 1)
            val bottom = cropBottom ?: (codedHeight - 1)
            val rotation = rotationDegrees ?: 0
            if (left < 0 || top < 0 || right < left || bottom < top ||
                right >= codedWidth || bottom >= codedHeight || rotation % 90 != 0
            ) return null
            return VideoOutputGeometry(codedWidth, codedHeight, left, top, right, bottom, (rotation % 360 + 360) % 360)
        }
    }
}

internal data class AdaptivePlaybackBounds(val maxWidth: Int, val maxHeight: Int)

internal fun boundedAdaptivePlaybackBounds(
    featureSupported: Boolean,
    canvasWidth: Int,
    canvasHeight: Int,
    codecMaxWidth: Int?,
    codecMaxHeight: Int?,
): AdaptivePlaybackBounds? {
    if (!featureSupported || canvasWidth <= 0 || canvasHeight <= 0) return null
    if ((codecMaxWidth ?: 0) < canvasWidth || (codecMaxHeight ?: 0) < canvasHeight) return null
    return AdaptivePlaybackBounds(canvasWidth, canvasHeight)
}

internal sealed interface VideoOutputSizeChange {
    data object Unchanged : VideoOutputSizeChange
    data object Adaptive : VideoOutputSizeChange
    data object OutOfBounds : VideoOutputSizeChange
    data class Reconfigure(val width: Int, val height: Int) : VideoOutputSizeChange
}

internal object VideoOutputSizePolicy {
    fun evaluate(
        geometry: VideoOutputGeometry,
        adaptivePlaybackEnabled: Boolean,
        configuredWidth: Int,
        configuredHeight: Int,
        maximumWidth: Int,
        maximumHeight: Int,
    ): VideoOutputSizeChange {
        if (adaptivePlaybackEnabled) return VideoOutputSizeChange.Adaptive
        if (geometry.codedWidth == configuredWidth && geometry.codedHeight == configuredHeight) {
            return VideoOutputSizeChange.Unchanged
        }
        if (geometry.codedWidth > maximumWidth || geometry.codedHeight > maximumHeight) {
            return VideoOutputSizeChange.OutOfBounds
        }
        return VideoOutputSizeChange.Reconfigure(geometry.codedWidth, geometry.codedHeight)
    }
}
