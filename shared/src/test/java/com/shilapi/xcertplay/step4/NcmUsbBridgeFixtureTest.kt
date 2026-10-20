package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.NcmUsbBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    shadows = [ScriptedUsbDeviceConnectionShadow::class, ScriptedUsbRequestShadow::class],
)
class NcmUsbBridgeFixtureTest {
    @Test
    fun unscriptedBulkOutAndReadQueueCannotPassAsSuccessfulUsbIo() {
        val outFixture = RobolectricUsbPipeFixture()
        val outBridge = bridge(outFixture)
        try {
            assertTrue(outBridge.send(ethernetFrame(), timeoutMillis = 100) is NcmSendResult.Failed)
            assertEquals(0L, outBridge.diagnosticSnapshot().writeSuccesses)
        } finally {
            outBridge.close()
        }

        val readFixture = RobolectricUsbPipeFixture()
        val readBridge = bridge(readFixture)
        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                readBridge.recv(timeoutMillis = 100)
            }
            assertEquals(0, readFixture.shadow.queueAttempts)
            assertEquals(0L, readBridge.diagnosticSnapshot().readCompletions)
        } finally {
            readBridge.close()
        }
    }

    @Test
    fun bulkOutRequiresAnExplicitScriptedResultAndWritesTheBuiltNtb() {
        val fixture = RobolectricUsbPipeFixture()
        val frame = ethernetFrame()
        val expected = Ntb16Fixture.single(frame)
        fixture.shadow.scriptBulkOut(BulkOutResult.Count(expected.size))
        val bridge = bridge(fixture)

        try {
            assertSame(NcmSendResult.Sent, bridge.send(frame, timeoutMillis = 100))
            assertArrayEquals(expected, fixture.shadow.outgoingBytes())
            assertEquals(1L, bridge.diagnosticSnapshot().writeSuccesses)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun notReadyAndPartialBulkOutResultsStayDistinct() {
        val frame = EthernetPacketFixture.ipv6Tcp(flags = 0x12)
        val expectedBlockSize = Ntb16Fixture.single(frame).size
        val notReadyFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkOut(BulkOutResult.Count(0))
        }
        val notReadyBridge = bridge(notReadyFixture)
        try {
            assertSame(NcmSendResult.NotReady, notReadyBridge.send(frame, timeoutMillis = 100))
            assertEquals(1L, notReadyBridge.diagnosticSnapshot().writeNotReady)
            assertEquals(0L, notReadyBridge.diagnosticSnapshot().writeSuccesses)
            assertEquals(1L, notReadyBridge.diagnosticSnapshot().ipv6TxAttempts)
            assertEquals(0L, notReadyBridge.diagnosticSnapshot().ipv6TxSuccesses)
        } finally {
            notReadyBridge.close()
        }

        val partialFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkOut(BulkOutResult.Count(expectedBlockSize - 1))
        }
        val partialBridge = bridge(partialFixture)
        try {
            assertTrue(partialBridge.send(frame, timeoutMillis = 100) is NcmSendResult.Failed)
            assertEquals(1L, partialBridge.diagnosticSnapshot().writePartialFailures)
            assertEquals(expectedBlockSize - 1, partialFixture.shadow.outgoingBytes().size)
        } finally {
            partialBridge.close()
        }
    }

    @Test
    fun synchronousReadsReassembleSplitAndCoalescedNtb16Blocks() {
        val firstFrame = ethernetFrame(60)
        val secondFrame = ethernetFrame(92)
        val firstBlock = Ntb16Fixture.single(firstFrame, sequence = 1)
        val secondBlock = Ntb16Fixture.single(secondFrame, sequence = 2)
        val firstSplit = Ntb16Fixture.split(firstBlock, firstChunkBytes = 7)
        val fixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(
                BulkInResult.Bytes(firstSplit[0]),
                BulkInResult.Bytes(Ntb16Fixture.coalesced(firstSplit[1], secondBlock)),
            )
        }
        val bridge = bridge(fixture, NcmDiagnosticProfile.SYNC_BULK_IN)

        try {
            assertArrayEquals(firstFrame, bridge.recv(timeoutMillis = 100))
            assertArrayEquals(secondFrame, bridge.recv(timeoutMillis = 100))
            assertEquals(2L, bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun exact512ByteNtbAcceptsTheMainPadAndCharacterizesTheUnpaddedWait() {
        val (frame, paddedBlock) = Ntb16Fixture.exact512Boundary(padded = true)
        val paddedFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(BulkInResult.Bytes(paddedBlock))
        }
        val paddedBridge = bridge(paddedFixture, NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            assertArrayEquals(frame, paddedBridge.recv(timeoutMillis = 100))
        } finally {
            paddedBridge.close()
        }

        val (unpaddedFrame, unpaddedBlock) = Ntb16Fixture.exact512Boundary(padded = false)
        assertArrayEquals(frame, unpaddedFrame)
        val unpaddedFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(BulkInResult.Bytes(unpaddedBlock))
            it.shadow.repeatBulkIn(BulkInResult.Count(0))
        }
        val unpaddedBridge = bridge(unpaddedFixture, NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            assertNull(unpaddedBridge.recv(timeoutMillis = 5))
            assertEquals(0L, unpaddedBridge.diagnosticSnapshot().ethernetRxFrames)
            assertTrue(unpaddedBridge.diagnosticSnapshot().syncReadNoData > 0L)
        } finally {
            unpaddedBridge.close()
        }
    }

    @Test
    fun shortHeaderRemainsIncompleteAndCorruptSignatureFailsClosed() {
        val shortFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(BulkInResult.Bytes(Ntb16Fixture.shortHeader()))
            it.shadow.repeatBulkIn(BulkInResult.Count(0))
        }
        val shortBridge = bridge(shortFixture, NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            assertNull(shortBridge.recv(timeoutMillis = 5))
            assertEquals(0L, shortBridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            shortBridge.close()
        }

        val corruptFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(BulkInResult.Bytes(Ntb16Fixture.corruptSignature()))
        }
        val corruptBridge = bridge(corruptFixture, NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                corruptBridge.recv(timeoutMillis = 50)
            }
            assertEquals(0L, corruptBridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            corruptBridge.close()
        }
    }

    @Test
    fun asyncTimeoutRetainsOneQueuedRequestForTheLaterCompletion() {
        val frame = ethernetFrame(72)
        val block = Ntb16Fixture.single(frame)
        val fixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptQueue(UsbQueueResult.Accept)
            it.shadow.repeatWait(UsbWaitResult.Timeout)
        }
        val bridge = bridge(fixture)

        try {
            assertNull(bridge.recv(timeoutMillis = 2))
            val timedOut = bridge.diagnosticSnapshot()
            assertEquals(1, fixture.shadow.queueAttempts)
            assertEquals(32 * 1024, fixture.shadow.lastQueueCapacity)
            assertTrue(timedOut.readTimeouts > 0L)
            assertEquals("QUEUED", timedOut.readState.name)
            val pendingRequest = fixture.shadow.lastQueuedRequest

            fixture.shadow.clearRepeatedWait()
            fixture.shadow.scriptWait(UsbWaitResult.Complete(block))

            assertArrayEquals(frame, bridge.recv(timeoutMillis = 100))
            assertEquals(1, fixture.shadow.queueAttempts)
            assertSame(pendingRequest, fixture.shadow.lastQueuedRequest)
            assertEquals(1L, bridge.diagnosticSnapshot().readCompletions)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun synchronousAndAsynchronousReadModesDeliverTheSameFrame() {
        val frame = ethernetFrame(72)
        val block = Ntb16Fixture.single(frame)
        val synchronousFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptBulkIn(BulkInResult.Bytes(block))
        }
        val asynchronousFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptQueue(UsbQueueResult.Accept)
            it.shadow.scriptWait(UsbWaitResult.Complete(block))
        }
        val synchronousBridge = bridge(synchronousFixture, NcmDiagnosticProfile.SYNC_BULK_IN)
        val asynchronousBridge = bridge(asynchronousFixture, NcmDiagnosticProfile.AUTO)

        try {
            val synchronousFrame = synchronousBridge.recv(timeoutMillis = 100)
            val asynchronousFrame = asynchronousBridge.recv(timeoutMillis = 100)

            assertArrayEquals(frame, synchronousFrame)
            assertArrayEquals(synchronousFrame, asynchronousFrame)
        } finally {
            synchronousBridge.close()
            asynchronousBridge.close()
        }
    }

    @Test
    fun queueRejectionRecordsThe32KiBRequestAndDoesNotAssumeSuccess() {
        val fixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptQueue(UsbQueueResult.Reject)
        }
        val bridge = bridge(fixture)

        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                bridge.recv(timeoutMillis = 100)
            }
            assertEquals(1, fixture.shadow.queueAttempts)
            assertEquals(32 * 1024, fixture.shadow.lastQueueCapacity)
            assertEquals(1L, bridge.diagnosticSnapshot().readQueueFailures)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun nullAndUnexpectedRequestWaitResultsAreTransportFailures() {
        val nullFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptQueue(UsbQueueResult.Accept)
            it.shadow.scriptWait(UsbWaitResult.NullResult)
        }
        val nullBridge = bridge(nullFixture)
        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                nullBridge.recv(timeoutMillis = 100)
            }
            assertEquals(1L, nullBridge.diagnosticSnapshot().readNullErrors)
        } finally {
            nullBridge.close()
        }

        val unexpectedFixture = RobolectricUsbPipeFixture().also {
            it.shadow.scriptQueue(UsbQueueResult.Accept)
            it.shadow.scriptWait(UsbWaitResult.UnexpectedRequest)
        }
        val unexpectedBridge = bridge(unexpectedFixture)
        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                unexpectedBridge.recv(timeoutMillis = 100)
            }
            assertEquals(1L, unexpectedBridge.diagnosticSnapshot().readUnexpectedCompletions)
        } finally {
            unexpectedBridge.close()
        }
    }

    private fun bridge(
        fixture: RobolectricUsbPipeFixture,
        profile: NcmDiagnosticProfile = NcmDiagnosticProfile.AUTO,
    ): NcmUsbBridge = NcmUsbBridge(
        connection = fixture.connection,
        outEndpoint = fixture.bulkOut,
        inEndpoint = fixture.bulkIn,
        statusEndpoint = null,
        descriptorHostMac = null,
        selectionDiagnostics = "step4-fixture",
        onDiagnosticEvent = {},
        diagnosticProfile = profile,
        diagnosticAttempt = 1,
        diagnosticsEnabled = false,
        onDiagnosticLog = {},
    )

    private fun ethernetFrame(length: Int = 64): ByteArray = ByteArray(length) { index ->
        (index * 17 + 3).toByte()
    }
}
