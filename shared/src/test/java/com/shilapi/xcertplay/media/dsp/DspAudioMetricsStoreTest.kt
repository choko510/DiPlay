package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class DspAudioMetricsStoreTest {
    @Test
    fun latestCorrelationAndPeakRiskArePublishedAsOneUiSnapshot() {
        DspAudioMetricsStore.update(
            DspDiagnosticsSnapshot(
                inputCorrelation = -0.75,
                outputCorrelation = 0.5,
                outputPeakL = 0.95,
                outputPeakR = 0.4,
            ),
        )

        val elevated = DspAudioMetricsStore.snapshot()
        assertEquals(-0.75, elevated.inputCorrelation, 1e-6)
        assertEquals(0.5, elevated.outputCorrelation, 1e-6)
        assertEquals(0.95, elevated.outputPeak, 1e-6)
        assertEquals(DspClipRisk.ELEVATED, elevated.clipRisk)

        DspAudioMetricsStore.update(DspDiagnosticsSnapshot(outputPeakL = 1.01))
        assertEquals(DspClipRisk.HIGH, DspAudioMetricsStore.snapshot().clipRisk)
    }
}
