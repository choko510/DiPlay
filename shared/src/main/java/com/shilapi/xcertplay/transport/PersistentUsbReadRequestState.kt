package com.shilapi.xcertplay.transport

/** Lifecycle bookkeeping for one reusable asynchronous USB bulk-IN request. */
internal class PersistentUsbReadRequestState<T : Any> {
    data class ClosePlan<T>(val request: T?, val cancelQueuedRequest: Boolean)

    private var request: T? = null
    private var queued = false
    private var closed = false
    private var failed = false

    fun requestOrCreate(create: () -> T): T {
        check(!closed) { "USB read request is closed" }
        request?.let { return it }
        return create().also { request = it }
    }

    fun needsQueue(): Boolean {
        check(!closed) { "USB read request is closed" }
        check(!failed) { "USB read request has failed" }
        return !queued
    }

    fun markQueued() {
        check(needsQueue()) { "USB read request is already queued" }
        queued = true
    }

    fun markQueueFailure() {
        failed = true
    }

    /** A wait timeout is not a request state transition: the same request remains pending. */
    fun onTimeout() {
        check(!closed && !failed && queued) { "USB read request is not pending" }
    }

    /** Returns false and preserves pending state when another request completed unexpectedly. */
    fun onCompletion(completed: T): Boolean {
        if (closed || failed || !queued || request !== completed) {
            failed = true
            return false
        }
        queued = false
        return true
    }

    fun markFailure() {
        failed = true
    }

    fun beginClose(): ClosePlan<T> {
        if (closed) return ClosePlan(null, false)
        closed = true
        return ClosePlan(request, queued)
    }

    fun takeForClose(): T? {
        val current = request
        request = null
        queued = false
        return current
    }
}
