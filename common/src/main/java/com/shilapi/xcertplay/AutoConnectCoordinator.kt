// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.os.SystemClock
import android.util.Log

/** Why the app is asking the auto-connect path to run. */
internal enum class AutoConnectSource {
    APP_LAUNCH,
    ACTIVITY_RECREATED,
    NEW_INTENT,
    BOOT,
    SETTINGS,
}

internal enum class AutoConnectTransport {
    WIRELESS,
    WIRED,
}

internal enum class AutoConnectSessionState {
    NONE,
    ACTIVE,
    TRANSITIONING,
}

internal enum class AutoConnectState {
    IDLE,
    STARTING,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER,
    RETRY_SCHEDULED,
    BLOCKED,
    ACTIVE,
    SUPPRESSED,
    EXHAUSTED,
}

internal enum class AutoConnectSkipReason {
    DISABLED,
    SETUP_FAILED,
    USER_DISCONNECTED,
    MANUAL_CONNECT_IN_PROGRESS,
    SESSION_EXISTS,
    SESSION_TRANSITIONING,
    REQUEST_IN_FLIGHT,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER,
    RETRY_SCHEDULED,
    RETRY_NOT_DUE,
    RETRY_EXHAUSTED,
    PERMISSION_DENIED,
    USB_AUTO_LAUNCH_ACTIVE,
    NOT_APPLICABLE,
}

internal enum class AutoConnectFailure {
    HOST_ACTIVITY_UNAVAILABLE,
    PERMISSION_DENIED,
    INITIALIZATION_FAILED,
}

internal data class AutoConnectRequest(
    val key: String,
    val source: AutoConnectSource,
    val transport: AutoConnectTransport,
)

internal data class AutoConnectConditions(
    val enabled: Boolean,
    val setupReady: Boolean,
    val session: AutoConnectSessionState,
    val usbAutoLaunchActive: Boolean = false,
)

internal sealed interface AutoConnectOutcome {
    data class Start(
        val generation: Long,
        val attempt: Int,
        val maxAttempts: Int,
        val request: AutoConnectRequest,
    ) : AutoConnectOutcome

    data class Skip(
        val reason: AutoConnectSkipReason,
        val retryAtMillis: Long? = null,
    ) : AutoConnectOutcome

    data class RetryScheduled(
        val generation: Long,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMillis: Long,
    ) : AutoConnectOutcome

    data class Exhausted(val generation: Long) : AutoConnectOutcome

    data class Blocked(val reason: AutoConnectSkipReason) : AutoConnectOutcome

    data object ContinueAfterPermission : AutoConnectOutcome

    data object StaleCallbackIgnored : AutoConnectOutcome
}

internal object AutoConnectLog {
    private const val TAG = "diplay-auto-connect"

    fun event(message: String) {
        Log.i(TAG, "AUTO_CONNECT $message")
    }
}

/**
 * Process-scoped admission control for the user-visible "connect when DiPlay opens" setting.
 *
 * It does not connect by itself and it does not touch Bluetooth or USB. It only remembers the one
 * logical request that is allowed to run, suppresses duplicate Activity resumes/recreations, and
 * returns a bounded retry decision when the host Activity could not be launched.
 */
