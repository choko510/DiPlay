package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayMenuYoutubeNavigationTest {
    @Test
    fun youtubeActionTargetsTheExistingCarPlayHostWithSplitAction() {
        val activity = Robolectric.buildActivity(DiPlayMenuActivity::class.java).setup().get()

        requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.menu_carplay_youtube)))
            .performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        assertEquals(CarPlayHostActions.ENTER_YOUTUBE_SPLIT, started.action)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun returnToCarPlayRequestsFullCarPlayLayout() {
        val activity = Robolectric.buildActivity(DiPlayMenuActivity::class.java).setup().get()

        requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.menu_return_carplay)))
            .performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(CarPlayHostActions.EXIT_YOUTUBE_SPLIT, started.action)
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
