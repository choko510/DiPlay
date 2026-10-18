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
                activeModulesMask = DspActiveModuleMask.HIGH_EXCITER or DspActiveModuleMask.SEAT_FOCUS,
                seatLatencyFrames = 264,
                algorithmicLatencyFrames = 328,
                harmonicQuality = DspHarmonicQuality.FOUR_X,
                autoHeadroomDb = 6.0,
                cabinCalibrationId = "cal_front",
                nativeErrorCount = 2,
                pipelineProcessUs = DspTimingSummary(p99Us = 321),
            ),
        )

        val elevated = DspAudioMetricsStore.snapshot()
        assertEquals(-0.75, elevated.inputCorrelation, 1e-6)
        assertEquals(0.5, elevated.outputCorrelation, 1e-6)
        assertEquals(0.95, elevated.outputPeak, 1e-6)
        assertEquals(DspClipRisk.ELEVATED, elevated.clipRisk)
        assertEquals(DspActiveModuleMask.HIGH_EXCITER or DspActiveModuleMask.SEAT_FOCUS, elevated.activeModulesMask)
        assertEquals(264, elevated.seatLatencyFrames)
        assertEquals(328, elevated.algorithmicLatencyFrames)
        assertEquals(DspHarmonicQuality.FOUR_X, elevated.harmonicQuality)
        assertEquals(6.0, elevated.autoHeadroomDb, 0.0)
        assertEquals("cal_front", elevated.cabinCalibrationId)
        assertEquals(2L, elevated.nativeErrorCount)
        assertEquals(321.0, elevated.processP99Us, 0.0)

        DspAudioMetricsStore.update(DspDiagnosticsSnapshot(outputPeakL = 1.01))
        assertEquals(DspClipRisk.HIGH, DspAudioMetricsStore.snapshot().clipRisk)
    }
}
