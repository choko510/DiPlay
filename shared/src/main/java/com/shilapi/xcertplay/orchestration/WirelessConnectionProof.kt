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
    TUNNEL_ENDED,
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
    private var authenticatedIndependently = false
    private var authenticatedByTunnel = false
    private var liveTunnelAuthenticated = false
    private var pendingTunnelAuthenticationBeforeSession = false
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
        authenticatedIndependently = firstSession && pendingAuthenticationBeforeSession
        authenticatedByTunnel =
            firstSession && pendingTunnelAuthenticationBeforeSession && liveTunnelAuthenticated
        authenticated = authenticatedIndependently || authenticatedByTunnel
        pendingAuthenticationBeforeSession = false
        pendingTunnelAuthenticationBeforeSession = false
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
        authenticatedIndependently = true
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
            WirelessControlMode.TUNNEL_READY -> {
                check(liveTunnelAuthenticated) { "TUNNEL_READY requires a live authenticated tunnel" }
                completeTunnelHandoff()
            }
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
        return when (controlMode) {
            WirelessControlMode.BOOTSTRAP -> {
                liveTunnelAuthenticated = true
                if (session == null && !hasActivatedSession) {
                    pendingTunnelAuthenticationBeforeSession = true
                } else if (session != null) {
                    authenticatedByTunnel = true
                    authenticated = true
                    confirmIfReady()
                }
                controlMode = WirelessControlMode.TUNNEL_READY
                transition(WirelessControlTransitionKind.TUNNEL_READY)
            }
            WirelessControlMode.HANDOFF_REQUESTED,
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL -> {
                liveTunnelAuthenticated = true
                if (session == null && !hasActivatedSession) {
                    pendingTunnelAuthenticationBeforeSession = true
                } else if (session != null) {
                    authenticatedByTunnel = true
                    authenticated = true
                    confirmIfReady()
                }
                completeTunnelHandoff()
            }
            WirelessControlMode.TUNNEL_READY,
            WirelessControlMode.TUNNEL_CONTROL -> {
                liveTunnelAuthenticated = true
                if (session != null) {
                    authenticatedByTunnel = true
                    authenticated = true
                    confirmIfReady()
                }
                transition(WirelessControlTransitionKind.IGNORED)
            }
            else -> transition(WirelessControlTransitionKind.IGNORED)
        }
    }

    @Synchronized fun tunnelEnded(generation: Int): WirelessControlTransition {
        if (
            this.generation != generation ||
            controlMode == WirelessControlMode.INACTIVE ||
            controlMode == WirelessControlMode.FAILED
        ) {
            return transition(WirelessControlTransitionKind.IGNORED)
        }

        liveTunnelAuthenticated = false
        pendingTunnelAuthenticationBeforeSession = false
        authenticatedByTunnel = false
        authenticated = authenticatedIndependently

        return when (controlMode) {
            WirelessControlMode.TUNNEL_READY -> {
                if (bluetoothBootstrapOpen) {
                    controlMode = WirelessControlMode.BOOTSTRAP
                    transition(WirelessControlTransitionKind.TUNNEL_ENDED)
                } else {
                    failForLostControlPath("Wireless tunnel closed after Bluetooth bootstrap ended")
                }
            }
            WirelessControlMode.TUNNEL_CONTROL ->
                failForLostControlPath("Wireless tunnel control path closed")
            WirelessControlMode.BOOTSTRAP,
            WirelessControlMode.HANDOFF_REQUESTED,
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL -> {
                if (bluetoothBootstrapOpen) {
                    transition(WirelessControlTransitionKind.TUNNEL_ENDED)
                } else {
                    failForLostControlPath("Wireless tunnel closed without a live Bluetooth control path")
                }
            }
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
        authenticatedIndependently = false
        authenticatedByTunnel = false
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
        authenticatedIndependently = false
        authenticatedByTunnel = false
        liveTunnelAuthenticated = false
        pendingTunnelAuthenticationBeforeSession = false
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

    @Synchronized fun hasOperationalCarPlayControl(
        generation: Int,
        nowNanos: Long,
        maxFrameAgeNanos: Long,
    ): Boolean {
        if (
            this.generation != generation ||
            session == null ||
            !hasRecentRenderedFrame(generation, nowNanos, maxFrameAgeNanos)
        ) {
            return false
        }
        return when (controlMode) {
            WirelessControlMode.BOOTSTRAP,
            WirelessControlMode.HANDOFF_REQUESTED,
            WirelessControlMode.AIRPLAY_ACTIVE_WITH_BOOTSTRAP_CONTROL -> bluetoothBootstrapOpen
            WirelessControlMode.TUNNEL_READY,
            WirelessControlMode.TUNNEL_CONTROL -> liveTunnelAuthenticated
            else -> false
        }
    }

    private fun completeTunnelHandoff(): WirelessControlTransition {
        check(liveTunnelAuthenticated) { "Tunnel control cannot take ownership after its tunnel ended" }
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

    private fun failForLostControlPath(reason: String): WirelessControlTransition {
        controlMode = WirelessControlMode.FAILED
        return transition(WirelessControlTransitionKind.FAILED, failureReason = reason)
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
