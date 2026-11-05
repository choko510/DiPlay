package com.shilapi.xcertplay.media

internal enum class VideoDecoderRecoveryState {
    RUNNING,
    DETACHING,
    RELEASE_BLOCKED,
    RETRYING_SAFE_RELEASE,
    RECOVERED,
    SESSION_RESTART_REQUIRED,
}

internal data class VideoDecoderRecoveryStatus(
    val state: VideoDecoderRecoveryState,
    val streamType: Int,
    val streamGeneration: Long,
    val decoderOwnerId: Long,
    val acknowledgmentPendingCount: Int,
    val releaseExceptionClass: String?,
    val lastFrameAgeMs: Long?,
    val blockedDurationMs: Long,
    val retryCount: Int,
) {
    fun toDiagnosticString(): String =
        "video recovery state=${state.name} streamType=$streamType generation=$streamGeneration " +
            "decoderOwnerId=$decoderOwnerId acknowledgmentPendingCount=$acknowledgmentPendingCount " +
            "releaseExceptionClass=${releaseExceptionClass ?: "none"} " +
            "lastFrameAgeMs=${lastFrameAgeMs ?: "unknown"} blockedDurationMs=$blockedDurationMs " +
            "retryCount=$retryCount"
}
