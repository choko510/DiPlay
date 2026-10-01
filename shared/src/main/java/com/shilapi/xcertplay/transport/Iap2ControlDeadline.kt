package com.shilapi.xcertplay.transport

/** A finite bring-up budget can end only after operational CarPlay proof; I/O polls stay bounded. */
internal class Iap2ControlDeadline(
    private val timeoutMillis: Long,
    private val maxPollMillis: Long = MAX_POLL_MILLIS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init {
        require(maxPollMillis > 0) { "maxPollMillis must be positive" }
    }

    private val unlimited = timeoutMillis == Long.MAX_VALUE
    private val startedNanos = clockNanos()
    private var authenticated = false
    private var established = false

    /** Completes protocol authentication; this only releases the NO_TIMEOUT handshake budget. */
    fun authenticated() { authenticated = true }

    /** Operational proof observed at expiry wins the timeout race; absent proof, the finite deadline remains. */
    fun establishIfOperational(proven: Boolean): Boolean {
        if (established || !proven) return false
        established = true
        return true
    }

    fun isEstablished(): Boolean = established
    fun remainingMillis(): Long {
        if (established || (unlimited && authenticated)) return maxPollMillis
        val budget = if (unlimited) HANDSHAKE_MILLIS else timeoutMillis
        val elapsedNanos = (clockNanos() - startedNanos).coerceAtLeast(0)
        val remainingNanos = budget * 1_000_000 - elapsedNanos
        if (remainingNanos <= 0) return 0
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(maxPollMillis)
    }
    companion object {
        const val HANDSHAKE_MILLIS = 60_000L
        const val MAX_POLL_MILLIS = 30_000L
    }
}
