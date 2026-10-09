package com.shilapi.xcertplay.media

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarPlayTouchMapperGeometryTest {
    @Test fun localScaleMapsCenterAndRejectsLetterboxDown() {
        val geometry = CarPlayRenderGeometry.create(1280, 720, 1280, 720, 704, 720)!!
        val center = touch(MotionEvent.ACTION_DOWN, listOf(352f to 360f))
        val letterbox = touch(MotionEvent.ACTION_DOWN, listOf(352f to 12f))
        try {
            val contact = CarPlayTouchMapper.contacts(center, geometry)!!.single()
            assertEquals(0.5, contact.x, 0.000001)
            assertEquals(0.5, contact.y, 0.000001)
            assertTrue(contact.down)
            assertNull(CarPlayTouchMapper.contacts(letterbox, geometry))
        } finally {
            center.recycle()
            letterbox.recycle()
        }
    }

    @Test fun splitAreaMapsToItsDeclaredHidRangeAndPointerUpRemainsScoped() {
        val geometry = CarPlayRenderGeometry.create(
            canvasWidth = 1280,
            canvasHeight = 720,
            sourceWidth = 704,
            sourceHeight = 720,
            viewWidth = 704,
            viewHeight = 720,
            viewArea = CarPlayRenderRect(0, 0, 704, 720),
        )!!
        val pointerUp = touch(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(352f to 360f, 704f to 720f),
        )
        try {
            val contacts = CarPlayTouchMapper.contacts(pointerUp, geometry)!!
            assertEquals(2, contacts.size)
            assertTrue(contacts[0].down)
            assertFalse(contacts[1].down)
            assertEquals(704.0 / 1280.0, contacts[1].x, 0.000001)
            assertEquals(1.0, contacts[1].y, 0.000001)
        } finally {
            pointerUp.recycle()
        }
    }

    private fun touch(action: Int, points: List<Pair<Float, Float>>): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0,
            1,
            action,
            points.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
