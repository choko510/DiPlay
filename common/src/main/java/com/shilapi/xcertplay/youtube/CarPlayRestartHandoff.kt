package com.shilapi.xcertplay.youtube

internal class CarPlayRestartHandoff {
    private data class PendingRestart(
        val token: Long,
        val owner: Any?,
        val size: DisplaySize,
        val teardownComplete: Boolean,
        val layoutReady: Boolean,
        val claimed: Boolean,
    )

    private var nextToken = 0L
    private var pending: PendingRestart? = null

    @Synchronized
    fun begin(owner: Any, size: DisplaySize): Long? {
        if (pending != null) return null
        val token = ++nextToken
        pending = PendingRestart(
            token = token,
            owner = owner,
            size = size,
            teardownComplete = false,
            layoutReady = true,
            claimed = false,
        )
        return token
    }

    @Synchronized
    fun observeSize(owner: Any, size: DisplaySize) {
        val restart = pending ?: return
        if (restart.owner == null || restart.owner === owner) {
            pending = restart.copy(size = size, layoutReady = false)
        }
    }

    @Synchronized
    fun markLayoutReady(owner: Any, size: DisplaySize) {
        val restart = pending ?: return
        if (restart.owner == null || restart.owner === owner) {
            pending = restart.copy(size = size, layoutReady = true)
        }
    }

    @Synchronized
    fun completeTeardown(token: Long, size: DisplaySize) {
        val restart = pending ?: return
        if (restart.token == token) {
            pending = restart.copy(size = size, teardownComplete = true)
        }
    }

    @Synchronized
    fun releaseOwner(owner: Any) {
        val restart = pending ?: return
        if (restart.owner === owner) {
            pending = restart.copy(owner = null, layoutReady = false, claimed = false)
        }
    }

    @Synchronized
    fun claim(owner: Any): DisplaySize? {
        val restart = pending ?: return null
        if (!restart.teardownComplete || !restart.layoutReady) return null
        if (restart.owner != null && restart.owner !== owner) return null
        pending = restart.copy(owner = owner, claimed = true)
        return restart.size
    }

    @Synchronized
    fun isClaimedBy(owner: Any): Boolean = pending?.let { it.claimed && it.owner === owner } == true

    @Synchronized
    fun hasPending(): Boolean = pending != null

    @Synchronized
    fun clear() {
        pending = null
    }
}
