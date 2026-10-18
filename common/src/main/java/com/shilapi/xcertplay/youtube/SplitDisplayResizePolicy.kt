package com.shilapi.xcertplay.youtube

internal object SplitDisplayResizePolicy {
    fun keepsCurrentController(
        previous: DisplaySize?,
        requested: DisplaySize,
        controllerPresent: Boolean,
        localScaleEnabled: Boolean,
    ): Boolean = previous != null && previous != requested && controllerPresent && localScaleEnabled
}
