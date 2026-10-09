package com.shilapi.xcertplay.youtube

internal enum class SplitViewMode {
    LEGACY_RECONNECT,
    LOCAL_SCALE_NO_RESTART,
    DYNAMIC_VIEW_AREA_EXPERIMENTAL,
    ;

    companion object {
        fun fromBuild(debuggable: Boolean, value: String): SplitViewMode {
            if (!debuggable) return LEGACY_RECONNECT
            return when (value.trim().lowercase()) {
                "local" -> LOCAL_SCALE_NO_RESTART
                "dynamic-experimental" -> DYNAMIC_VIEW_AREA_EXPERIMENTAL
                else -> LEGACY_RECONNECT
            }
        }
    }
}

internal object SplitDisplaySizePolicy {
    fun negotiatedSize(
        localSplit: Boolean,
        observed: DisplaySize,
        fullCanvas: DisplaySize?,
        activeCanvas: DisplaySize?,
        restartReady: Boolean,
        restartPending: Boolean,
    ): DisplaySize? {
        if (!localSplit) return observed
        val canvas = fullCanvas ?: return observed
        if (canvas == activeCanvas && !restartReady && !restartPending) return null
        return canvas
    }
}
