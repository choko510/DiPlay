package com.shilapi.xcertplay.media

/** Serializes route publication with track release so an old listener cannot restore a stale route. */
internal class PlaybackRouteListenerGate<T : Any> {
    private var generation = 0L
    private var owner: T? = null

    @Synchronized
    fun bind(owner: T): Long {
        this.owner = owner
        generation += 1
        return generation
    }

    @Synchronized
    fun publishIfCurrent(owner: T, generation: Long, publish: () -> Unit): Boolean {
        if (this.owner !== owner || this.generation != generation) return false
        publish()
        return true
    }

    @Synchronized
    fun release(owner: T, clear: () -> Unit): Boolean {
        if (this.owner !== owner) return false
        this.owner = null
        generation += 1
        clear()
        return true
    }
}
