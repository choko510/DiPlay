package com.shilapi.xcertplay.airplay

import kotlin.math.floor

object DynamicViewAreaFactory {
    fun twoAreas(
        display: AirPlayDisplayConfig,
        splitFraction: Double = 0.55,
    ): DynamicViewAreaConfig? {
        if (!splitFraction.isFinite() || splitFraction <= 0.0 || splitFraction >= 1.0) return null
        if (display.widthPixels <= 0 || display.heightPixels <= 0) return null

        val viewInsets = display.viewArea ?: AirPlayInsets()
        val left = alignUpEven(viewInsets.left.coerceAtLeast(0).toLong())
        val top = alignUpEven(viewInsets.top.coerceAtLeast(0).toLong())
        val right = alignDownEven(display.widthPixels.toLong() - viewInsets.right.coerceAtLeast(0))
        val bottom = alignDownEven(display.heightPixels.toLong() - viewInsets.bottom.coerceAtLeast(0))
        if (right - left < 4 || bottom - top < 2 || right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null

        val fullViewport = CarPlayRect(
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt(),
        )
        val splitWidth = alignDownEven(floor(fullViewport.width * splitFraction).toLong())
        if (splitWidth < 2 || splitWidth >= fullViewport.width) return null
        val splitViewport = CarPlayRect(fullViewport.x, fullViewport.y, splitWidth.toInt(), fullViewport.height)
        val insets = display.safeArea ?: AirPlayInsets()
        val fullSafeArea = safeAreaWithin(
            fullViewport,
            display.widthPixels,
            display.heightPixels,
            insets,
        ) ?: return null
        val splitSafeArea = safeAreaWithin(
            splitViewport,
            display.widthPixels,
            display.heightPixels,
            insets,
        ) ?: return null
        return runCatching {
            DynamicViewAreaConfig(
                areas = listOf(
                    CarPlayViewArea(fullViewport, fullSafeArea, display.safeAreaDrawOutside),
                    CarPlayViewArea(splitViewport, splitSafeArea, display.safeAreaDrawOutside),
                ),
                initialIndex = 0,
            ).also { it.validateForCanvas(display.widthPixels, display.heightPixels) }
        }.getOrNull()
    }

    private fun safeAreaWithin(
        viewport: CarPlayRect,
        canvasWidth: Int,
        canvasHeight: Int,
        insets: AirPlayInsets,
    ): CarPlayRect? {
        val leftInset = insets.left.coerceAtLeast(0).toLong()
        val topInset = insets.top.coerceAtLeast(0).toLong()
        val rightInset = insets.right.coerceAtLeast(0).toLong()
        val bottomInset = insets.bottom.coerceAtLeast(0).toLong()
        val left = alignUpEven(maxOf(viewport.x.toLong(), leftInset))
        val top = alignUpEven(maxOf(viewport.y.toLong(), topInset))
        val right = alignDownEven(minOf(viewport.x.toLong() + viewport.width, canvasWidth.toLong() - rightInset))
        val bottom = alignDownEven(minOf(viewport.y.toLong() + viewport.height, canvasHeight.toLong() - bottomInset))
        if (right - left < 2 || bottom - top < 2 || right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null
        return CarPlayRect(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
    }

    private fun alignUpEven(value: Long): Long = if (value % 2L == 0L) value else value + 1L

    private fun alignDownEven(value: Long): Long = value and -2L
}
