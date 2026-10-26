package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class WiredStartupTimingTest {
    @Test
    fun exactFullNcmOutIsRecordedBeforeOrAfterStartSessionWithoutAcceptingPartialOrNotReady() {
        val now = AtomicLong(10_000L)
        val timing = WiredStartupTiming(attemptId = 1L, clockNanos = now::get)

        now.set(10_050L)
        assertFalse(timing.recordFullBulkOut(transferred = 0, expectedFullSize = 64))
        assertFalse(timing.recordFullBulkOut(transferred = 63, expectedFullSize = 64))
        assertTrue(timing.recordFullBulkOut(transferred = 64, expectedFullSize = 64))
        now.set(10_100L)
        assertTrue(timing.record(WiredStartupTimingEvent.FIRST_START_SESSION))
        now.set(10_200L)
        assertFalse(timing.recordFullBulkOut(transferred = 64, expectedFullSize = 64))

        val snapshot = timing.snapshot(nowNanos = 10_200L)
        assertEquals(50L, snapshot.firstFullBulkOutNs)
        assertEquals(100L, snapshot.startSessionSentNs)
    }

    @Test
    fun generationRejectsOldCallbacksAndOldAttemptCannotChangeAnotherTimeline() {
        val now = AtomicLong(1_000L)
        val oldAttempt = WiredStartupTiming(attemptId = 4L, clockNanos = now::get)
        val activeAttempt = WiredStartupTiming(attemptId = 5L, clockNanos = now::get)
        assertTrue(oldAttempt.recordAttachGeneration(8L))
        assertTrue(activeAttempt.recordAttachGeneration(9L))

        now.set(1_100L)
        assertTrue(oldAttempt.recordFullBulkOut(64, 64, callbackGeneration = 8L))
        assertFalse(oldAttempt.record(WiredStartupTimingEvent.LISTENER_ACCEPT, callbackGeneration = 9L))
        assertTrue(oldAttempt.record(WiredStartupTimingEvent.LISTENER_ACCEPT, callbackGeneration = 8L))
        assertTrue(activeAttempt.record(WiredStartupTimingEvent.LISTENER_ACCEPT, callbackGeneration = 9L))

        assertEquals(100L, oldAttempt.snapshot().listenerAcceptedNs)
        assertEquals(100L, activeAttempt.snapshot().listenerAcceptedNs)
        assertEquals(100L, oldAttempt.snapshot().firstFullBulkOutNs)
        assertNull(activeAttempt.snapshot().firstFullBulkOutNs)
        assertEquals(8L, oldAttempt.snapshot().attachGeneration)
        assertEquals(9L, activeAttempt.snapshot().attachGeneration)
    }

    @Test
    fun recordsEveryStageAsAttemptRelativeNanosAndSealsAtTeardown() {
        val now = AtomicLong(100_000L)
        val timing = WiredStartupTiming(attemptId = 6L, clockNanos = now::get)
        timing.recordAttachGeneration(4L)
        val expected = mutableListOf<Long>()
        fun record(event: WiredStartupTimingEvent) {
            now.addAndGet(10L)
            expected += now.get() - 100_000L
            assertTrue(timing.record(event, callbackGeneration = 4L))
        }

        record(WiredStartupTimingEvent.FIRST_START_SESSION)
        now.addAndGet(10L)
        expected += now.get() - 100_000L
        assertTrue(timing.recordFullBulkOut(128, 128, callbackGeneration = 4L))
        record(WiredStartupTimingEvent.FIRST_VALID_AIRPLAY_SYN)
        record(WiredStartupTimingEvent.FIRST_SYN_ACK_TUN)
        record(WiredStartupTimingEvent.FIRST_SYN_ACK_OUT)
        record(WiredStartupTimingEvent.LISTENER_ACCEPT)
        record(WiredStartupTimingEvent.RTSP_RECORD)
        record(WiredStartupTimingEvent.MAIN_SETUP)
        record(WiredStartupTimingEvent.MAIN_FIRST_FRAME)
        record(WiredStartupTimingEvent.TEARDOWN)

        val snapshot = timing.snapshot()
        assertEquals(expected[0], snapshot.startSessionSentNs)
        assertEquals(expected[1], snapshot.firstFullBulkOutNs)
        assertEquals(expected[2], snapshot.firstValidAirPlaySynNs)
        assertEquals(expected[3], snapshot.firstSynAckTunNs)
        assertEquals(expected[4], snapshot.firstSynAckOutNs)
        assertEquals(expected[5], snapshot.listenerAcceptedNs)
        assertEquals(expected[6], snapshot.rtspRecordNs)
        assertEquals(expected[7], snapshot.mainSetupNs)
        assertEquals(expected[8], snapshot.mainFirstFrameNs)
        assertEquals(expected[9], snapshot.teardownNs)
        assertFalse(timing.record(WiredStartupTimingEvent.LISTENER_ACCEPT, callbackGeneration = 4L))
        assertTrue(WiredStartupTimingFormatter.format(snapshot).contains("first_full_bulk_out_ns=20"))
    }

    @Test
    fun exactFullNcmOutAfterStartSessionHasItsOwnTimestamp() {
        val now = AtomicLong(75_000L)
        val timing = WiredStartupTiming(attemptId = 12L, clockNanos = now::get)

        now.set(75_100L)
        assertTrue(timing.record(WiredStartupTimingEvent.FIRST_START_SESSION))
        now.set(75_200L)
        assertTrue(timing.recordFullBulkOut(96, 96))

        val snapshot = timing.snapshot()
        assertEquals(100L, snapshot.startSessionSentNs)
        assertEquals(200L, snapshot.firstFullBulkOutNs)
    }

    @Test
    fun validAirplaySynRequiresTargetAndPortAndUnknownParseFailsClosed() {
        val now = AtomicLong(5_000L)
        val timing = WiredStartupTiming(attemptId = 7L, clockNanos = now::get)
        timing.recordAttachGeneration(2L)

        val wrongTarget = WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_VALID,
            family = WiredPacketClassification.FAMILY_IPV6,
            protocol = WiredPacketClassification.PROTOCOL_TCP,
            tcpFlags = TCP_SYN,
            addressChecked = true,
            addressMatches = false,
            portChecked = true,
            portMatches = true,
        )
        repeat(8) {
            assertFalse(timing.recordAirPlaySyn(wrongTarget, callbackGeneration = 2L))
        }
        assertNull(timing.snapshot(nowNanos = 5_000L).firstValidAirPlaySynNs)

        val unknownExtension = WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_UNSUPPORTED,
            family = WiredPacketClassification.FAMILY_IPV6,
            vlanTagged = true,
        )
        assertFalse(timing.recordAirPlaySyn(unknownExtension, callbackGeneration = 2L))
        assertEquals(SynObservation.UNKNOWN, timing.snapshot(nowNanos = 5_000L).synObservation)

        now.set(5_100L)
        val validSyn = WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_VALID,
            family = WiredPacketClassification.FAMILY_IPV6,
            protocol = WiredPacketClassification.PROTOCOL_TCP,
            tcpFlags = TCP_SYN,
            addressChecked = true,
            addressMatches = true,
            portChecked = true,
            portMatches = true,
            vlanTagged = true,
        )
        assertTrue(timing.recordAirPlaySyn(validSyn, callbackGeneration = 2L))
        assertEquals(100L, timing.snapshot().firstValidAirPlaySynNs)
        assertEquals(SynObservation.PRESENT, timing.snapshot().synObservation)

        val ipv4OnlyTiming = WiredStartupTiming(attemptId = 11L, clockNanos = now::get)
        ipv4OnlyTiming.recordAttachGeneration(2L)
        ipv4OnlyTiming.record(WiredStartupTimingEvent.FIRST_START_SESSION)
        val ipv4SynWithoutAddressProof = WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_VALID,
            family = WiredPacketClassification.FAMILY_IPV4,
            protocol = WiredPacketClassification.PROTOCOL_TCP,
            tcpFlags = TCP_SYN,
            portChecked = true,
            portMatches = true,
        )
        assertFalse(ipv4OnlyTiming.recordAirPlaySyn(ipv4SynWithoutAddressProof, callbackGeneration = 2L))
        assertEquals(
            SynObservation.UNKNOWN,
            ipv4OnlyTiming.snapshot(nowNanos = now.get() + 15_000_000_000L).synObservation,
        )
    }

    @Test
    fun synAckTimelineRequiresValidTargetAndSuccessfulNcmOut() {
        val now = AtomicLong(20_000L)
        val timing = WiredStartupTiming(attemptId = 8L, clockNanos = now::get)
        timing.recordAttachGeneration(3L)
        val validSynAck = WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_VALID,
            family = WiredPacketClassification.FAMILY_IPV6,
            protocol = WiredPacketClassification.PROTOCOL_TCP,
            tcpFlags = TCP_SYN or TCP_ACK,
            addressChecked = true,
            addressMatches = true,
            portChecked = true,
            portMatches = true,
        )

        assertTrue(timing.recordSynAckFromTun(validSynAck, callbackGeneration = 3L))
        assertFalse(timing.recordSynAckOut(sent = false, callbackGeneration = 3L))
        now.set(20_200L)
        assertTrue(timing.recordSynAckOut(sent = true, callbackGeneration = 3L))

        val snapshot = timing.snapshot()
        assertEquals(0L, snapshot.firstSynAckTunNs)
        assertEquals(200L, snapshot.firstSynAckOutNs)
    }

    @Test
    fun synObservationWindowAndQuietThresholdsAreObservationalOnly() {
        val now = AtomicLong(100L)
        val timing = WiredStartupTiming(attemptId = 9L, clockNanos = now::get)
        timing.record(WiredStartupTimingEvent.FIRST_START_SESSION)

        assertEquals(
            SynObservation.UNKNOWN,
            timing.snapshot(nowNanos = 14_900_000_100L).synObservation,
        )
        for (elapsedMillis in listOf(15_000L, 17_000L, 26_000L)) {
            assertEquals(
                SynObservation.ABSENT_WITHIN_WINDOW,
                timing.snapshot(nowNanos = 100L + elapsedMillis * NANOS_PER_MILLISECOND).synObservation,
            )
        }
        assertFalse(hasQuietWindowElapsed(1_000L, 2_000_000_999L, quietMillis = 2_000L))
        assertTrue(hasQuietWindowElapsed(1_000L, 2_000_001_000L, quietMillis = 2_000L))
        assertFalse(hasQuietWindowElapsed(null, 10_000_000_000L, quietMillis = 2_000L))
    }

    @Test
    fun alwaysOnTimingIsIndependentFromOptInPathCountersAndFormatterOmitsPacketData() {
        val now = AtomicLong(40_000L)
        val timing = WiredStartupTiming(attemptId = 10L, clockNanos = now::get)
        val optIn = WiredPathDiagnostics("debug-off", 10, enabled = false, clockNanos = now::get)

        timing.record(WiredStartupTimingEvent.FIRST_START_SESSION)
        optIn.record(WiredPathEvent.START)
        val formatted = WiredStartupTimingFormatter.format(timing.snapshot())

        assertTrue(formatted.startsWith("WIRED_STAGE3_V1 attempt=10 attach_generation=none"))
        assertTrue(formatted.contains("start_session_ns=0"))
        assertTrue(formatted.contains("syn_observation=UNKNOWN"))
        assertFalse(formatted.contains("packet="))
        assertEquals(0L, optIn.snapshot(WiredPathReportReason.START).counter(WiredPathEvent.START).count)
    }

    private companion object {
        const val TCP_SYN = 0x02
        const val TCP_ACK = 0x10
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
