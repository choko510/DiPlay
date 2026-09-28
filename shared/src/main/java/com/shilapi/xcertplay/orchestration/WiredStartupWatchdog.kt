package com.shilapi.xcertplay.orchestration

/** Guards only the interval from wired CarPlayStartSession until AirPlay starts. */
internal class WiredStartupWatchdog {
    class Token internal constructor(val generation: Int, val attempt: Int)

    private var generation = 0
    private var armed: Token? = null
    private var expiredAttempt: Int? = null

    @Synchronized
    fun arm(attempt: Int): Token? {
        if (expiredAttempt == attempt) return null
        armed?.let { if (it.attempt == attempt) return null }
        return Token(++generation, attempt).also { armed = it }
    }

    @Synchronized
    fun cancel(attempt: Int? = null) {
        if (attempt == null || armed?.attempt == attempt || expiredAttempt == attempt) {
            generation++
            armed = null
            expiredAttempt = null
        }
    }

    @Synchronized
    fun expire(token: Token): Int? {
        if (armed !== token) return null
        armed = null
        expiredAttempt = token.attempt
        return token.attempt
    }

    @Synchronized
    fun hasExpired(attempt: Int): Boolean = expiredAttempt == attempt

    @Synchronized
    fun clear(attempt: Int) {
        if (armed?.attempt == attempt || expiredAttempt == attempt) cancel(attempt)
    }
}

internal class WiredStartupRetryPolicy {
    private var retryConsumed = false

    @Synchronized
    fun claimAutomaticRetry(): Boolean {
        if (retryConsumed) return false
        retryConsumed = true
        return true
    }

    @Synchronized
    fun reset() {
        retryConsumed = false
    }
}
