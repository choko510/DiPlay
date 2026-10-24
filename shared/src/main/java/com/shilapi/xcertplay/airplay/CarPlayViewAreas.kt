package com.shilapi.xcertplay.airplay

import kotlin.math.roundToInt

data class CarPlayViewAreaDefinition(
    val viewArea: AirPlayInsets = AirPlayInsets(),
    val safeArea: AirPlayInsets = AirPlayInsets(),
    val safeAreaDrawOutside: Boolean = true,
)

data class CarPlayViewAreas(
    val areas: List<CarPlayViewAreaDefinition>,
    val initialIndex: Int = 0,
) {
    fun isValidFor(widthPixels: Int, heightPixels: Int): Boolean =
        widthPixels > 0 && heightPixels > 0 && areas.isNotEmpty() && initialIndex in areas.indices &&
            areas.all { area ->
                isValidInsets(area.viewArea, widthPixels, heightPixels) &&
                    isValidInsets(area.safeArea, widthPixels, heightPixels) &&
                    isSafeAreaWithinViewArea(area.viewArea, area.safeArea)
            }

    fun requireValidFor(widthPixels: Int, heightPixels: Int) {
        require(isValidFor(widthPixels, heightPixels)) {
            "View areas must have valid geometry, contained safe areas, and a valid initial index"
        }
    }

    fun isEncoderAlignedFor(widthPixels: Int, heightPixels: Int): Boolean =
        widthPixels > 0 && heightPixels > 0 && widthPixels % 2 == 0 && heightPixels % 2 == 0 &&
            areas.all { isEvenAlignedViewArea(it.viewArea) }

    fun requireEncoderAlignedFor(widthPixels: Int, heightPixels: Int) {
        require(isEncoderAlignedFor(widthPixels, heightPixels)) {
            "View areas must align their boundaries to even display pixels"
        }
    }

    fun scaled(
        sourceWidthPixels: Int,
        sourceHeightPixels: Int,
        targetWidthPixels: Int,
        targetHeightPixels: Int,
    ): CarPlayViewAreas {
        require(sourceWidthPixels > 0 && sourceHeightPixels > 0) { "Source display dimensions must be positive" }
        require(targetWidthPixels > 0 && targetHeightPixels > 0) { "Target display dimensions must be positive" }
        requireValidFor(sourceWidthPixels, sourceHeightPixels)
        requireEncoderAlignedFor(sourceWidthPixels, sourceHeightPixels)
        return copy(
            areas = areas.map { area ->
                val viewArea = scaleInsetsToEven(
                    area.viewArea,
                    sourceWidthPixels,
                    sourceHeightPixels,
                    targetWidthPixels,
                    targetHeightPixels,
                )
                area.copy(
                    viewArea = viewArea,
                    safeArea = scaleSafeArea(
                        area.safeArea,
                        viewArea,
                        sourceWidthPixels,
                        sourceHeightPixels,
                        targetWidthPixels,
                        targetHeightPixels,
                    ),
                )
            },
        ).also {
            it.requireValidFor(targetWidthPixels, targetHeightPixels)
            it.requireEncoderAlignedFor(targetWidthPixels, targetHeightPixels)
        }
    }

    companion object {
        fun single(display: AirPlayDisplayConfig): CarPlayViewAreas = CarPlayViewAreas(
            areas = listOf(
                CarPlayViewAreaDefinition(
                    viewArea = display.viewArea ?: AirPlayInsets(),
                    safeArea = display.safeArea ?: AirPlayInsets(),
                    safeAreaDrawOutside = display.safeAreaDrawOutside ?: true,
                ),
            ),
        )

        fun fullAndSplit(
            display: AirPlayDisplayConfig,
            splitFraction: Double = 0.55,
            initialIndex: Int = 0,
        ): CarPlayViewAreas {
            require(display.viewAreas == null) { "Build new areas from a display without an existing area model" }
            require(splitFraction.isFinite() && splitFraction in MIN_SPLIT_FRACTION..MAX_SPLIT_FRACTION) {
                "Split fraction must be between $MIN_SPLIT_FRACTION and $MAX_SPLIT_FRACTION"
            }
            val width = display.widthPixels
            val height = display.heightPixels
            require(width > 0 && height > 0) { "Display dimensions must be positive" }
            val fullViewArea = display.viewArea ?: AirPlayInsets()
            val fullSafeArea = display.safeArea ?: AirPlayInsets()
            require(isValidInsets(fullViewArea, width, height)) { "Full view area is outside the display" }
            require(isValidInsets(fullSafeArea, width, height)) { "Full safe area is outside the display" }
            require(width % 2 == 0 && height % 2 == 0 && isEvenAlignedViewArea(fullViewArea)) {
                "Full display view area must align to even pixel boundaries"
            }
            require(isSafeAreaWithinViewArea(fullViewArea, fullSafeArea)) {
                "Full safe area is outside the full view area"
            }

            val viewLeft = fullViewArea.left
            val viewRight = width - fullViewArea.right
            val visibleWidth = viewRight - viewLeft
            val splitWidth = ((visibleWidth * splitFraction / 2.0).roundToInt() * 2).coerceIn(2, visibleWidth)
            val splitRight = viewLeft + splitWidth
            val splitViewArea = fullViewArea.copy(right = width - splitRight)
            val fullSafeRight = width - fullSafeArea.right
            val splitSafeRight = minOf(fullSafeRight, splitRight)
            val splitSafeArea = fullSafeArea.copy(right = width - splitSafeRight)
            val drawOutside = display.safeAreaDrawOutside ?: true
            return CarPlayViewAreas(
                areas = listOf(
                    CarPlayViewAreaDefinition(fullViewArea, fullSafeArea, drawOutside),
                    CarPlayViewAreaDefinition(splitViewArea, splitSafeArea, drawOutside),
                ),
                initialIndex = initialIndex,
            ).also {
                it.requireValidFor(width, height)
                it.requireEncoderAlignedFor(width, height)
            }
        }
    }
}

