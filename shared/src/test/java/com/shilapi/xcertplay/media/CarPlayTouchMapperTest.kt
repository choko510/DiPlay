package com.shilapi.xcertplay.media

import android.view.InputDevice
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayTouchMapperTest {
    @Test
    fun pointerUpPreservesAndroidIdsWhenTheRemainingPointerMovesToIndexZero() {
        val pointerUp = motionEvent(
            action = MotionEvent.ACTION_POINTER_UP,
            pointers = listOf(Pointer(10, 20f, 30f), Pointer(22, 70f, 80f)),
        )
        val remainingMove = motionEvent(
            action = MotionEvent.ACTION_MOVE,
            pointers = listOf(Pointer(22, 75f, 82f)),
        )

        try {
            val lifted = CarPlayTouchMapper.contacts(pointerUp, viewWidth = 100, viewHeight = 100)
            assertEquals(listOf(AirPlayContact(10, 0.2, 0.3, false), AirPlayContact(22, 0.7, 0.8, true)), lifted)

            val remaining = CarPlayTouchMapper.contacts(remainingMove, viewWidth = 100, viewHeight = 100)
            assertEquals(22, remaining.single().id)
            assertTrue(remaining.single().down)
            assertEquals(0.75, remaining.single().x, 0.0)
        } finally {
            pointerUp.recycle()
            remainingMove.recycle()
        }
    }

    @Test
    fun cancelMarksEveryPointerUpAtItsLastPosition() {
        val cancel = motionEvent(
            action = MotionEvent.ACTION_CANCEL,
            pointers = listOf(Pointer(7, 25f, 40f), Pointer(19, 75f, 60f)),
        )

        try {
            val contacts = CarPlayTouchMapper.contacts(cancel, viewWidth = 100, viewHeight = 100)
            assertEquals(listOf(7, 19), contacts.map(AirPlayContact::id))
            assertTrue(contacts.all { !it.down })
            assertEquals(listOf(0.25, 0.75), contacts.map(AirPlayContact::x))
        } finally {
            cancel.recycle()
        }
    }

    private fun motionEvent(action: Int, pointers: List<Pointer>): MotionEvent {
        val properties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].x
                y = pointers[index].y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0L,
            1L,
            action,
            pointers.size,
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

    private data class Pointer(val id: Int, val x: Float, val y: Float)
}
