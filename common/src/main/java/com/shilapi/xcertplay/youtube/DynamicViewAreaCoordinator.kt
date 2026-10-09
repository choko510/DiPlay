package com.shilapi.xcertplay.youtube

import com.shilapi.xcertplay.airplay.ViewAreaCommandWriteResult

internal enum class DynamicViewAreaMode {
    DISCONNECTED,
    LOCAL_ONLY,
    READY,
    SWITCHING,
    LOCAL_FALLBACK,
}

internal data class ViewAreaTransitionToken(
    val session: Any,
    val generation: Long,
    val targetIndex: Int,
    val deadlineElapsedMs: Long,
    val initiatedByPhone: Boolean,
)

internal data class DynamicViewAreaSnapshot(
    val mode: DynamicViewAreaMode,
    val declaredAreaCount: Int,
    val requestedIndex: Int,
    val committedIndex: Int,
    val generation: Long,
    val pending: Boolean,
    val failedForSession: Boolean,
    val retryCount: Int,
    val commandWriteSucceeded: Boolean,
    val failureReason: String?,
    val pendingToken: ViewAreaTransitionToken?,
)

internal sealed interface ViewAreaRequestResult {
    data class Outbound(val token: ViewAreaTransitionToken) : ViewAreaRequestResult
    data class PhoneInitiated(val token: ViewAreaTransitionToken) : ViewAreaRequestResult
    data class Existing(val token: ViewAreaTransitionToken) : ViewAreaRequestResult
    data object AlreadyCommitted : ViewAreaRequestResult
    data class Rejected(val reason: String) : ViewAreaRequestResult
}

internal enum class ViewAreaWriteDisposition {
    WRITTEN,
    RETRY_ONCE,
    FALLBACK,
    STALE,
    NOT_APPLICABLE,
}

internal class DynamicViewAreaCoordinator {
    private var mode = DynamicViewAreaMode.DISCONNECTED
    private var session: Any? = null
    private var declaredAreaCount = 0
    private var requestedIndex = 0
    private var committedIndex = 0
    private var generation = 0L
    private var pendingToken: ViewAreaTransitionToken? = null
    private var retryCount = 0
    private var writeSucceeded = false
    private var failedForSession = false
    private var failureReason: String? = null

    @Synchronized
    fun attachSession(session: Any?, areaCount: Int) {
        val normalizedCount = areaCount.coerceAtLeast(1)
        val sessionAreaCount = if (session == null) 0 else normalizedCount
        if (this.session === session && declaredAreaCount == sessionAreaCount) return
        generation++
        this.session = session
        declaredAreaCount = sessionAreaCount
        requestedIndex = 0
        committedIndex = 0
        pendingToken = null
        retryCount = 0
        writeSucceeded = false
        failedForSession = false
        failureReason = null
        mode = when {
            session == null -> DynamicViewAreaMode.DISCONNECTED
            normalizedCount < 2 -> DynamicViewAreaMode.LOCAL_ONLY
            else -> DynamicViewAreaMode.READY
        }
    }

    @Synchronized
    fun request(index: Int, nowElapsedMs: Long, timeoutMs: Long): ViewAreaRequestResult {
        if (session == null) return ViewAreaRequestResult.Rejected("no_airplay_session")
        if (declaredAreaCount < 2) return ViewAreaRequestResult.Rejected("dynamic_areas_not_declared")
        if (failedForSession) return ViewAreaRequestResult.Rejected("dynamic_disabled_for_session")
        if (index !in 0 until declaredAreaCount) return ViewAreaRequestResult.Rejected("index_out_of_range")
        pendingToken?.takeIf { it.targetIndex == index }?.let { return ViewAreaRequestResult.Existing(it) }
        if (pendingToken == null && committedIndex == index) return ViewAreaRequestResult.AlreadyCommitted
        return ViewAreaRequestResult.Outbound(beginTransition(index, nowElapsedMs, timeoutMs, initiatedByPhone = false))
    }

