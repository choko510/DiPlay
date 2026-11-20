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
 * An Activity reports page changes inside itself through [resumed], and announces every navigation
 * it starts with [navigatingTo] before `startActivity`. Android pauses the source before it resumes
 * the destination, so without the announcement the gap between the two lifecycle calls would look
 * like "no DiPlay screen is open" and an automatic launch could still take the display.
 */
internal object DiPlayUiFocus {
    private var owner: Any? = null
    private var surface: DiPlaySurface = DiPlaySurface.NONE
    private var navigationAnnounced = false

    /** The Activity owns the screen now, including page changes inside itself. */
    @Synchronized
    fun resumed(owner: Any, surface: DiPlaySurface) {
        this.owner = owner
        this.surface = surface
        navigationAnnounced = false
    }

    /**
     * A DiPlay screen is being opened. The destination keeps its protection from this point, so a
     * request that arrives before it resumes cannot replace it.
     */
    @Synchronized
    fun navigatingTo(owner: Any, surface: DiPlaySurface) {
        this.owner = owner
        this.surface = surface
        navigationAnnounced = true
    }

    @Synchronized
    fun paused(owner: Any) {
        if (this.owner !== owner) return
        // The announced destination is not resumed yet; keep protecting it.
        if (navigationAnnounced) return
        this.owner = null
        this.surface = DiPlaySurface.NONE
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
    }
}
