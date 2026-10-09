package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPerformanceTracerTest {
    @Test
    fun tracingIsEnabledForDebugOrExplicitBenchmarkOptIn() {
        assertTrue(SplitPerformanceTracer.shouldEnable(debuggable = true, benchmarkOptIn = false))
        assertTrue(SplitPerformanceTracer.shouldEnable(debuggable = false, benchmarkOptIn = true))
        assertFalse(SplitPerformanceTracer.shouldEnable(debuggable = false, benchmarkOptIn = false))
    }

    @Test
    fun counterDeltaUsesTheBeforeAndAfterSnapshot() {
        val before = LongArray(SplitPerformanceCounter.entries.size)
        val after = before.copyOf()
        before[SplitPerformanceCounter.AIRPLAY_SESSION_CHANGES.ordinal] = 2
        after[SplitPerformanceCounter.AIRPLAY_SESSION_CHANGES.ordinal] = 3
        after[SplitPerformanceCounter.VIEWAREA_COMMAND_WRITE_OK.ordinal] = 5

        val summary = SplitPerformanceTracer.deltaSummary(after, before)

        assertEquals(0L, Regex("carplay_controller_starts=(\\d+)").find(summary)?.groupValues?.get(1)?.toLong())
        assertEquals(1L, Regex("airplay_session_changes=(\\d+)").find(summary)?.groupValues?.get(1)?.toLong())
        assertEquals(5L, Regex("viewarea_command_write_ok=(\\d+)").find(summary)?.groupValues?.get(1)?.toLong())
    }
}