    @Synchronized
    fun requestFromPhone(
        session: Any,
        index: Int,
        canApplyToHostLayout: Boolean,
        nowElapsedMs: Long,
        timeoutMs: Long,
    ): ViewAreaRequestResult {
        if (this.session !== session) return ViewAreaRequestResult.Rejected("stale_airplay_session")
        if (declaredAreaCount < 2) return ViewAreaRequestResult.Rejected("dynamic_areas_not_declared")
        if (failedForSession) return ViewAreaRequestResult.Rejected("dynamic_disabled_for_session")
        if (index !in 0 until declaredAreaCount) return ViewAreaRequestResult.Rejected("index_out_of_range")
        if (!canApplyToHostLayout) return ViewAreaRequestResult.Rejected("host_layout_cannot_apply_request")
        pendingToken?.takeIf { it.targetIndex == index }?.let { return ViewAreaRequestResult.Existing(it) }
        if (pendingToken == null && committedIndex == index) return ViewAreaRequestResult.AlreadyCommitted
        return ViewAreaRequestResult.PhoneInitiated(
            beginTransition(index, nowElapsedMs, timeoutMs, initiatedByPhone = true),
        )
    }

    @Synchronized
    fun isCurrent(token: ViewAreaTransitionToken): Boolean =
        this.session === token.session && pendingToken == token && generation == token.generation

    @Synchronized
    fun onWriteResult(
        token: ViewAreaTransitionToken,
        result: ViewAreaCommandWriteResult,
        nowElapsedMs: Long,
    ): ViewAreaWriteDisposition {
        if (!isCurrent(token)) return ViewAreaWriteDisposition.STALE
        if (token.initiatedByPhone) return ViewAreaWriteDisposition.NOT_APPLICABLE
        when (result) {
            ViewAreaCommandWriteResult.WRITTEN -> {
                writeSucceeded = true
                return ViewAreaWriteDisposition.WRITTEN
            }
            ViewAreaCommandWriteResult.EVENT_CHANNEL_NOT_READY -> if (
                retryCount == 0 && nowElapsedMs < token.deadlineElapsedMs
            ) {
                retryCount = 1
                return ViewAreaWriteDisposition.RETRY_ONCE
            }
            else -> Unit
        }
        fallBack(result.name.lowercase())
        return ViewAreaWriteDisposition.FALLBACK
    }

    @Synchronized
    fun confirmObserved(
        token: ViewAreaTransitionToken,
        observedIndex: Int,
        geometryChangedSinceRequest: Boolean,
    ): Boolean {
        if (!isCurrent(token) || observedIndex != token.targetIndex || !geometryChangedSinceRequest) return false
        committedIndex = token.targetIndex
        requestedIndex = token.targetIndex
        pendingToken = null
        retryCount = 0
        writeSucceeded = false
        failureReason = null
        mode = DynamicViewAreaMode.READY
        return true
    }

    @Synchronized
    fun timeout(token: ViewAreaTransitionToken, nowElapsedMs: Long): Boolean {
        if (!isCurrent(token) || nowElapsedMs < token.deadlineElapsedMs) return false
        fallBack("transition_timeout")
        return true
    }

    @Synchronized
    fun snapshot(): DynamicViewAreaSnapshot = DynamicViewAreaSnapshot(
        mode = mode,
        declaredAreaCount = declaredAreaCount,
        requestedIndex = requestedIndex,
        committedIndex = committedIndex,
        generation = generation,
        pending = pendingToken != null,
        failedForSession = failedForSession,
        retryCount = retryCount,
        commandWriteSucceeded = writeSucceeded,
        failureReason = failureReason,
        pendingToken = pendingToken,
    )

    private fun beginTransition(
        index: Int,
        nowElapsedMs: Long,
        timeoutMs: Long,
        initiatedByPhone: Boolean,
    ): ViewAreaTransitionToken {
        require(timeoutMs > 0) { "ViewArea timeout must be positive" }
        check(generation < Long.MAX_VALUE) { "ViewArea generation exhausted" }
        generation++
        val currentSession = checkNotNull(session)
        val deadline = if (nowElapsedMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else nowElapsedMs + timeoutMs
        val token = ViewAreaTransitionToken(currentSession, generation, index, deadline, initiatedByPhone)
        requestedIndex = index
        pendingToken = token
        retryCount = 0
        writeSucceeded = false
        failureReason = null
        mode = DynamicViewAreaMode.SWITCHING
        return token
    }

    private fun fallBack(reason: String) {
        pendingToken = null
        requestedIndex = committedIndex
        retryCount = 0
        writeSucceeded = false
        failedForSession = true
        failureReason = reason
        mode = DynamicViewAreaMode.LOCAL_FALLBACK
    }
}
