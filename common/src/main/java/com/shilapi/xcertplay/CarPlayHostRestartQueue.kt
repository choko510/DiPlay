// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * What a restart after a transport handover is supposed to restore. A restart that has to wait for
 * a DiPlay settings or menu screen must keep this intent, otherwise a wireless restore turns into a
 * wired CH341 start just because an adapter happens to be attached.
 */
internal enum class CarPlayHostRecovery {
    /** Resume over the attached iPhone or CH341 adapter. */
    WIRED_USB,

    /** Return to the transport the connection was on before the handover (wireless restore). */
    RESTORE_TRANSPORT,
}

/**
 * A host restart that was parked because the user was working in DiPlay settings or the menu.
 * `OPEN_CARPLAY` carries no device identity, so it stays here until a non-settings screen is back.
 */
internal object CarPlayHostRestartQueue {
    private const val TAG = "xcertplay-host"

    private var pending = false

    fun hold() {
        synchronized(this) { pending = true }
        Log.i(TAG, "event=HOST_RESTART result=held_for_user_navigation")
    }

    fun resume(context: Context) {
        if (DiPlayUiFocus.isUserNavigationTarget()) return
        synchronized(this) {
            if (!pending) return
            pending = false
        }
        val intent = CarPlayHostActions.createInternalIntent(context, CarPlayHostActions.OPEN_CARPLAY)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.i(TAG, "event=HOST_RESTART result=host_dispatched")
        } catch (error: RuntimeException) {
            synchronized(this) { pending = true }
            Log.w(TAG, "event=HOST_RESTART result=launch_failed error=${error.javaClass.simpleName}")
        }
    }

    internal fun isPendingForTesting(): Boolean = synchronized(this) { pending }

    internal fun clearForTesting() {
        synchronized(this) { pending = false }
    }
}
