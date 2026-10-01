package com.shilapi.xcertplay.media

internal class AudioRendererLifecycle(
    private val closeJoinMillis: Long = 300L,
) {
    private enum class State { NEW, RUNNING, CLOSED }

    private val lock = Any()
    @Volatile private var state = State.NEW
    @Volatile private var startAttempted = false

    val isRunning: Boolean
        get() = state == State.RUNNING

    val hasStarted: Boolean
        get() = startAttempted

    fun start(worker: Thread, onStarting: () -> Unit = {}): Boolean {
        var failure: Throwable? = null
        val started = synchronized(lock) {
            if (state != State.NEW) {
                false
            } else {
                state = State.RUNNING
                try {
                    onStarting()
                    if (state != State.RUNNING) {
                        false
                    } else {
                        startAttempted = true
                        worker.start()
                        true
                    }
                } catch (error: Throwable) {
                    state = State.CLOSED
                    worker.interrupt()
                    failure = error
                    false
                }
            }
        }
        if (failure != null) {
            joinWorker(worker)
            throw requireNotNull(failure)
        }
        return started
    }

    fun close(worker: Thread, beforeInterrupt: () -> Unit = {}) {
        val shouldJoin = synchronized(lock) {
            if (state == State.CLOSED) {
                false
            } else {
                state = State.CLOSED
                beforeInterrupt()
                worker.interrupt()
                worker.state != Thread.State.NEW
            }
        }
        if (shouldJoin) joinWorker(worker)
    }

    fun stopped() {
        synchronized(lock) { state = State.CLOSED }
    }

    private fun joinWorker(worker: Thread) {
        if (worker === Thread.currentThread() || worker.state == Thread.State.NEW) return
        try {
            worker.join(closeJoinMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
