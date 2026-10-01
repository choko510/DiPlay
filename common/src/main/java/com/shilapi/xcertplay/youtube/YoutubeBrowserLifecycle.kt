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

internal object YoutubeBrowserMemoryPolicy {
    private const val API_LEVEL_WITHOUT_RUNNING_TRIM_LEVELS = 34
    private const val RUNNING_CRITICAL_LEVEL = 15
    private const val UI_HIDDEN_LEVEL = 20

    fun shouldEvictSuspendedSession(
        sdkInt: Int,
        trimLevel: Int,
        splitMode: Boolean,
        browserSuspended: Boolean,
    ): Boolean =
        sdkInt < API_LEVEL_WITHOUT_RUNNING_TRIM_LEVELS &&
            trimLevel >= RUNNING_CRITICAL_LEVEL &&
            trimLevel < UI_HIDDEN_LEVEL &&
            !splitMode &&
            browserSuspended
}
