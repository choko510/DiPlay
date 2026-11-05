package com.shilapi.xcertplay.youtube

import android.view.MotionEvent
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalCarPlayScaleTouchTest {
    @Test
    fun visibleContentCoordinatesMapToTheSameRemoteNormalizedPosition() {
        val bounds = requireNotNull(
            LocalCarPlayScalePolicy.fitBounds(
                remote = DisplaySize(1920, 1080),
                viewport = DisplaySize(1056, 1080),
            ),
        )
        val event = MotionEvent.obtain(
            0L,
            1L,
            MotionEvent.ACTION_DOWN,
            bounds.width / 2f,
            bounds.height / 2f,
            0,
        )

        try {
            val contact = CarPlayTouchMapper.contacts(event, bounds.width, bounds.height).single()

            assertEquals(0.5, contact.x, 0.0001)
            assertEquals(0.5, contact.y, 0.0001)
            assertTrue(bounds.contains(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f))
        } finally {
            event.recycle()
        }
    }
}
