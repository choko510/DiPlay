package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

data class CarPlayViewAreaRect(
    val widthPixels: Int,
    val heightPixels: Int,
    val originXPixels: Int = 0,
    val originYPixels: Int = 0,
) {
    val rightPixels: Long get() = originXPixels.toLong() + widthPixels
    val bottomPixels: Long get() = originYPixels.toLong() + heightPixels

    fun isInside(widthPixels: Int, heightPixels: Int): Boolean =
        this.widthPixels > 0 && this.heightPixels > 0 &&
            originXPixels >= 0 && originYPixels >= 0 &&
            rightPixels <= widthPixels && bottomPixels <= heightPixels

    fun contains(other: CarPlayViewAreaRect): Boolean =
        other.originXPixels >= originXPixels && other.originYPixels >= originYPixels &&
            other.rightPixels <= rightPixels && other.bottomPixels <= bottomPixels
}

data class CarPlayViewArea(
    val viewArea: CarPlayViewAreaRect,
    val safeArea: CarPlayViewAreaRect,
    val drawUiOutsideSafeArea: Boolean,
)

data class CarPlayViewAreas(
    val areas: List<CarPlayViewArea>,
    val initialIndex: Int = 0,
) {
    fun isValidFor(widthPixels: Int, heightPixels: Int): Boolean =
        areas.isNotEmpty() && initialIndex in areas.indices && areas.all { area ->
            area.viewArea.isInside(widthPixels, heightPixels) &&
                area.safeArea.isInside(widthPixels, heightPixels) &&
                area.viewArea.contains(area.safeArea)
        }
}

object CarPlayViewAreaPlanner {
    const val DEFAULT_SPLIT_FRACTION = 0.55f
    private const val MIN_SPLIT_FRACTION = 0.50f
    private const val MAX_SPLIT_FRACTION = 0.60f

    fun fullAndSplit(
        display: AirPlayDisplayConfig,
        splitFraction: Float = DEFAULT_SPLIT_FRACTION,
    ): CarPlayViewAreas? {
        if (display.widthPixels <= 0 || display.heightPixels <= 0) return null
        val normalizedFraction = if (splitFraction.isNaN() || splitFraction.isInfinite()) {
            DEFAULT_SPLIT_FRACTION
        } else {
            splitFraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
        }
        val baseView = insetRect(display.widthPixels, display.heightPixels, display.viewArea) ?: return null
        val safeCandidate = insetRect(display.widthPixels, display.heightPixels, display.safeArea) ?: return null
        if (!baseView.contains(safeCandidate)) return null
        val splitWidth = ((baseView.widthPixels * normalizedFraction).toInt() / 2) * 2
        if (splitWidth <= 0 || splitWidth > baseView.widthPixels) return null
        val splitView = baseView.copy(widthPixels = splitWidth)
        val full = CarPlayViewArea(baseView, safeCandidate, display.safeAreaDrawOutside ?: true)
        val splitSafe = intersect(splitView, safeCandidate) ?: return null
        val split = CarPlayViewArea(splitView, splitSafe, display.safeAreaDrawOutside ?: true)
        return CarPlayViewAreas(listOf(full, split)).takeIf {
            it.isValidFor(display.widthPixels, display.heightPixels)
        }
    }

    private fun insetRect(widthPixels: Int, heightPixels: Int, insets: AirPlayInsets?): CarPlayViewAreaRect? {
        val applied = insets ?: AirPlayInsets()
        if (applied.left < 0 || applied.top < 0 || applied.right < 0 || applied.bottom < 0) return null
        val width = widthPixels.toLong() - applied.left - applied.right
        val height = heightPixels.toLong() - applied.top - applied.bottom
        if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return CarPlayViewAreaRect(width.toInt(), height.toInt(), applied.left, applied.top)
    }

    private fun intersect(first: CarPlayViewAreaRect, second: CarPlayViewAreaRect): CarPlayViewAreaRect? {
        val left = maxOf(first.originXPixels, second.originXPixels)
        val top = maxOf(first.originYPixels, second.originYPixels)
        val right = minOf(first.rightPixels, second.rightPixels)
        val bottom = minOf(first.bottomPixels, second.bottomPixels)
        if (right <= left || bottom <= top) return null
        return CarPlayViewAreaRect(
            widthPixels = (right - left).toInt(),
            heightPixels = (bottom - top).toInt(),
            originXPixels = left,
            originYPixels = top,
        )
    }
}

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    val advertisedViewAreas: CarPlayViewAreas? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
    val wirelessAudio: Boolean = false,
)
