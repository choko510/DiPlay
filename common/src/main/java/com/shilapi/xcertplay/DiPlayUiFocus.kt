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
 * Owns which DiPlay surface is resumed. The connection outlives the Activities, so asynchronous
 * USB auto-launch, recovery, and OEM menu requests consult this instead of guessing whether the
 * user is looking at CarPlay.
 */
internal object DiPlayUiFocus {
    private var owner: Any? = null
    private var surface: DiPlaySurface = DiPlaySurface.NONE

    @Synchronized
    fun resumed(owner: Any, surface: DiPlaySurface) {
        this.owner = owner
        this.surface = surface
    }

    @Synchronized
    fun paused(owner: Any) {
        if (this.owner !== owner) return
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
    }
}
