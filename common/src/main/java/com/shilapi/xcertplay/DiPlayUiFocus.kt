// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

/**
 * DiPlay screen that currently owns the foreground.
 */
internal enum class DiPlaySurface {
    NONE,
    HOME,
    SETTINGS,
    DSP_SETTINGS,
    MENU,
    CARPLAY_HOST,
    ;

    /**
     * Screens the user opened on purpose from CarPlay. Connection work keeps running, but an
     * automatic connection or menu request must never replace one of these screens.
     */
    val isUserNavigationTarget: Boolean
        get() = when (this) {
            SETTINGS, DSP_SETTINGS, MENU -> true
            NONE, HOME, CARPLAY_HOST -> false
        }
}

/**
 * Owns which DiPlay surface the user is looking at. The connection outlives the Activities, so
 * asynchronous USB auto-launch, recovery, and OEM menu requests consult this instead of guessing
 * whether the user is looking at CarPlay.
 *
 * The screen is released only when an Activity proves it left the foreground ([stopped]), not on
 * every pause: a pause can mean a dialog, a translucent receiver, or a handoff to the next DiPlay
 * screen. This keeps the protection intact from `onPause` until the destination resumes.
 *
 * Android also starts the transparent USB attach receivers without running DiPlay navigation code,
 * so those receivers restore the screen they cover with [transientOverlayResumed].
 */
internal object DiPlayUiFocus {
    private var owner: Any? = null
    private var surface: DiPlaySurface = DiPlaySurface.NONE
    private var navigationAnnounced = false
    private var coveredSurface: DiPlaySurface = DiPlaySurface.NONE
    private var overlayOwner: Any? = null
    private var ownerLeftWhileCovered = false
    private var nextNavigationToken = 0L
    private var lastNavigation: Navigation? = null

    private data class Navigation(
        val token: Long,
        val previousOwner: Any?,
        val previousSurface: DiPlaySurface,
        val previousAnnounced: Boolean,
    )

    /** The Activity owns the screen now, including page changes inside itself. */
    @Synchronized
    fun resumed(owner: Any, surface: DiPlaySurface) {
        this.owner = owner
        this.surface = surface
        navigationAnnounced = false
        coveredSurface = DiPlaySurface.NONE
        overlayOwner = null
        ownerLeftWhileCovered = false
        lastNavigation = null
    }

    /** The screen is only covered; the user has not chosen another one. */
    @Synchronized
    fun paused(owner: Any) {
        if (this.owner !== owner) return
        if (surface == DiPlaySurface.NONE) return
        coveredSurface = surface
    }

    /**
     * The screen left the foreground, so the user is no longer looking at DiPlay. The announced
     * destination keeps the display until it resumes.
     */
    @Synchronized
    fun stopped(owner: Any) {
        if (this.owner !== owner) return
        coveredSurface = DiPlaySurface.NONE
        if (navigationAnnounced) return
        if (overlayOwner != null) {
            // A transparent receiver is still on top; it releases the screen when it finishes.
            ownerLeftWhileCovered = true
            return
        }
        release()
    }

    private fun release() {
        owner = null
        surface = DiPlaySurface.NONE
        navigationAnnounced = false
        coveredSurface = DiPlaySurface.NONE
        overlayOwner = null
        ownerLeftWhileCovered = false
        lastNavigation = null
    }

    /**
     * A DiPlay screen is being opened. The destination keeps its protection from this point, so a
     * request that arrives before it resumes cannot replace it. The returned token lets a failed
     * launch restore the previous state without touching newer navigations.
     */
    @Synchronized
    fun navigatingTo(owner: Any, surface: DiPlaySurface): Long {
        nextNavigationToken += 1
        lastNavigation = Navigation(nextNavigationToken, this.owner, this.surface, navigationAnnounced)
        this.owner = owner
        this.surface = surface
        navigationAnnounced = true
        return nextNavigationToken
    }

    /** Reverts one announced navigation that never reached its destination. */
    @Synchronized
    fun cancelNavigation(owner: Any, token: Long) {
        val navigation = lastNavigation ?: return
        if (navigation.token != token) return
        if (this.owner !== owner) return
        this.owner = navigation.previousOwner
        this.surface = navigation.previousSurface
        navigationAnnounced = navigation.previousAnnounced
        lastNavigation = null
    }

    /**
     * A screen that is finishing returns to the screen that opened it. `finish()` does not run any
     * code of the destination, so the parent is announced here instead.
     */
    @Synchronized
    fun announceReturn(owner: Any, surface: DiPlaySurface) {
        if (this.owner !== owner) return
        if (navigationAnnounced) return
        this.surface = surface
        navigationAnnounced = true
    }

    /**
     * A transparent DiPlay receiver (USB attach) is running on top of a screen the user chose.
     * Android started it without running any DiPlay navigation code. The receiver only protects the
     * covered screen; it never becomes its owner, so the screen keeps its identity and the receiver
     * releases the protection when it finishes ([transientOverlayFinished]).
     */
    @Synchronized
    fun transientOverlayResumed(owner: Any) {
        if (surface == DiPlaySurface.NONE && coveredSurface == DiPlaySurface.NONE) return
        overlayOwner = owner
    }

    /**
     * The transparent receiver is gone. If the covered screen is still there it keeps the display;
     * if it left DiPlay while covered, nothing is displayed any more and the focus is released.
     */
    @Synchronized
    fun transientOverlayFinished(owner: Any) {
        if (overlayOwner !== owner) return
        overlayOwner = null
        if (!ownerLeftWhileCovered) return
        if (navigationAnnounced) {
            ownerLeftWhileCovered = false
            return
        }
        release()
    }

    @Synchronized
    fun current(): DiPlaySurface = surface

    @Synchronized
    fun isUserNavigationTarget(): Boolean = surface.isUserNavigationTarget

    @Synchronized
    fun reset() {
        release()
    }
}