private fun isValidInsets(insets: AirPlayInsets, widthPixels: Int, heightPixels: Int): Boolean =
    insets.left >= 0 && insets.right >= 0 && insets.top >= 0 && insets.bottom >= 0 &&
        insets.left.toLong() + insets.right < widthPixels &&
        insets.top.toLong() + insets.bottom < heightPixels

private fun isSafeAreaWithinViewArea(viewArea: AirPlayInsets, safeArea: AirPlayInsets): Boolean =
    safeArea.left >= viewArea.left && safeArea.right >= viewArea.right &&
        safeArea.top >= viewArea.top && safeArea.bottom >= viewArea.bottom

private fun isEvenAlignedViewArea(insets: AirPlayInsets): Boolean =
    insets.left % 2 == 0 && insets.right % 2 == 0 && insets.top % 2 == 0 && insets.bottom % 2 == 0

private fun scaleInsetsToEven(
    insets: AirPlayInsets,
    sourceWidthPixels: Int,
    sourceHeightPixels: Int,
    targetWidthPixels: Int,
    targetHeightPixels: Int,
): AirPlayInsets = AirPlayInsets(
    top = scaleInsetToEven(insets.top, sourceHeightPixels, targetHeightPixels),
    bottom = scaleInsetToEven(insets.bottom, sourceHeightPixels, targetHeightPixels),
    left = scaleInsetToEven(insets.left, sourceWidthPixels, targetWidthPixels),
    right = scaleInsetToEven(insets.right, sourceWidthPixels, targetWidthPixels),
)

private fun scaleSafeArea(
    safeArea: AirPlayInsets,
    viewArea: AirPlayInsets,
    sourceWidthPixels: Int,
    sourceHeightPixels: Int,
    targetWidthPixels: Int,
    targetHeightPixels: Int,
): AirPlayInsets {
    val scaled = scaleInsets(
        safeArea,
        sourceWidthPixels,
        sourceHeightPixels,
        targetWidthPixels,
        targetHeightPixels,
    )
    return AirPlayInsets(
        top = maxOf(scaled.top, viewArea.top),
        bottom = maxOf(scaled.bottom, viewArea.bottom),
        left = maxOf(scaled.left, viewArea.left),
        right = maxOf(scaled.right, viewArea.right),
    )
}

private fun scaleInsets(
    insets: AirPlayInsets,
    sourceWidthPixels: Int,
    sourceHeightPixels: Int,
    targetWidthPixels: Int,
    targetHeightPixels: Int,
): AirPlayInsets = AirPlayInsets(
    top = scaleInset(insets.top, sourceHeightPixels, targetHeightPixels),
    bottom = scaleInset(insets.bottom, sourceHeightPixels, targetHeightPixels),
    left = scaleInset(insets.left, sourceWidthPixels, targetWidthPixels),
    right = scaleInset(insets.right, sourceWidthPixels, targetWidthPixels),
)

private fun scaleInset(value: Int, sourceSize: Int, targetSize: Int): Int =
    (value * targetSize.toDouble() / sourceSize).roundToInt().coerceAtLeast(0)

private fun scaleInsetToEven(value: Int, sourceSize: Int, targetSize: Int): Int {
    val scaled = scaleInset(value, sourceSize, targetSize)
    return scaled + scaled % 2
}

private const val MIN_SPLIT_FRACTION = 0.5
private const val MAX_SPLIT_FRACTION = 0.6
