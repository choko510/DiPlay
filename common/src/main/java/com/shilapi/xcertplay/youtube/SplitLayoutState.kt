package com.shilapi.xcertplay.youtube

internal enum class HostLayoutMode {
    CARPLAY_FULL,
    CARPLAY_YOUTUBE_SPLIT,
}

internal data class HostLayoutState(
    val mode: HostLayoutMode = HostLayoutMode.CARPLAY_FULL,
    val carPlayFraction: Float = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
) {
    fun enterYoutubeSplit(): HostLayoutState = copy(mode = HostLayoutMode.CARPLAY_YOUTUBE_SPLIT)

    fun returnToCarPlay(): HostLayoutState = copy(mode = HostLayoutMode.CARPLAY_FULL)
}

internal object SplitLayoutConfig {
    const val DEFAULT_CARPLAY_FRACTION = 0.55f
    private const val MIN_CARPLAY_FRACTION = 0.50f
    private const val MAX_CARPLAY_FRACTION = 0.60f

    fun normalizeCarPlayFraction(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) {
            DEFAULT_CARPLAY_FRACTION
        } else {
            value.coerceIn(MIN_CARPLAY_FRACTION, MAX_CARPLAY_FRACTION)
        }
}
