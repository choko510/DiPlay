package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPerformanceTracerTest {
    @Test
    fun tracingIsEnabledForDebugOrExplicitBenchmarkOptIn() {
        assertTrue(SplitPerformanceTracer.shouldEnable(debuggable = true, benchmarkOptIn = false))
        assertTrue(SplitPerformanceTracer.shouldEnable(debuggable = false, benchmarkOptIn = true))
        assertFalse(SplitPerformanceTracer.shouldEnable(debuggable = false, benchmarkOptIn = false))
    }
}
