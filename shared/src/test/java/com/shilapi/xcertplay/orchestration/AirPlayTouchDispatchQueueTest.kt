package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTouchDispatchQueueTest {
    @Test
    fun coalescesPendingMovesButKeepsDownAndUpTransitionsInOrder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<List<AirPlayContact>>()
        val session = Any()
        val queue = AirPlayTouchDispatchQueue<Any>(capacity = 4, dispatch = { _, _, contacts ->
            if (delivered.isEmpty()) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            delivered += contacts
        })

        try {
            assertTrue(queue.submit(session, 1, listOf(contact(0.1, down = true))))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.submit(session, 1, listOf(contact(0.2, down = true))))
            assertTrue(queue.submit(session, 1, listOf(contact(0.3, down = true))))
            assertTrue(queue.submit(session, 1, listOf(contact(0.3, down = false))))

            assertEquals(2, queue.pendingCountForTest)
            assertEquals(0.3, queue.pendingContactsForTest()[0].single().x, 0.0)
            assertFalse(queue.pendingContactsForTest()[1].single().down)
            release.countDown()
            assertTrue(waitFor { delivered.size == 3 })
            assertTrue(delivered[0].single().down)
            assertTrue(delivered[1].single().down)
            assertFalse(delivered[2].single().down)
            assertEquals(0.3, delivered[1].single().x, 0.0)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun overflowingEdgeQueueCancelsHeldContactsAndRemainsBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<List<AirPlayContact>>()
        val session = Any()
        val queue = AirPlayTouchDispatchQueue<Any>(capacity = 2, dispatch = { _, _, contacts ->
            if (delivered.isEmpty()) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            delivered += contacts
        })

        try {
            assertTrue(queue.submit(session, 3, listOf(contact(0.1, down = true))))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.submit(session, 3, listOf(contact(0.1, down = false))))
            assertTrue(queue.submit(session, 3, listOf(contact(0.2, down = true))))
            assertFalse(queue.submit(session, 3, listOf(contact(0.2, down = false))))

            assertTrue(queue.pendingCountForTest <= 2)
            assertEquals(listOf(emptyList<AirPlayContact>()), queue.pendingContactsForTest())
            release.countDown()
            assertTrue(waitFor { delivered.size == 2 })
            assertTrue(delivered.last().isEmpty())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun queuedTouchCarriesTheOwningSessionGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<Pair<Any, Long>>()
        val session = Any()
        val queue = AirPlayTouchDispatchQueue<Any>(capacity = 2, dispatch = { owner, generation, _ ->
            if (delivered.isEmpty()) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            delivered += owner to generation
        })

        try {
            assertTrue(queue.submit(session, 10, listOf(contact(0.1, down = true))))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.submit(session, 11, listOf(contact(0.2, down = true))))
            release.countDown()
            assertTrue(waitFor { delivered.size == 2 })
            assertTrue(delivered.all { it.first === session })
            assertEquals(listOf(10L, 11L), delivered.map { it.second })
        } finally {
            release.countDown()
            queue.close()
        }
    }

    private fun contact(x: Double, down: Boolean) = AirPlayContact(7, x, 0.5, down)

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(2)
        }
        return condition()
    }
}
