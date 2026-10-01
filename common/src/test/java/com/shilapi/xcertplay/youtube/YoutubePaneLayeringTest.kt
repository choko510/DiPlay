package com.shilapi.xcertplay.youtube

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class YoutubePaneLayeringTest {
    @Test
    fun statusOverlayStaysAboveBrowserAfterReattachment() {
        val context = RuntimeEnvironment.getApplication()
        val container = FrameLayout(context)
        val status = TextView(context)
        val browser = View(context)
        container.addView(status, FrameLayout.LayoutParams(-1, -1))

        YoutubePaneLayering.attachBrowserBelowStatus(container, browser, status)

        assertSame(browser, container.getChildAt(0))
        assertSame(status, container.getChildAt(1))

        container.removeView(browser)
        FrameLayout(context).addView(browser)
        YoutubePaneLayering.attachBrowserBelowStatus(container, browser, status)

        assertSame(browser, container.getChildAt(0))
        assertSame(status, container.getChildAt(1))
    }
}
