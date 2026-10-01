package com.shilapi.xcertplay.youtube

internal enum class YoutubeBrowserLifecycle {
    ACTIVE,
    SUSPENDED,
    DESTROYED,
}

internal object YoutubeBrowserSessionReusePolicy {
    fun canReuse(
        lifecycle: YoutubeBrowserLifecycle,
        currentContextId: String?,
        requestedContextId: String,
        primarySessionOpen: Boolean,
        crashed: Boolean,
    ): Boolean =
        lifecycle != YoutubeBrowserLifecycle.DESTROYED &&
            currentContextId == requestedContextId &&
            primarySessionOpen &&
            !crashed
}
