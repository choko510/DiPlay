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
 * Three lifecycle details matter:
 * - An Activity reports page changes inside itself through [resumed].
 * - Every DiPlay navigation is announced with [navigatingTo] before `startActivity`, because Android
 *   pauses the source before it resumes the destination.
 * - A screen that returns through `finish()` re-announces its parent with [announceReturn], and the
 *   transparent USB attach receivers restore the covered screen with [transientOverlayResumed],
 *   because Android starts those without our code running first.
 */
internal object DiPlayUiFocus {
    private var owner: Any? = null
    private var surface: DiPlaySurface = DiPlaySurface.NONE
    private var navigationAnnounced = false
    private var coveredSurface: DiPlaySurface = DiPlaySurface.NONE
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
     * Android started it without running any DiPlay navigation code, so the covered screen is
     * restored instead of treated as "the user left DiPlay".
     */
    @Synchronized
    fun transientOverlayResumed(owner: Any) {
        if (surface != DiPlaySurface.NONE) return
        if (coveredSurface == DiPlaySurface.NONE) return
        this.owner = owner
        this.surface = coveredSurface
        navigationAnnounced = true
        coveredSurface = DiPlaySurface.NONE
    }

    @Synchronized
    fun paused(owner: Any) {
        if (this.owner !== owner) return
        // The announced destination is not resumed yet; keep protecting it.
        if (navigationAnnounced) return
        coveredSurface = surface
        this.owner = null
        this.surface = DiPlaySurface.NONE
        lastNavigation = null
    }

    @Synchronized
    fun current(): DiPlaySurface = surface

    @Synchronized
    fun isUserNavigationTarget(): Boolean = surface.isUserNavigationTarget

    @Synchronized
    fun reset() {
        owner = null
        surface = DiPlaySurface.NONE
        navigationAnnounced = false
        coveredSurface = DiPlaySurface.NONE
        lastNavigation = null
    }
}
