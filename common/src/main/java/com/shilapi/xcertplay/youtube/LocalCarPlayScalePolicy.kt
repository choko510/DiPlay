package com.shilapi.xcertplay.youtube

import kotlin.math.roundToInt

internal data class LocalCarPlayContentBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && y >= top && x < left + width && y < top + height
}

internal object LocalCarPlayScalePolicy {
    fun shouldKeepSession(
        hasController: Boolean,
        ownsController: Boolean,
        hasUsableSurface: Boolean,
        hasRemoteSize: Boolean,
        handshakeReset: Boolean,
        restartPending: Boolean,
        shuttingDown: Boolean,
    ): Boolean = hasController && ownsController && hasUsableSurface && hasRemoteSize &&
        !handshakeReset && !restartPending && !shuttingDown

    fun fitBounds(remote: DisplaySize, viewport: DisplaySize): LocalCarPlayContentBounds? {
        if (remote.width <= 0 || remote.height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
            return null
        }
        val scale = minOf(
            viewport.width.toDouble() / remote.width,
            viewport.height.toDouble() / remote.height,
        )
        val width = (remote.width * scale).roundToInt().coerceIn(1, viewport.width)
        val height = (remote.height * scale).roundToInt().coerceIn(1, viewport.height)
        return LocalCarPlayContentBounds(
            left = (viewport.width - width) / 2,
            top = (viewport.height - height) / 2,
            width = width,
            height = height,
        )
    }
}
