package com.shilapi.xcertplay.orchestration

internal enum class WirelessControlMode {
    INACTIVE,
    BOOTSTRAP,
    HANDOFF_REQUESTED,
    AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL,
    TUNNEL_READY,
    TUNNEL_CONTROL,
    FAILED,
}

internal enum class WirelessControlTransitionKind {
    IGNORED,
    HANDOFF_REQUESTED,
    TUNNEL_READY,
    TUNNEL_ACTIVE,
    BOOTSTRAP_CONTROL_ACTIVE,
    WAITING_FOR_TUNNEL,
    BOOTSTRAP_ENDED,
    FAILED,
}

internal data class WirelessControlTransition(
    val kind: WirelessControlTransitionKind,
    val mode: WirelessControlMode,
    val closeBluetoothBootstrap: Boolean = false,
    val reportActive: Boolean = false,
    val failureReason: String? = null,
)

/** Tracks one generation's media proof and the owner of its wireless control channel. */
internal class WirelessConnectionProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var authenticated = false
    private var pendingAuthenticationBeforeSession = false
    private var hasActivatedSession = false
    private var rendered = false
    private var lastRenderedFrameNanos: Long? = null
    private var confirmation: (() -> Unit)? = null
    private var controlMode = WirelessControlMode.INACTIVE
    private var bluetoothBootstrapOpen = false
    private var activeStatusReported = false

    @Synchronized fun begin(generation: Int, confirmation: () -> Unit) {
        clear()
        this.generation = generation
        this.confirmation = confirmation
        controlMode = WirelessControlMode.BOOTSTRAP
        bluetoothBootstrapOpen = true
    }

    @Synchronized fun activate(generation: Int, session: S): Boolean {
        if (
            this.generation != generation ||
            controlMode == WirelessControlMode.INACTIVE ||
            controlMode == WirelessControlMode.FAILED
        ) {
            return false
        }
        if (this.session === session) return true
        val firstSession = !hasActivatedSession
        this.session = session
        hasActivatedSession = true
        authenticated = firstSession && pendingAuthenticationBeforeSession
        pendingAuthenticationBeforeSession = false
        rendered = false
        lastRenderedFrameNanos = null
        return true
    }

    @Synchronized fun authenticated(generation: Int) {
        if (this.generation != generation) return
        if (session == null) {
            if (!hasActivatedSession) pendingAuthenticationBeforeSession = true
            return
        }
        authenticated = true
        confirmIfReady()
    }

    @Synchronized fun rendered(
        generation: Int,
        session: S,
        nowNanos: Long = System.nanoTime(),
    ) {
        if (this.generation != generation || this.session !== session) return
        rendered = true
        lastRenderedFrameNanos = nowNanos
        confirmIfReady()
    }

    @Synchronized fun hasRecentRenderedFrame(
        generation: Int,
        nowNanos: Long,
        maxAgeNanos: Long,
    ): Boolean {
        require(maxAgeNanos >= 0) { "maxAgeNanos must not be negative" }
        if (this.generation != generation || session == null || !rendered) return false
        val lastRendered = lastRenderedFrameNanos ?: return false
        val ageNanos = nowNanos - lastRendered
        return ageNanos in 0..maxAgeNanos
    }

    @Synchronized fun requestHandoff(generation: Int, session: S): WirelessControlTransition {
        if (this.generation != generation || this.session !== session) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }
        return when (controlMode) {
            WirelessControlMode.BOOTSTRAP -> {
                controlMode = WirelessControlMode.HANDOFF_REQUESTED
                transition(WirelessControlTransitionKind.HANDOFF_REQUESTED)
            }
            WirelessControlMode.TUNNEL_READY -> completeTunnelHandoff()
            else -> transition(WirelessControlTransitionKind.IGNORED)
        }
    }

    @Synchronized fun tunnelAuthenticated(generation: Int): WirelessControlTransition {
        if (
            this.generation != generation ||
            controlMode == WirelessControlMode.INACTIVE ||
            controlMode == WirelessControlMode.FAILED
        ) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }
        if (session != null) {
            authenticated = true
            confirmIfReady()
        } else if (!hasActivatedSession) {
            pendingAuthenticationBeforeSession = true
        }
        return when (controlMode) {
            WirelessControlMode.BOOTSTRAP -> {
                controlMode = WirelessControlMode.TUNNEL_READY
                transition(WirelessControlTransitionKind.TUNNEL_READY)
            }
            WirelessControlMode.HANDOFF_REQUESTED,
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL -> completeTunnelHandoff()
            else -> transition(WirelessControlTransitionKind.IGNORED)
        }
    }

    @Synchronized fun handoffTimedOut(
        generation: Int,
        nowNanos: Long,
        maxFrameAgeNanos: Long,
    ): WirelessControlTransition {
        if (
            this.generation != generation ||
            controlMode != WirelessControlMode.HANDOFF_REQUESTED
        ) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }
        val hasRecentVideo = hasRecentRenderedFrame(generation, nowNanos, maxFrameAgeNanos)
        if (bluetoothBootstrapOpen && hasRecentVideo) {
            controlMode = WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL
            val reportActive = !activeStatusReported
            activeStatusReported = true
            return transition(
                WirelessControlTransitionKind.BOOTSTRAP_CONTROL_ACTIVE,
                reportActive = reportActive,
            )
        }

        controlMode = WirelessControlMode.FAILED
        val reason = when {
            !bluetoothBootstrapOpen ->
                "Bluetooth control closed before a wireless iAP2 tunnel became ready"
            session == null ->
                "AirPlay session ended before the wireless iAP2 tunnel became ready"
            else ->
                "Wireless handoff timed out without a recently rendered video frame"
        }
        return transition(WirelessControlTransitionKind.FAILED, failureReason = reason)
    }

    @Synchronized fun bootstrapEnded(generation: Int): WirelessControlTransition {
        if (this.generation != generation) return transition(WirelessControlTransitionKind.IGNORED)
        if (controlMode == WirelessControlMode.TUNNEL_CONTROL) {
            bluetoothBootstrapOpen = false
            return transition(WirelessControlTransitionKind.BOOTSTRAP_ENDED)
        }
        if (!bluetoothBootstrapOpen) return transition(WirelessControlTransitionKind.IGNORED)

        bluetoothBootstrapOpen = false
        return when (controlMode) {
            WirelessControlMode.HANDOFF_REQUESTED,
            WirelessControlMode.TUNNEL_READY ->
                transition(WirelessControlTransitionKind.WAITING_FOR_TUNNEL)
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL -> {
                controlMode = WirelessControlMode.FAILED
                transition(
                    WirelessControlTransitionKind.FAILED,
                    failureReason = "Bluetooth control closed while AirPlay had no authenticated tunnel",
                )
            }
            WirelessControlMode.BOOTSTRAP -> {
                controlMode = WirelessControlMode.FAILED
                transition(
                    WirelessControlTransitionKind.FAILED,
                    failureReason = "Wireless Bluetooth control channel closed before handoff",
                )
            }
            else -> transition(WirelessControlTransitionKind.IGNORED)
        }
    }

    @Synchronized fun fallbackVideoTimedOut(
        generation: Int,
        nowNanos: Long,
        maxFrameAgeNanos: Long,
    ): WirelessControlTransition {
        if (
            this.generation != generation ||
            controlMode != WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL ||
            hasRecentRenderedFrame(generation, nowNanos, maxFrameAgeNanos)
        ) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }
        controlMode = WirelessControlMode.FAILED
        return transition(
            WirelessControlTransitionKind.FAILED,
            failureReason = "AirPlay video stopped rendering while Bluetooth bootstrap was the control path",
        )
    }

    @Synchronized fun end(generation: Int, session: S): WirelessControlTransition {
        if (this.generation != generation || this.session !== session) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }
        this.session = null
        authenticated = false
        rendered = false
        lastRenderedFrameNanos = null
        if (controlMode == WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL) {
            controlMode = WirelessControlMode.FAILED
            return transition(
                WirelessControlTransitionKind.FAILED,
                failureReason = "AirPlay session ended while Bluetooth bootstrap was the active control path",
            )
        }
        return transition(WirelessControlTransitionKind.IGNORED)
    }

    @Synchronized fun clear() {
        generation = -1
        session = null
        authenticated = false
        pendingAuthenticationBeforeSession = false
        hasActivatedSession = false
        rendered = false
        lastRenderedFrameNanos = null
        confirmation = null
        controlMode = WirelessControlMode.INACTIVE
        bluetoothBootstrapOpen = false
        activeStatusReported = false
    }

    @Synchronized fun mode(generation: Int): WirelessControlMode? =
        if (this.generation == generation) controlMode else null

    @Synchronized fun hasEstablishedControl(generation: Int): Boolean =
        this.generation == generation &&
            (controlMode == WirelessControlMode.TUNNEL_READY ||
                controlMode == WirelessControlMode.TUNNEL_CONTROL ||
                controlMode == WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL)

    private fun completeTunnelHandoff(): WirelessControlTransition {
        controlMode = WirelessControlMode.TUNNEL_CONTROL
        val closeBluetoothBootstrap = bluetoothBootstrapOpen
        bluetoothBootstrapOpen = false
        val reportActive = !activeStatusReported
        activeStatusReported = true
        return transition(
            WirelessControlTransitionKind.TUNNEL_ACTIVE,
            closeBluetoothBootstrap = closeBluetoothBootstrap,
            reportActive = reportActive,
        )
    }

    private fun transition(
        kind: WirelessControlTransitionKind,
        closeBluetoothBootstrap: Boolean = false,
        reportActive: Boolean = false,
        failureReason: String? = null,
    ) = WirelessControlTransition(
        kind = kind,
        mode = controlMode,
        closeBluetoothBootstrap = closeBluetoothBootstrap,
        reportActive = reportActive,
        failureReason = failureReason,
    )

    private fun confirmIfReady() {
        if (!authenticated || !rendered) return
        val callback = confirmation ?: return
        confirmation = null
        callback()
    }
}
