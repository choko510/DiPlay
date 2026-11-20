// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

/**
 * How [DiPlayActivity] was opened. Only a plain app start may begin an automatic connection;
 * navigating to a DiPlay screen must never be treated as a request to connect.
 */
internal enum class DiPlayLaunchOrigin {
    APP_START,
    USER_NAVIGATION,
    EXPLICIT_CONNECT_REQUEST,
}

internal object DiPlayLaunchIntentPolicy {
    fun origin(
        page: String?,
        returnToMenu: Boolean,
        openConnectedIphone: Boolean,
    ): DiPlayLaunchOrigin = when {
        openConnectedIphone -> DiPlayLaunchOrigin.EXPLICIT_CONNECT_REQUEST
        page != null || returnToMenu -> DiPlayLaunchOrigin.USER_NAVIGATION
        else -> DiPlayLaunchOrigin.APP_START
    }

    fun shouldAutoConnectOnResume(origin: DiPlayLaunchOrigin): Boolean =
        origin == DiPlayLaunchOrigin.APP_START

    fun shouldAutoLaunchUsbOnResume(origin: DiPlayLaunchOrigin): Boolean =
        origin == DiPlayLaunchOrigin.APP_START
}

/**
 * Accepts CarPlay `requestUI` only while the CarPlay host itself owns the screen, so a stale or
 * repeated OEM request cannot replace DiPlay settings or stack another menu instance.
 */
internal fun shouldOpenHostUi(
    hostVisible: Boolean,
    menuOpen: Boolean,
    surface: DiPlaySurface,
    sessionCurrent: Boolean,
    sessionClosed: Boolean,
): Boolean = hostVisible &&
    !menuOpen &&
    surface == DiPlaySurface.CARPLAY_HOST &&
    sessionCurrent &&
    !sessionClosed

/**
 * Collapses a burst of OEM requests for the same session. The gate is reset whenever the host
 * becomes visible again, so a later deliberate tap of the CarPlay icon still works.
 */
internal class HostUiRequestGate(
    private val duplicateWindowMillis: Long = DUPLICATE_WINDOW_MILLIS,
) {
    private var lastSession: Any? = null
    private var lastAcceptedAtMillis = Long.MIN_VALUE

    @Synchronized
    fun accept(session: Any, nowMillis: Long): Boolean {
        if (session !== lastSession) {
            lastSession = session
            lastAcceptedAtMillis = nowMillis
            return true
        }
        val elapsed = nowMillis - lastAcceptedAtMillis
        if (elapsed in 0..duplicateWindowMillis) return false
        lastAcceptedAtMillis = nowMillis
        return true
    }

    @Synchronized
    fun reset() {
        lastSession = null
        lastAcceptedAtMillis = Long.MIN_VALUE
    }

    companion object {
        const val DUPLICATE_WINDOW_MILLIS = 750L
    }
}
