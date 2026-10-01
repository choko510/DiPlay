package com.shilapi.xcertplay.youtube

internal enum class YoutubeBrowserLifecycle {
    ACTIVE,
    SUSPENDED,
    DESTROYED,
}

internal enum class YoutubeBrowserOpenDecision {
    PRESERVE_ACTIVE_SESSION,
    REACTIVATE_SUSPENDED_SESSION,
    CREATE_SESSION,
    IGNORE_DESTROYED_CONTROLLER,
}

internal object YoutubeBrowserSessionReusePolicy {
    fun decide(
        lifecycle: YoutubeBrowserLifecycle,
        currentContextId: String?,
        requestedContextId: String,
        primarySessionOpen: Boolean,
        crashed: Boolean,
    ): YoutubeBrowserOpenDecision {
        if (lifecycle == YoutubeBrowserLifecycle.DESTROYED) {
            return YoutubeBrowserOpenDecision.IGNORE_DESTROYED_CONTROLLER
        }
        if (currentContextId != requestedContextId || !primarySessionOpen || crashed) {
            return YoutubeBrowserOpenDecision.CREATE_SESSION
        }
        return when (lifecycle) {
            YoutubeBrowserLifecycle.ACTIVE -> YoutubeBrowserOpenDecision.PRESERVE_ACTIVE_SESSION
            YoutubeBrowserLifecycle.SUSPENDED -> YoutubeBrowserOpenDecision.REACTIVATE_SUSPENDED_SESSION
            YoutubeBrowserLifecycle.DESTROYED -> YoutubeBrowserOpenDecision.IGNORE_DESTROYED_CONTROLLER
        }
    }
}
