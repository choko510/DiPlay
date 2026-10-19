package com.shilapi.xcertplay.transport

import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredPathDiagnosticsTest {
    @Test
    fun disabledRecorderDoesNotCountOrFormatEvents() {
        val diagnostics = WiredPathDiagnostics("disabled", 4, enabled = false)

        diagnostics.record(WiredPathEvent.V6_SYN_TO_AIRPLAY)
        val snapshot = diagnostics.seal()

        assertEquals(0L, snapshot.counter(WiredPathEvent.V6_SYN_TO_AIRPLAY).count)
        assertFalse(snapshot.counterOverflow)
        assertTrue(snapshot.sealed)
    }

    @Test
    fun attachGenerationSeparatesStaleCallbacksFromCurrentAttempt() {
        val diagnostics = WiredPathDiagnostics("run-1", 9, enabled = true)
        diagnostics.recordAttachGeneration(3)

        diagnostics.record(WiredPathEvent.NCM_USB_QUEUE, callbackAttachGeneration = 3)
        diagnostics.record(WiredPathEvent.NCM_USB_QUEUE, callbackAttachGeneration = 2)
        diagnostics.recordAttachGeneration(4)
        diagnostics.record(WiredPathEvent.NCM_USB_QUEUE, callbackAttachGeneration = 3)
        diagnostics.record(WiredPathEvent.NCM_USB_QUEUE, callbackAttachGeneration = 4)

        val snapshot = diagnostics.snapshot(WiredPathReportReason.ACCEPT)
        assertEquals(2L, snapshot.counter(WiredPathEvent.NCM_USB_QUEUE).count)
        assertEquals(2L, snapshot.counter(WiredPathEvent.STALE_CALLBACK).count)
        assertEquals(4L, snapshot.attachGeneration)
    }

    @Test
    fun timestampsAreRelativeAndRecordOnceKeepsOnlyFirstOccurrence() {
        val now = AtomicLong(10_000_000L)
        val diagnostics = WiredPathDiagnostics("run-2", 2, enabled = true, clockNanos = now::get)
        now.set(15_000_000L)
        diagnostics.recordOnce(WiredPathEvent.MAIN_FIRST_FRAME)
        now.set(45_000_000L)
        diagnostics.recordOnce(WiredPathEvent.MAIN_FIRST_FRAME)

        val first = diagnostics.snapshot(WiredPathReportReason.MAIN)
            .counter(WiredPathEvent.MAIN_FIRST_FRAME)
        assertEquals(1L, first.count)
        assertEquals(5L, first.firstElapsedMs)
    }

    @Test
    fun sealIsIdempotentAndDropsLateCallbacks() {
        val diagnostics = WiredPathDiagnostics("run-3", 5, enabled = true)
        diagnostics.record(WiredPathEvent.LISTENER_ACCEPTED)

        val final = diagnostics.seal()
        diagnostics.record(WiredPathEvent.RECORD_ACTIVE)

        assertTrue(final.sealed)
        assertEquals(WiredPathReportReason.FINAL, final.reason)
        assertEquals(1L, final.counter(WiredPathEvent.LISTENER_ACCEPTED).count)
        assertEquals(0L, final.counter(WiredPathEvent.RECORD_ACTIVE).count)
        assertSame(final, diagnostics.seal())
    }

    @Test
    fun allConcurrentIncrementsAreRetained() {
        val diagnostics = WiredPathDiagnostics("run-4", 6, enabled = true)
        val workerCount = 8
        val iterations = 10_000
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)
        val futures = (0 until workerCount).map {
            executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(iterations) { diagnostics.record(WiredPathEvent.NCM_USB_COMPLETION) }
            }
        }
        start.countDown()
        futures.forEach { it.get(20, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(
            (workerCount * iterations).toLong(),
            diagnostics.seal().counter(WiredPathEvent.NCM_USB_COMPLETION).count,
        )
    }

    @Test
    fun counterSaturatesAndMarksOverflow() {
        val diagnostics = WiredPathDiagnostics("run-5", 7, enabled = true)
        val counts = privateField(diagnostics, "counts") as AtomicLongArray
        counts.set(WiredPathEvent.NCM_USB_QUEUE.ordinal, Long.MAX_VALUE)

        diagnostics.record(WiredPathEvent.NCM_USB_QUEUE)

        val snapshot = diagnostics.seal()
        assertEquals(Long.MAX_VALUE, snapshot.counter(WiredPathEvent.NCM_USB_QUEUE).count)
        assertTrue(snapshot.counterOverflow)
    }

    @Test
    fun reportUsesFixedPiiFreeFieldsAndSanitizesRunId() {
        val diagnostics = WiredPathDiagnostics("fe80::2", 8, enabled = true)
        diagnostics.record(WiredPathEvent.TCP_SYN_ACK_FROM_TUN)

        val line = WiredPathReportFormatter.format(diagnostics.snapshot(WiredPathReportReason.RECORD))

        assertTrue(line.startsWith("WIRED_PATH_V1 schema=1 run=run-unknown attempt=8"))
        assertTrue(line.contains("reason=RECORD"))
        assertTrue(line.contains("checksum=NOT_CHECKED"))
        assertTrue(line.contains("tcp_syn_ack_from_tun=1_first_ms="))
        assertFalse(line.contains("fe80::2"))
        assertFalse(line.contains("mac="))
        assertFalse(line.contains("payload="))
    }

    private fun privateField(instance: Any, name: String): Any {
        val field: Field = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(instance) ?: error("Field $name was null")
    }
}
