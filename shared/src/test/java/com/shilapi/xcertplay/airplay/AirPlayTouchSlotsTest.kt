package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTouchSlotsTest {
    @Test
    fun pointerSlotsStayStableAndLiftUsesTheFinalNormalizedPosition() {
        val slots = AirPlayTouchSlots()
        val down = slots.prepare(
            listOf(
                contact(id = 10, x = 0.2, y = 0.3),
                contact(id = 22, x = 0.7, y = 0.8),
            ),
        )
        assertEquals(listOf(0, 1), down.contacts.map(AirPlayContact::id))
        slots.complete(down, delivered = true)

        val liftFirst = slots.prepare(
            listOf(
                contact(id = 10, x = 0.25, y = 0.35, down = false),
                contact(id = 22, x = 0.72, y = 0.81),
            ),
        )
        assertFalse(liftFirst.contacts[0].down)
        assertEquals(0.25, liftFirst.contacts[0].x, 0.0)
        assertEquals(0.35, liftFirst.contacts[0].y, 0.0)
        assertTrue(liftFirst.contacts[1].down)
        assertEquals(1, liftFirst.contacts[1].id)
        slots.complete(liftFirst, delivered = true)

        val remaining = slots.prepare(listOf(contact(id = 22, x = 0.73, y = 0.82)))
        assertFalse(remaining.contacts[0].down)
        assertTrue(remaining.contacts[1].down)
        assertEquals(1, remaining.contacts[1].id)
    }

    @Test
    fun thirdPointerUsesFreedSlotOnTheNextReport() {
        val slots = AirPlayTouchSlots()
        val initial = slots.prepare(
            listOf(contact(-10), contact(22), contact(400)),
        )
        assertEquals(listOf(0, 1), initial.contacts.filter(AirPlayContact::down).map(AirPlayContact::id))
        slots.complete(initial, delivered = true)

        val released = slots.prepare(listOf(contact(22), contact(400)))
        assertFalse(released.contacts[0].down)
        assertTrue(released.contacts[1].down)
        assertEquals(1, released.contacts[1].id)
        slots.complete(released, delivered = true)

        val reassigned = slots.prepare(listOf(contact(22), contact(400)))
        assertEquals(0, reassigned.contacts[0].id)
        assertTrue(reassigned.contacts[0].down)
        assertEquals(1, reassigned.contacts[1].id)
        assertTrue(reassigned.contacts[1].down)
    }

    @Test
    fun failedReleaseIsDeliveredBeforeAReusedPointerIdTakesAnotherSlot() {
        val slots = AirPlayTouchSlots()
        val down = slots.prepare(listOf(contact(10, x = 0.4, y = 0.6)))
        slots.complete(down, delivered = true)

        val failedRelease = slots.prepare(emptyList())
        slots.complete(failedRelease, delivered = false)

        val reused = slots.prepare(listOf(contact(10, x = 0.8, y = 0.9)))
        assertFalse(reused.contacts[0].down)
        assertEquals(0.4, reused.contacts[0].x, 0.0)
        assertTrue(reused.contacts[1].down)
        assertEquals(1, reused.contacts[1].id)
        slots.complete(reused, delivered = true)

        val nextMove = slots.prepare(listOf(contact(10, x = 0.81, y = 0.91)))
        assertTrue(nextMove.contacts[1].down)
        assertEquals(1, nextMove.contacts[1].id)
    }

    private fun contact(
        id: Int,
        x: Double = 0.5,
        y: Double = 0.5,
        down: Boolean = true,
    ) = AirPlayContact(id, x, y, down)
}
