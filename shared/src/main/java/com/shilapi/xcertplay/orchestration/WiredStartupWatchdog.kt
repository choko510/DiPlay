package com.shilapi.xcertplay.orchestration

/** Guards the interval from wired CarPlayStartSession until the screen stream opens. */
internal class WiredStartupWatchdog {
    companion object {
        const val SOFT_TIMEOUT_MILLIS = 8_000L
        const val HARD_TIMEOUT_MILLIS = 15_000L
    }

    class Token internal constructor(val generation: Int, val attempt: Int)

    private var generation = 0
    private var armed: Token? = null
    private var expiredAttempt: Int? = null
    private var slowReported = false

    @Synchronized
    fun arm(attempt: Int): Token? {
        if (expiredAttempt == attempt) return null
        armed?.let { if (it.attempt == attempt) return null }
        return Token(++generation, attempt).also {
            armed = it
            slowReported = false
        }
    }

    @Synchronized
    fun markSlow(token: Token): Boolean {
        if (armed !== token || slowReported) return false
        slowReported = true
        return true
    }

    @Synchronized
    fun cancel(attempt: Int? = null) {
        if (attempt == null || armed?.attempt == attempt || expiredAttempt == attempt) {
            generation++
            armed = null
            expiredAttempt = null
            slowReported = false
        }
    }

    @Synchronized
    fun expire(token: Token): Int? {
        if (armed !== token) return null
        armed = null
        expiredAttempt = token.attempt
        slowReported = false
        return token.attempt
    }

    @Synchronized
    fun hasExpired(attempt: Int): Boolean = expiredAttempt == attempt

    @Synchronized
    fun clear(attempt: Int) {
        if (armed?.attempt == attempt || expiredAttempt == attempt) cancel(attempt)
    }
}

internal enum class WiredRecoveryDecision {
    FAST_CLEAN_RETRY,
    DEEP_RECOVERY_CANDIDATE,
    USER_OR_PHYSICAL_DISCONNECT,
    SUPPRESSED,
}

internal class WiredStartupRetryPolicy {
    private var failureStreak = 0

    @Synchronized
    fun onFailure(devicePresent: Boolean, closed: Boolean, manualReconnect: Boolean): WiredRecoveryDecision {
        if (closed) return WiredRecoveryDecision.SUPPRESSED
        if (manualReconnect) {
            failureStreak = 0
            return WiredRecoveryDecision.SUPPRESSED
        }
        if (!devicePresent) {
            failureStreak = 0
            return WiredRecoveryDecision.USER_OR_PHYSICAL_DISCONNECT
        }
        return if (failureStreak == 0) {
            failureStreak = 1
            WiredRecoveryDecision.FAST_CLEAN_RETRY
        } else {
            WiredRecoveryDecision.DEEP_RECOVERY_CANDIDATE
        }
    }

    @Synchronized
    fun reset() {
        failureStreak = 0
    }
}

internal class WiredDataPathGeneration {
    private var current = 0

    @Synchronized
    fun begin(): Int = ++current

    @Synchronized
    fun invalidate(): Int = ++current

    @Synchronized
    fun isCurrent(generation: Int): Boolean = current == generation
}
