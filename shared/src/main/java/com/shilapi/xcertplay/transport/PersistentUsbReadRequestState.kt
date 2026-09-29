package com.shilapi.xcertplay.transport

/** Lifecycle bookkeeping for one reusable asynchronous USB bulk-IN request. */
internal class PersistentUsbReadRequestState<T : Any> {
    enum class State {
        NEW,
        QUEUED,
        COMPLETED,
        FAILED,
        CLOSING,
        CLOSED,
    }

    data class ClosePlan<T>(val request: T?, val cancelQueuedRequest: Boolean)

    private var request: T? = null
    private var currentState = State.NEW
    private var queuedRequestNeedsCancel = false

    @Synchronized
    fun state(): State = currentState

    @Synchronized
    fun isClosing(): Boolean = currentState == State.CLOSING || currentState == State.CLOSED

    @Synchronized
    fun requestOrCreate(create: () -> T): T {
        check(currentState != State.CLOSING && currentState != State.CLOSED) { "USB read request is closed" }
        check(currentState != State.FAILED) { "USB read request has failed" }
        request?.let { return it }
        return create().also { request = it }
    }

    @Synchronized
    fun needsQueue(): Boolean {
        check(currentState != State.CLOSING && currentState != State.CLOSED) { "USB read request is closed" }
        check(currentState != State.FAILED) { "USB read request has failed" }
        return currentState == State.NEW || currentState == State.COMPLETED
    }

    @Synchronized
    fun markQueued() {
        check(needsQueue()) { "USB read request is already queued" }
        currentState = State.QUEUED
        queuedRequestNeedsCancel = false
    }

    @Synchronized
    fun markQueueFailure() {
        if (currentState != State.CLOSING && currentState != State.CLOSED) {
            queuedRequestNeedsCancel = false
            currentState = State.FAILED
        }
    }

    /** A wait timeout is not a request state transition: the same request remains pending. */
    @Synchronized
    fun onTimeout() {
        check(currentState == State.QUEUED) { "USB read request is not pending" }
    }

    /** A null requestWait result means transport failure, unless close already began. */
    @Synchronized
    fun onNullResult(): Boolean {
        if (currentState == State.CLOSING || currentState == State.CLOSED) return false
        queuedRequestNeedsCancel = currentState == State.QUEUED
        currentState = State.FAILED
        return true
    }

    /** A mismatched completion fails the request while preserving its cancellation for close(). */
    @Synchronized
    fun onCompletion(completed: T): Boolean {
        if (currentState != State.QUEUED || request !== completed) {
            markFailure()
            return false
        }
        currentState = State.COMPLETED
        return true
    }

    @Synchronized
    fun markFailure() {
        if (currentState != State.CLOSING && currentState != State.CLOSED) {
            queuedRequestNeedsCancel = currentState == State.QUEUED
            currentState = State.FAILED
        }
    }

    @Synchronized
    fun beginClose(): ClosePlan<T> {
        if (currentState == State.CLOSING || currentState == State.CLOSED) return ClosePlan(null, false)
        val cancelQueuedRequest = currentState == State.QUEUED ||
            (currentState == State.FAILED && queuedRequestNeedsCancel)
        currentState = State.CLOSING
        return ClosePlan(request, cancelQueuedRequest)
    }

    @Synchronized
    fun takeForClose(): T? {
        if (currentState == State.CLOSED) return null
        val current = request
        request = null
        queuedRequestNeedsCancel = false
        currentState = State.CLOSED
        return current
    }
}
