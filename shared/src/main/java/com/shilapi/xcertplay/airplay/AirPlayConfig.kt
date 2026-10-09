package com.shilapi.xcertplay.airplay

import java.util.Collections

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

data class CarPlayRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(x >= 0 && y >= 0) { "CarPlay rectangle origin must be non-negative" }
        require(width > 0 && height > 0) { "CarPlay rectangle dimensions must be positive" }
        require(x.toLong() + width <= Int.MAX_VALUE && y.toLong() + height <= Int.MAX_VALUE) {
            "CarPlay rectangle bounds overflow"
        }
    }
}

data class CarPlayViewArea(
    val viewport: CarPlayRect,
    val safeArea: CarPlayRect,
    val drawUIOutsideSafeArea: Boolean? = null,
) {
    init {
        require(safeArea.x >= viewport.x && safeArea.y >= viewport.y) {
            "CarPlay safe area must be inside its viewport"
        }
        require(safeArea.x.toLong() + safeArea.width <= viewport.x.toLong() + viewport.width &&
            safeArea.y.toLong() + safeArea.height <= viewport.y.toLong() + viewport.height
        ) { "CarPlay safe area must be inside its viewport" }
    }
}

class DynamicViewAreaConfig(
    areas: List<CarPlayViewArea>,
    val initialIndex: Int = 0,
) {
    val areas: List<CarPlayViewArea> = Collections.unmodifiableList(areas.toList())

    init {
        require(this.areas.size in 1..2) { "Only one or two CarPlay view areas are supported" }
        require(initialIndex in this.areas.indices) { "Initial CarPlay view area is out of range" }
        require(this.areas.map { it.viewport }.distinct().size == this.areas.size) {
            "CarPlay view area viewports must be unique"
        }
    }

    internal fun validateForCanvas(widthPixels: Int, heightPixels: Int) {
        this.areas.forEach { area ->
            val viewport = area.viewport
            val safeArea = area.safeArea
            require(viewport.x.toLong() + viewport.width <= widthPixels &&
                viewport.y.toLong() + viewport.height <= heightPixels
            ) { "CarPlay view area exceeds the display canvas" }
            require(isEvenAligned(viewport) && isEvenAligned(safeArea)) {
                "CarPlay view areas must use even codec-aligned coordinates"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DynamicViewAreaConfig && areas == other.areas && initialIndex == other.initialIndex

    override fun hashCode(): Int = 31 * areas.hashCode() + initialIndex

    override fun toString(): String = "DynamicViewAreaConfig(areas=$areas, initialIndex=$initialIndex)"

    private fun isEvenAligned(rect: CarPlayRect): Boolean =
        rect.x % 2 == 0 && rect.y % 2 == 0 && rect.width % 2 == 0 && rect.height % 2 == 0
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
    val dynamicViewAreas: DynamicViewAreaConfig? = null,
) {
    init {
        dynamicViewAreas?.validateForCanvas(widthPixels, heightPixels)
    }
}

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
