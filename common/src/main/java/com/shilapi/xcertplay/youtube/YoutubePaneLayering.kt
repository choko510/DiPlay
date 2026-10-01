package com.shilapi.xcertplay.youtube

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal object YoutubePaneLayering {
    fun attachBrowserBelowStatus(container: FrameLayout, browser: View, status: View) {
        (browser.parent as? ViewGroup)?.removeView(browser)
        container.addView(browser, 0, FrameLayout.LayoutParams(-1, -1))
        status.bringToFront()
    }

    fun bringStatusToFront(status: View) {
        status.bringToFront()
    }
}
