package com.shilapi.xcertplay.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOwnerSlotTest {
    private class Owner(val token: AudioOwnerToken, val label: String)

    @Test
    fun delayedBRollbackCannotClearGenerationC() {
        val slot = AudioOwnerSlot<Owner>(100)
        val sinkOwner = AtomicReference<AudioOwnerToken?>()
        val bCommitted = CountDownLatch(1)
        val resumeB = CountDownLatch(1)
        val bStarts = AtomicInteger()
        val bClosed = AtomicInteger()
        val c = AtomicReference<Owner>()

        val a = synchronized(slot.lock) {
            val owner = Owner(slot.nextToken(), "A")
            slot.owner = owner
            sinkOwner.set(owner.token)
            owner
        }

        val bWorker = Thread {
            val b = synchronized(slot.lock) {
                val owner = Owner(slot.nextToken(), "B")
                slot.owner = owner
                sinkOwner.set(owner.token)
                owner
            }
            bCommitted.countDown()
            check(resumeB.await(2, TimeUnit.SECONDS))

            val started = synchronized(slot.lock) {
                if (slot.owner === b) {
                    bStarts.incrementAndGet()
                    true
                } else {
                    false
                }
            }
            if (!started) {
                bClosed.incrementAndGet()
                slot.clearIfCurrent(b) { sinkOwner.compareAndSet(b.token, null) }
            }
        }.apply { isDaemon = true }
        bWorker.start()

        assertTrue(bCommitted.await(2, TimeUnit.SECONDS))
        val bToken = synchronized(slot.lock) { slot.owner!!.token }
        val cOwner = synchronized(slot.lock) {
            val owner = Owner(slot.nextToken(), "C")
            slot.owner = owner
            sinkOwner.set(owner.token)
            c.set(owner)
            owner
        }
        resumeB.countDown()
        bWorker.join(2_000)

        assertFalse(bWorker.isAlive)
        assertEquals(1L, a.token.generation)
        assertEquals(2L, bToken.generation)
        assertEquals(3L, cOwner.token.generation)
        assertEquals(0, bStarts.get())
        assertEquals(1, bClosed.get())
        assertSame(c.get(), slot.owner)
        assertEquals(cOwner.token, sinkOwner.get())

        assertFalse(slot.clearIfCurrent(a) { sinkOwner.compareAndSet(a.token, null) })
        assertSame(cOwner, slot.owner)
        assertEquals(cOwner.token, sinkOwner.get())
    }
}