internal class AutoConnectCoordinator(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val logger: (String) -> Unit = AutoConnectLog::event,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelaysMillis: LongArray = DEFAULT_RETRY_DELAYS_MILLIS,
    private val staleRequestMillis: Long = DEFAULT_STALE_REQUEST_MILLIS,
) {
    private data class ActiveRequest(
        val request: AutoConnectRequest,
        val startedAtMillis: Long,
    )

    private var state = AutoConnectState.IDLE
    private var active: ActiveRequest? = null
    private var attempts = 0
    private var retryAtMillis = 0L
    private var generation = 0L
    private var userDisconnected = false
    private var blockedReason: AutoConnectSkipReason? = null
    private var manualConnectAtMillis: Long? = null
    private var observedActive = false
    private var observedHadActiveSession = false
    private var autoStarted = false
    private var lastStartedKey: String? = null
    private var terminalKey: String? = null
    private var hostLaunchDispatched = false

    @Synchronized
    fun currentState(): AutoConnectState = state

    @Synchronized
    fun activeGeneration(): Long? = active?.let { generation }

    @Synchronized
    fun onExplicitConnect() {
        val now = clock()
        generation++
        autoStarted = false
        userDisconnected = false
        blockedReason = null
        terminalKey = null
        active = null
        attempts = 0
        retryAtMillis = 0L
        manualConnectAtMillis = now
        hostLaunchDispatched = false
        state = AutoConnectState.IDLE
        log("event=EXPLICIT_CONNECT state=IDLE")
    }

    @Synchronized
    fun onUserDisconnect() {
        generation++
        autoStarted = false
        userDisconnected = true
        blockedReason = null
        terminalKey = null
        active = null
        attempts = 0
        retryAtMillis = 0L
        manualConnectAtMillis = null
        hostLaunchDispatched = false
        state = AutoConnectState.SUPPRESSED
        log("event=USER_DISCONNECTED decision=SKIP reason=USER_DISCONNECTED")
    }

    @Synchronized
    fun onAutoConnectDisabled() {
        blockedReason = null
        terminalKey = null
        active = null
        attempts = 0
        retryAtMillis = 0L
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
        log("event=AUTO_CONNECT_DISABLED state=${state.label}")
    }

    /** A manual/USB path owns the next session; it must not inherit auto-session recovery policy. */
    @Synchronized
    fun onExternalSessionStart() {
        autoStarted = false
        active = null
        attempts = 0
        retryAtMillis = 0L
        terminalKey = null
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
        log("event=EXTERNAL_SESSION_START state=${state.label}")
    }

    @Synchronized
    fun onHostPermissionDenied() {
        if (!autoStarted) return
        blockedReason = AutoConnectSkipReason.PERMISSION_DENIED
        terminalKey = active?.request?.key ?: lastStartedKey
        active = null
        attempts = 0
        retryAtMillis = 0L
        hostLaunchDispatched = false
        state = AutoConnectState.BLOCKED
        log("event=HOST_PERMISSION_DENIED decision=BLOCKED reason=PERMISSION_DENIED")
    }

    @Synchronized
    fun onSessionActive() {
        observedActive = true
        observedHadActiveSession = true
        active = null
        attempts = 0
        retryAtMillis = 0L
        manualConnectAtMillis = null
        blockedReason = null
        hostLaunchDispatched = false
        if (!userDisconnected) state = AutoConnectState.ACTIVE
        log("event=SESSION_ACTIVE state=${state.label}")
    }

    @Synchronized
    fun onSessionStopped() {
        observedActive = false
        observedHadActiveSession = false
        autoStarted = false
        active = null
        attempts = 0
        retryAtMillis = 0L
        manualConnectAtMillis = null
        hostLaunchDispatched = false
        if (!userDisconnected) {
            state = if (blockedReason == null) AutoConnectState.IDLE else AutoConnectState.BLOCKED
        }
        log("event=SESSION_STOPPED state=${state.label}")
    }

    @Synchronized
    fun onHostCreated(generation: Long) {
        if (generation != this.generation || active == null) {
            log("decision=STALE_IGNORED event=HOST_CREATED generation=$generation")
            return
        }
        log("event=HOST_CREATED generation=$generation state=${state.label}")
    }

    @Synchronized
    fun onHostSessionActive(generation: Long) {
        if (generation != this.generation) {
            log("decision=STALE_IGNORED event=HOST_SESSION_ACTIVE generation=$generation")
            return
        }
        log("event=HOST_SESSION_ACTIVE generation=$generation")
        onSessionActive()
    }

    @Synchronized
    fun onHostSessionEnded(generation: Long) {
        if (generation != this.generation) {
            log("decision=STALE_IGNORED event=HOST_SESSION_ENDED generation=$generation")
            return
        }
        observedActive = false
        observedHadActiveSession = false
        active = null
        attempts = 0
        retryAtMillis = 0L
        manualConnectAtMillis = null
        hostLaunchDispatched = false
        if (!userDisconnected) {
            state = if (blockedReason == null) AutoConnectState.IDLE else AutoConnectState.BLOCKED
        }
        log("event=HOST_SESSION_ENDED generation=$generation state=${state.label}")
    }

    @Synchronized
    fun onHostStartFailed(generation: Long, failure: AutoConnectFailure) {
        if (generation != this.generation) {
            log("decision=STALE_IGNORED event=HOST_START_FAILED generation=$generation")
            return
        }
        log("event=HOST_START_FAILED generation=$generation error=${failure.label}")
        onHostLaunchFailed(generation, failure)
    }

    @Synchronized
    fun observeSession(activeSession: Boolean, sessionPresent: Boolean) {
        val wasActive = observedActive
        observedActive = activeSession
        when {
            activeSession && !wasActive -> onSessionActive()
            !sessionPresent && observedHadActiveSession -> onSessionStopped()
        }
    }

    @Synchronized
    fun postponeForUser(generation: Long): Boolean {
        if (generation != this.generation || active == null) return false
        state = AutoConnectState.WAITING_FOR_USER
        log("event=WAITING_FOR_USER generation=$generation")
        return true
    }

    @Synchronized
    fun onUserSelection(generation: Long): Boolean {
        if (generation != this.generation || active == null) return false
        active = active?.copy(startedAtMillis = clock())
        state = AutoConnectState.STARTING
        log("decision=CONTINUE reason=USER_SELECTED generation=$generation")
        return true
    }

    @Synchronized
    fun onUserSelectionCancelled(generation: Long) {
        if (generation != this.generation || active == null) return
        autoStarted = false
        active = null
        attempts = 0
        retryAtMillis = 0L
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
        log("decision=SKIP reason=USER_CANCELLED generation=$generation")
    }

    /**
     * Called when the Activity is being destroyed for a reason other than a configuration change.
     * A retry that can no longer be dispatched must not block the next explicit app launch.
     */
    @Synchronized
    fun onForegroundGone() {
        if (state != AutoConnectState.RETRY_SCHEDULED &&
            state != AutoConnectState.EXHAUSTED &&
            state != AutoConnectState.BLOCKED &&
            state != AutoConnectState.WAITING_FOR_USER
        ) return
        active = null
        attempts = 0
        retryAtMillis = 0L
        blockedReason = null
        terminalKey = null
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
        log("event=REQUEST_ABANDONED decision=SKIP reason=FOREGROUND_GONE")
    }

    @Synchronized
    fun noteNotRequested(source: AutoConnectSource, transport: AutoConnectTransport?) {
        log("event=REQUESTED source=${source.label}")
        log(
            "decision=SKIP reason=${AutoConnectSkipReason.NOT_APPLICABLE.label} " +
                "source=${source.label}${transport?.let { " transport=${it.label}" }.orEmpty()}",
        )
    }

    @Synchronized
    fun noteHandledExternally(source: AutoConnectSource) {
        autoStarted = false
        active = null
        attempts = 0
        retryAtMillis = 0L
        terminalKey = null
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
        log("decision=SKIP reason=USB_AUTO_LAUNCH_ACTIVE source=${source.label}")
    }

    @Synchronized
    fun request(
        request: AutoConnectRequest,
        conditions: AutoConnectConditions,
        nowMillis: Long = clock(),
    ): AutoConnectOutcome {
        log("event=REQUESTED source=${request.source.label}")
        log(
            "enabled=${conditions.enabled} transport=${request.transport.label} " +
                "state=${state.label} setupReady=${conditions.setupReady} session=${conditions.session.label}",
        )

        if (!conditions.enabled) {
            abandonCurrent()
            terminalKey = null
            return skip(request, AutoConnectSkipReason.DISABLED)
        }
        if (!conditions.setupReady) {
            abandonCurrent()
            blockedReason = AutoConnectSkipReason.SETUP_FAILED
            terminalKey = request.key
            state = AutoConnectState.BLOCKED
            return skip(request, AutoConnectSkipReason.SETUP_FAILED)
        }
        resetTerminalForNewLaunch(request)
        blockedReason?.let { return skip(request, it) }
        if (userDisconnected) return skip(request, AutoConnectSkipReason.USER_DISCONNECTED)
        manualConnectAtMillis?.let { manualAt ->
            if (nowMillis - manualAt < staleRequestMillis) {
                return skip(request, AutoConnectSkipReason.MANUAL_CONNECT_IN_PROGRESS)
            }
            manualConnectAtMillis = null
        }
        if (conditions.usbAutoLaunchActive) {
            return skip(request, AutoConnectSkipReason.USB_AUTO_LAUNCH_ACTIVE)
        }
        when (conditions.session) {
            AutoConnectSessionState.ACTIVE -> {
                state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.ACTIVE
                active = null
                return skip(request, AutoConnectSkipReason.SESSION_EXISTS)
            }
            AutoConnectSessionState.TRANSITIONING ->
                return skip(request, AutoConnectSkipReason.SESSION_TRANSITIONING)
            AutoConnectSessionState.NONE -> Unit
        }

        val current = active
        if (current != null) {
            if (current.request.key == request.key) {
                when (state) {
                    AutoConnectState.RETRY_SCHEDULED ->
                        return skip(request, AutoConnectSkipReason.RETRY_SCHEDULED, retryAtMillis)
                    AutoConnectState.WAITING_FOR_PERMISSION ->
                        return skip(request, AutoConnectSkipReason.WAITING_FOR_PERMISSION)
                    AutoConnectState.WAITING_FOR_USER ->
                        return skip(request, AutoConnectSkipReason.WAITING_FOR_USER)
                    AutoConnectState.STARTING if hostLaunchDispatched ->
                        return skip(request, AutoConnectSkipReason.REQUEST_IN_FLIGHT)
                    else -> {
                        if (nowMillis - current.startedAtMillis < staleRequestMillis) {
                            return skip(request, AutoConnectSkipReason.REQUEST_IN_FLIGHT)
                        }
                        log("event=STALE_REQUEST_DROPPED ageMs=${nowMillis - current.startedAtMillis}")
                        active = null
                        attempts = 0
                    }
                }
            } else {
                if (state == AutoConnectState.RETRY_SCHEDULED) {
                    return skip(request, AutoConnectSkipReason.RETRY_SCHEDULED, retryAtMillis)
                }
                if (state == AutoConnectState.WAITING_FOR_PERMISSION) {
                    return skip(request, AutoConnectSkipReason.WAITING_FOR_PERMISSION)
                }
                if (state == AutoConnectState.WAITING_FOR_USER) {
                    return skip(request, AutoConnectSkipReason.WAITING_FOR_USER)
                }
                if (nowMillis - current.startedAtMillis < staleRequestMillis) {
                    return skip(request, AutoConnectSkipReason.REQUEST_IN_FLIGHT)
                }
                log("event=STALE_REQUEST_DROPPED ageMs=${nowMillis - current.startedAtMillis}")
                active = null
                attempts = 0
            }
        }

        if (attempts >= maxAttempts) {
            terminalKey = request.key
            state = AutoConnectState.EXHAUSTED
            return skip(request, AutoConnectSkipReason.RETRY_EXHAUSTED)
        }
        return start(request, nowMillis)
    }

    @Synchronized
    fun postponeForPermission(generation: Long): Boolean {
        if (generation != this.generation || active == null) return false
        state = AutoConnectState.WAITING_FOR_PERMISSION
        log("event=WAITING_FOR_PERMISSION generation=$generation")
        return true
    }

    @Synchronized
    fun onPermissionResult(generation: Long, granted: Boolean, nowMillis: Long = clock()): AutoConnectOutcome {
        if (generation != this.generation || active == null) {
            log("decision=STALE_IGNORED event=PERMISSION_RESULT generation=$generation")
            return AutoConnectOutcome.StaleCallbackIgnored
        }
        if (!granted) {
            blockedReason = AutoConnectSkipReason.PERMISSION_DENIED
            terminalKey = active?.request?.key ?: lastStartedKey
            active = null
            hostLaunchDispatched = false
            state = AutoConnectState.BLOCKED
            log("decision=SKIP reason=PERMISSION_DENIED generation=$generation")
            return AutoConnectOutcome.Blocked(AutoConnectSkipReason.PERMISSION_DENIED)
        }
        active = active?.copy(startedAtMillis = nowMillis)
        state = AutoConnectState.STARTING
        log("decision=CONTINUE reason=PERMISSION_GRANTED generation=$generation")
        return AutoConnectOutcome.ContinueAfterPermission
    }

    @Synchronized
    fun onHostLaunchFailed(
        generation: Long,
        failure: AutoConnectFailure,
        nowMillis: Long = clock(),
    ): AutoConnectOutcome {
        if (generation != this.generation || active == null) {
            log("decision=STALE_IGNORED event=HOST_LAUNCH_FAILED generation=$generation")
            return AutoConnectOutcome.StaleCallbackIgnored
        }
        val reason = when (failure) {
            AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE -> AutoConnectSkipReason.REQUEST_IN_FLIGHT
            AutoConnectFailure.PERMISSION_DENIED -> AutoConnectSkipReason.PERMISSION_DENIED
            AutoConnectFailure.INITIALIZATION_FAILED -> AutoConnectSkipReason.SETUP_FAILED
        }
        if (failure != AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE) {
            blockedReason = reason
            terminalKey = active?.request?.key ?: lastStartedKey
            active = null
            hostLaunchDispatched = false
            state = AutoConnectState.BLOCKED
            log("decision=BLOCKED reason=${reason.label} generation=$generation error=${failure.label}")
            return AutoConnectOutcome.Blocked(reason)
        }
        hostLaunchDispatched = false
        if (attempts >= maxAttempts) {
            terminalKey = active?.request?.key ?: lastStartedKey
            active = null
            state = AutoConnectState.EXHAUSTED
            log("decision=RETRY_EXHAUSTED generation=$generation attempts=$attempts/$maxAttempts")
            return AutoConnectOutcome.Exhausted(generation)
        }
        val delay = retryDelaysMillis[(attempts - 1).coerceIn(0, retryDelaysMillis.lastIndex)]
        retryAtMillis = nowMillis + delay
        state = AutoConnectState.RETRY_SCHEDULED
        log(
            "decision=RETRY_SCHEDULED generation=$generation attempt=${attempts + 1}/$maxAttempts " +
                "delayMs=$delay error=${failure.label}",
        )
        return AutoConnectOutcome.RetryScheduled(generation, attempts + 1, maxAttempts, delay)
    }

    @Synchronized
    fun retryDue(
        conditions: AutoConnectConditions,
        nowMillis: Long = clock(),
    ): AutoConnectOutcome {
        when (state) {
            AutoConnectState.RETRY_SCHEDULED -> Unit
            AutoConnectState.SUPPRESSED ->
                return AutoConnectOutcome.Skip(AutoConnectSkipReason.USER_DISCONNECTED)
            AutoConnectState.BLOCKED ->
                return AutoConnectOutcome.Skip(blockedReason ?: AutoConnectSkipReason.PERMISSION_DENIED)
            AutoConnectState.EXHAUSTED ->
                return AutoConnectOutcome.Skip(AutoConnectSkipReason.RETRY_EXHAUSTED)
            else -> return AutoConnectOutcome.Skip(AutoConnectSkipReason.RETRY_NOT_DUE)
        }
        val request = active?.request ?: return AutoConnectOutcome.Skip(AutoConnectSkipReason.RETRY_EXHAUSTED)
        if (!conditions.enabled) {
            abandonCurrent()
            return skip(request, AutoConnectSkipReason.DISABLED)
        }
        if (!conditions.setupReady) {
            abandonCurrent()
            blockedReason = AutoConnectSkipReason.SETUP_FAILED
            state = AutoConnectState.BLOCKED
            return skip(request, AutoConnectSkipReason.SETUP_FAILED)
        }
        if (blockedReason != null) {
            abandonCurrent()
            return skip(request, blockedReason!!)
        }
        if (userDisconnected) {
            abandonCurrent()
            return skip(request, AutoConnectSkipReason.USER_DISCONNECTED)
        }
        if (conditions.usbAutoLaunchActive) {
            active = null
            attempts = 0
            retryAtMillis = 0L
            state = AutoConnectState.IDLE
            return skip(request, AutoConnectSkipReason.USB_AUTO_LAUNCH_ACTIVE)
        }
        when (conditions.session) {
            AutoConnectSessionState.ACTIVE -> {
                abandonCurrent()
                return skip(request, AutoConnectSkipReason.SESSION_EXISTS)
            }
            AutoConnectSessionState.TRANSITIONING -> {
                abandonCurrent()
                return skip(request, AutoConnectSkipReason.SESSION_TRANSITIONING)
            }
            AutoConnectSessionState.NONE -> Unit
        }
        if (nowMillis < retryAtMillis) {
            return AutoConnectOutcome.Skip(AutoConnectSkipReason.RETRY_NOT_DUE, retryAtMillis)
        }
        if (attempts >= maxAttempts) {
            terminalKey = request.key
            active = null
            state = AutoConnectState.EXHAUSTED
            log("decision=RETRY_EXHAUSTED generation=$generation attempts=$attempts/$maxAttempts")
            return AutoConnectOutcome.Exhausted(generation)
        }
        return start(request, nowMillis)
    }

    /**
     * True only for a session that this coordinator admitted automatically. Manual, USB
     * auto-attach and notification-launched sessions keep their existing recovery behaviour.
     */
    @Synchronized
    fun suppressesHostRecovery(autoConnectEnabled: Boolean): Boolean {
        if (!autoStarted && !userDisconnected) return false
        val reason = when {
            userDisconnected -> "USER_DISCONNECTED"
            !autoConnectEnabled -> "AUTO_CONNECT_OFF"
            blockedReason != null -> blockedReason!!.name
            state == AutoConnectState.EXHAUSTED -> "RETRY_EXHAUSTED"
            else -> null
        } ?: return false
        log("event=HOST_RECOVERY_SUPPRESSED reason=$reason")
        return true
    }

    @Synchronized
    fun resetForTests() {
        state = AutoConnectState.IDLE
        active = null
        attempts = 0
        retryAtMillis = 0L
        userDisconnected = false
        blockedReason = null
        terminalKey = null
        lastStartedKey = null
        manualConnectAtMillis = null
        observedActive = false
        observedHadActiveSession = false
        hostLaunchDispatched = false
        autoStarted = false
    }

    private fun start(request: AutoConnectRequest, nowMillis: Long): AutoConnectOutcome {
        generation++
        autoStarted = true
        attempts++
        retryAtMillis = 0L
        terminalKey = null
        lastStartedKey = request.key
        hostLaunchDispatched = false
        state = AutoConnectState.STARTING
        active = ActiveRequest(request, nowMillis)
        log(
            "decision=START generation=$generation attempt=$attempts/$maxAttempts " +
                "source=${request.source.label} transport=${request.transport.label}",
        )
        return AutoConnectOutcome.Start(generation, attempts, maxAttempts, request)
    }

    private fun abandonCurrent() {
        active = null
        attempts = 0
        retryAtMillis = 0L
        hostLaunchDispatched = false
        state = if (userDisconnected) AutoConnectState.SUPPRESSED else AutoConnectState.IDLE
    }

    private fun resetTerminalForNewLaunch(request: AutoConnectRequest) {
        if (terminalKey == null || terminalKey == request.key) return
        if (state != AutoConnectState.BLOCKED && state != AutoConnectState.EXHAUSTED) return
        log("event=NEW_LAUNCH_RESET previousState=${state.label}")
        active = null
        attempts = 0
        retryAtMillis = 0L
        blockedReason = null
        terminalKey = null
        hostLaunchDispatched = false
        state = AutoConnectState.IDLE
    }

    /** True while a specific request generation is still allowed to continue after a user dialog. */
    @Synchronized
    fun isRequestActive(generation: Long): Boolean =
        generation == this.generation && active != null &&
            (state == AutoConnectState.STARTING || state == AutoConnectState.WAITING_FOR_PERMISSION ||
                state == AutoConnectState.WAITING_FOR_USER)

    @Synchronized
    fun onHostLaunchDispatched(generation: Long) {
        if (generation != this.generation || active == null) return
        hostLaunchDispatched = true
        log("event=HOST_LAUNCH_DISPATCHED generation=$generation")
    }

    private fun skip(
        request: AutoConnectRequest,
        reason: AutoConnectSkipReason,
        retryAt: Long? = null,
    ): AutoConnectOutcome {
        log(
            "decision=SKIP reason=${reason.label} source=${request.source.label} " +
                "transport=${request.transport.label} state=${state.label}" +
                (retryAt?.let { " retryAtMs=$it" } ?: ""),
        )
        return AutoConnectOutcome.Skip(reason, retryAt)
    }

    private fun log(message: String) = logger(message)

    private val AutoConnectSource.label: String
        get() = when (this) {
            AutoConnectSource.APP_LAUNCH -> "APP_LAUNCH"
            AutoConnectSource.ACTIVITY_RECREATED -> "ACTIVITY_RECREATED"
            AutoConnectSource.NEW_INTENT -> "NEW_INTENT"
            AutoConnectSource.BOOT -> "BOOT"
            AutoConnectSource.SETTINGS -> "SETTINGS"
        }

    private val AutoConnectTransport.label: String
        get() = when (this) {
            AutoConnectTransport.WIRELESS -> "WIRELESS"
            AutoConnectTransport.WIRED -> "WIRED"
        }

    private val AutoConnectState.label: String
        get() = name

    private val AutoConnectSessionState.label: String
        get() = name

    private val AutoConnectSkipReason.label: String
        get() = name

    private val AutoConnectFailure.label: String
        get() = name

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private val DEFAULT_RETRY_DELAYS_MILLIS = longArrayOf(2_000L, 5_000L)
        private const val DEFAULT_STALE_REQUEST_MILLIS = 20_000L

        val shared = AutoConnectCoordinator()
    }
}
