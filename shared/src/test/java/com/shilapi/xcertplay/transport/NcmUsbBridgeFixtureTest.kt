package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import com.shilapi.xcertplay.step4.Step4TestFixtures
import com.shilapi.xcertplay.step4.UsbRequestWaitFixture

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    shadows = [ScriptedUsbEndpointShadow::class, ScriptedUsbDeviceConnectionShadow::class, ScriptedUsbRequestShadow::class],
)
class NcmUsbBridgeFixtureTest {
    @Test
    fun scriptedUsbRequestReadsSplitAndCoalescedNtb16Blocks() {
        val firstFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x30)
        val secondFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x50)
        val firstBlock = Ntb16Codec.build(firstFrame, sequence = 7)
        val secondBlock = Ntb16Codec.build(secondFrame, sequence = 8)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.split(firstBlock, 3, 19).first()))
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.split(firstBlock, 3, 19)[1]))
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.coalesced(
            Step4TestFixtures.split(firstBlock, 3, 19)[2],
            secondBlock,
        )))

        bridge.use {
            assertArrayEquals(firstFrame, it.recv(100))
            assertArrayEquals(secondFrame, it.recv(100))
            val snapshot = it.diagnosticSnapshot()
            assertEquals(3L, snapshot.readCompletions)
            assertEquals(3L, snapshot.readQueues)
        }

        assertTrue(script.connectionClosed)
        assertEquals(1, script.requestCloseCount)
    }

    @Test
    fun paddedExactPacketBoundaryBlockIsDelivered() {
        val frame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val block = Step4TestFixtures.padded512ByteNtb()
        assertArrayEquals(frame, Ntb16Codec.parse(block, 0, Step4TestFixtures.USB_PACKET_SIZE).single())
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(block))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
        }
    }

    @Test
    fun missingExactPacketBoundaryPadIsDeliveredWithoutWaitingForMissingPad() {
        val frame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val unpaddedBlock = Step4TestFixtures.unpadded512ByteNtb()
        assertArrayEquals(frame, Ntb16Codec.parse(unpaddedBlock).single())
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(unpaddedBlock))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
            val snapshot = it.diagnosticSnapshot()
            assertEquals(1L, snapshot.readCompletions)
            assertEquals(1L, snapshot.ethernetRxFrames)
        }
    }

    @Test
    fun exactBoundaryWithoutPadDoesNotConsumeTheNextNTH16Header() {
        val firstFrame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val secondFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x66)
        val input = Step4TestFixtures.coalesced(
            Step4TestFixtures.unpadded512ByteNtb(),
            Ntb16Codec.build(secondFrame, sequence = 2),
        )
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(input))

        bridge.use {
            assertArrayEquals(firstFrame, it.recv(100))
            assertArrayEquals(secondFrame, it.recv(100))
        }
    }

    @Test
    fun packetBoundaryMinusTwoMinusOnePlusOneAndPlusTwoAreDelivered() {
        for ((index, blockLength) in listOf(510, 511, 513, 514).withIndex()) {
            val frame = Step4TestFixtures.ethernetFrame(
                length = blockLength - 28,
                seed = 0x20 + index,
            )
            val block = Ntb16Codec.build(frame, sequence = index)
            val (bridge, script) = newBridge()
            script.enqueueRead(UsbRequestWaitFixture.Completed(block))

            bridge.use { assertArrayEquals(frame, it.recv(100)) }
        }
    }

    @Test
    fun optionalPadUsesTheBulkInEndpointPacketSize() {
        val frame = Step4TestFixtures.ethernetFrame(length = 64 - 28, seed = 0x5a)
        val block = Ntb16Codec.build(frame, sequence = 7)
        assertEquals(64, block.size)
        val paddedBlock = block + byteArrayOf(0)
        val (bridge, script) = newBridge(bulkInMaxPacketSize = 64)
        script.enqueueRead(UsbRequestWaitFixture.Completed(paddedBlock))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
        }
    }

    @Test
    fun oneByteFragmentedPaddedBoundaryThenNextBlockResynchronizesPastLatePad() {
        val firstFrame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val secondFrame = Step4TestFixtures.ethernetFrame(length = 64, seed = 0x35)
        val input = Step4TestFixtures.coalesced(
            Step4TestFixtures.padded512ByteNtb(),
            Ntb16Codec.build(secondFrame, sequence = 3),
        )
        val (bridge, script) = newBridge()
        input.forEach { script.enqueueRead(UsbRequestWaitFixture.Completed(byteArrayOf(it))) }

        bridge.use {
            assertArrayEquals(firstFrame, it.recv(1_000))
            assertArrayEquals(secondFrame, it.recv(1_000))
        }
    }

    @Test
    fun zeroLengthCompletionDoesNotHideTheNextCompleteNTB() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x59)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(ByteArray(0)))
        script.enqueueRead(UsbRequestWaitFixture.Completed(Ntb16Codec.build(frame, sequence = 4)))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
            assertEquals(2L, it.diagnosticSnapshot().readCompletions)
        }
    }

    @Test
    fun corruptNdpOffsetAndFalseNTHSignatureRecoverTheFollowingNTB() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x73)
        val validBlock = Ntb16Codec.build(frame, sequence = 5)
        val corruptNdp = Step4TestFixtures.unpadded512ByteNtb().copyOf().apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
        }
        val falseHeader = ByteArray(28).apply {
            writeU32(this, 0, Ntb16Codec.NTH16_SIG)
            writeU16(this, 4, 12)
            writeU16(this, 8, 512)
            writeU16(this, 10, 12)
            writeU16(this, 16, 16)
        }
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(corruptNdp, falseHeader, validBlock),
        ))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
            assertEquals(1L, it.diagnosticSnapshot().ethernetRxFrames)
        }
    }

    @Test
    fun ambiguousMaximumLengthNtbFailsClosedInsteadOfEarlyResynchronization() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 40).copyOf().apply {
            writeU16(this, 8, 0xffff)
        }
        val nextBlock = Ntb16Codec.build(nextFrame, sequence = 41)
        val followingBlock = Ntb16Codec.build(followingFrame, sequence = 42)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, nextBlock, followingBlock),
        ))

        val error = runCatching { bridge.recv(100) }.exceptionOrNull()
        val frames = bridge.diagnosticSnapshot().ethernetRxFrames
        bridge.close()

        assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
        assertEquals(0L, frames)
        assertTrue(script.connectionClosed)
    }

    @Test
    fun ambiguousMaximumLengthNtbWithPayloadSignatureFailsClosedWithoutDeliveringFrames() {
        val fakeFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x62)
        val embeddedNtb = Ntb16Codec.build(fakeFrame, sequence = 51)
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 240, seed = 0x31).apply {
            embeddedNtb.copyInto(this, destinationOffset = 48)
        }
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 50).copyOf().apply {
            writeU16(this, 8, 0xffff)
        }
        val nextBlock = Ntb16Codec.build(nextFrame, sequence = 51)
        val followingBlock = Ntb16Codec.build(followingFrame, sequence = 52)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, nextBlock, followingBlock),
        ))

        val error = runCatching { bridge.recv(100) }.exceptionOrNull()
        val frames = bridge.diagnosticSnapshot().ethernetRxFrames
        bridge.close()

        assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
        assertEquals(0L, frames)
        assertTrue(script.connectionClosed)
    }

    @Test
    fun ambiguousMaximumLengthNtbSplitAcrossReadsFailsClosedWithoutDeliveringFrames() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 60).copyOf().apply {
            writeU16(this, 8, 0xffff)
        }
        val nextBlock = Ntb16Codec.build(nextFrame, sequence = 61)
        val followingBlock = Ntb16Codec.build(followingFrame, sequence = 62)
        val splitOffset = followingBlock.size / 2
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, nextBlock, followingBlock.copyOfRange(0, splitOffset)),
        ))
        script.enqueueRead(UsbRequestWaitFixture.Completed(followingBlock.copyOfRange(splitOffset, followingBlock.size)))

        val error = runCatching { bridge.recv(100) }.exceptionOrNull()
        val snapshot = bridge.diagnosticSnapshot()
        bridge.close()

        assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
        assertEquals(2L, snapshot.readCompletions)
        assertEquals(0L, snapshot.ethernetRxFrames)
        assertTrue(script.connectionClosed)
    }

    @Test
    fun validIncompleteNtbDoesNotResyncIntoUnusedGap() {
        val originalFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val embeddedFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val embeddedNtb = Ntb16Codec.build(embeddedFrame, sequence = 51)
        val incompleteNtb = Ntb16Codec.build(originalFrame, sequence = 50).copyOf(300).apply {
            writeU16(this, 8, size)
            embeddedNtb.copyInto(this, destinationOffset = 120)
        }

        for (chunkSize in listOf(1, 2, 64, 512, 32_768)) {
            val (bridge, script) = newBridge()
            incompleteNtb.asList().chunked(chunkSize).forEach { chunk ->
                script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
            }

            bridge.use { assertArrayEquals(originalFrame, it.recv(10_000)) }
        }
    }

    @Test
    fun ndpPointerLoopIsRejectedAndTheFollowingNtbCanBeDelivered() {
        val loopFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x13)
        val recoveredFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x24)
        val loopingBlock = Ntb16Codec.build(loopFrame, sequence = 40).apply {
            writeU16(this, 18, 12)
        }
        val followingBlock = Ntb16Codec.build(recoveredFrame, sequence = 41)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.coalesced(loopingBlock, followingBlock)))

        bridge.use {
            assertArrayEquals(recoveredFrame, it.recv(100))
            assertNull(it.recv(2))
        }
    }

    @Test
    fun overMtuRecoveryAcceptsTheSequencePairAcrossU16Wrap() {
        val rejectedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x35)
        val firstRecoveredFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x46)
        val secondRecoveredFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x57)
        val rejectedBlock = Ntb16Codec.build(rejectedFrame, sequence = 0xffff).apply {
            writeU16(this, 22, EthernetIpv6Codec.MAX_UNTAGGED_ETHERNET_FRAME_BYTES + 1)
        }
        val firstFollowingBlock = Ntb16Codec.build(firstRecoveredFrame, sequence = 0)
        val secondFollowingBlock = Ntb16Codec.build(secondRecoveredFrame, sequence = 1)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(rejectedBlock, firstFollowingBlock, secondFollowingBlock),
        ))

        bridge.use {
            assertArrayEquals(firstRecoveredFrame, it.recv(100))
            assertArrayEquals(secondRecoveredFrame, it.recv(100))
        }
    }

    @Test
    fun tenThousandOneByteCompletionsDeliverNtbFramesInOrder() {
        val frames = (0 until 100).map { index ->
            Step4TestFixtures.ethernetFrame(length = 72, seed = index)
        }
        val blocks = frames.mapIndexed { sequence, frame -> Ntb16Codec.build(frame, sequence) }
        val input = Step4TestFixtures.coalesced(*blocks.toTypedArray())
        assertEquals(10_000, input.size)
        val (bridge, script) = newBridge()
        input.forEach { byte -> script.enqueueRead(UsbRequestWaitFixture.Completed(byteArrayOf(byte))) }

        bridge.use {
            frames.forEach { frame -> assertArrayEquals(frame, it.recv(5_000)) }
            assertEquals(10_000L, it.diagnosticSnapshot().readCompletions)
        }
    }

    @Test
    fun validIncompleteNtbWithCandidateAtProtectedEndWaitsForDeclaredBlock() {
        val originalFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val fakeFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x42)
        val fakeBlock = Ntb16Codec.build(fakeFrame, sequence = 51)
        val originalBlock = Ntb16Codec.build(originalFrame, sequence = 50)
        val protectedEnd = 28 + originalFrame.size
        val declaredLength = 300
        val incompleteNtb = ByteArray(declaredLength).apply {
            originalBlock.copyInto(this)
            writeU16(this, 8, declaredLength)
            fakeBlock.copyInto(this, destinationOffset = protectedEnd)
        }
        for (chunkSize in listOf(1, 2, 64, 512, 32_768)) {
            val (bridge, script) = newBridge()
            incompleteNtb.copyOfRange(0, 200).asList().chunked(chunkSize).forEach { chunk ->
                script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
            }

            bridge.use {
                assertNull(it.recv(10))
                incompleteNtb.copyOfRange(200, declaredLength).asList().chunked(chunkSize).forEach { chunk ->
                    script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
                }
                assertArrayEquals(originalFrame, it.recv(1_000))
                assertNull(it.recv(10))
                assertEquals(1L, it.diagnosticSnapshot().ethernetRxFrames)
            }
        }
    }

    @Test
    fun ambiguousDeclaredLengthNtbWithTwoConsecutiveEmbeddedBlocksFailsClosed() {
        val originalFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val fakeFirstFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x42)
        val fakeSecondFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x53)
        val originalBlock = Ntb16Codec.build(originalFrame, sequence = 50)
        val protectedEnd = 28 + originalFrame.size
        val declaredLength = 320
        val fakeBlocks = listOf(
            Ntb16Codec.build(fakeFirstFrame, sequence = 51),
            Ntb16Codec.build(fakeSecondFrame, sequence = 52),
        )

        for (tailPadding in listOf(0, 1, 4, 8, 16)) {
            val start = protectedEnd + tailPadding
            val incompleteNtb = ByteArray(declaredLength).apply {
                originalBlock.copyInto(this)
                writeU16(this, 8, declaredLength)
                fakeBlocks[0].copyInto(this, destinationOffset = start)
                fakeBlocks[1].copyInto(this, destinationOffset = start + fakeBlocks[0].size)
            }
            for (chunkSize in listOf(1, 2, 64, 512, 32_768)) {
                val (bridge, script) = newBridge()
                incompleteNtb.copyOfRange(0, 264).asList().chunked(chunkSize).forEach { chunk ->
                    script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
                }
                incompleteNtb.copyOfRange(264, declaredLength).asList().chunked(chunkSize).forEach { chunk ->
                    script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
                }
                val error = runCatching { bridge.recv(1_000) }.exceptionOrNull()
                val frames = bridge.diagnosticSnapshot().ethernetRxFrames
                bridge.close()

                assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
                assertEquals(0L, frames)
                assertTrue(script.connectionClosed)
            }
        }
    }

    @Test
    fun plausibleLength1000CannotSilentlyConsumeFollowingValidNtb16Blocks() {
        val damagedBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 70,
        ).copyOf().apply { writeU16(this, 8, 1_000) }
        val followingBlocks = (71..82).map { sequence ->
            Ntb16Codec.build(
                Step4TestFixtures.ethernetFrame(length = 60, seed = sequence),
                sequence = sequence,
            )
        }
        val input = Step4TestFixtures.coalesced(damagedBlock, *followingBlocks.toTypedArray())
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(input))

        val error = runCatching { bridge.recv(100) }.exceptionOrNull()
        val frames = bridge.diagnosticSnapshot().ethernetRxFrames
        bridge.close()

        assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
        assertEquals(0L, frames)
        assertTrue(script.connectionClosed)
    }

    @Test
    fun incompleteDeclaredNtbFailsAfterUsbStopsMakingProgress() {
        val incompleteBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 83,
        ).copyOf(200).apply { writeU16(this, 8, 300) }
        val (bridge, script) = newBridge(incompleteNtbNoProgressTimeoutMillis = 20)
        script.enqueueRead(UsbRequestWaitFixture.Completed(incompleteBlock))

        val error = runCatching { bridge.recv(500) }.exceptionOrNull()
        bridge.close()

        assertTrue(error?.message.orEmpty().contains("NCM_INCOMPLETE_ERROR"))
        assertTrue(script.connectionClosed)
    }

    @Test
    fun incompleteDeclaredNtbProgressResetsNoProgressDeadline() {
        val validBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x32),
            sequence = 84,
        )
        val declaredBlock = ByteArray(300).apply {
            validBlock.copyInto(this)
            writeU16(this, 8, size)
        }
        val (bridge, script) = newBridge(incompleteNtbNoProgressTimeoutMillis = 35)
        script.enqueueRead(UsbRequestWaitFixture.Completed(declaredBlock.copyOfRange(0, 100)))
        script.enqueueRead(UsbRequestWaitFixture.Timeout)

        bridge.use {
            assertNull(it.recv(20))
            Thread.sleep(10)
            script.enqueueRead(UsbRequestWaitFixture.Completed(declaredBlock.copyOfRange(100, 200)))
            script.enqueueRead(UsbRequestWaitFixture.Timeout)
            assertNull(it.recv(20))
            Thread.sleep(10)
            script.enqueueRead(UsbRequestWaitFixture.Completed(declaredBlock.copyOfRange(200, 300)))

            assertArrayEquals(Step4TestFixtures.ethernetFrame(length = 60, seed = 0x32), it.recv(100))
        }
    }

    @Test
    fun oversizedIncompleteNdpLengthRecoversWhenTheFollowingPairSpansReads() {
        val damagedBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 40,
        ).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 16, 65_520)
        }
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val nextBlock = Ntb16Codec.build(nextFrame, sequence = 41)
        val followingBlock = Ntb16Codec.build(followingFrame, sequence = 42)
        val splitOffset = followingBlock.size / 2
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, nextBlock, followingBlock.copyOfRange(0, splitOffset)),
        ))
        script.enqueueRead(UsbRequestWaitFixture.Completed(followingBlock.copyOfRange(splitOffset, followingBlock.size)))

        bridge.use {
            assertArrayEquals(nextFrame, it.recv(100))
            assertArrayEquals(followingFrame, it.recv(100))
            assertEquals(2L, it.diagnosticSnapshot().ethernetRxFrames)
        }
    }

    @Test
    fun maximumLengthNtbWithEightByteTrailingAlignmentFailsClosed() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 50).copyOf().apply {
            writeU16(this, 8, 0xffff)
        }
        val input = Step4TestFixtures.coalesced(
            damagedBlock,
            ByteArray(8),
            Ntb16Codec.build(nextFrame, sequence = 51),
            Ntb16Codec.build(followingFrame, sequence = 52),
        )
        for (chunkSize in listOf(1, 2, 64, 512, 32_768)) {
            val (bridge, script) = newBridge()
            input.asList().chunked(chunkSize).forEach { chunk ->
                script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
            }

            val error = runCatching { bridge.recv(100) }.exceptionOrNull()
            val frames = bridge.diagnosticSnapshot().ethernetRxFrames
            bridge.close()

            assertTrue(error?.message.orEmpty().contains("ambiguous declared-length NTB"))
            assertEquals(0L, frames)
            assertTrue(script.connectionClosed)
        }
    }

    @Test
    fun oversizedDatagramLengthDoesNotHideFollowingValidNtb() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 40).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 22, 60_000)
        }
        val input = Step4TestFixtures.coalesced(
            damagedBlock,
            Ntb16Codec.build(nextFrame, sequence = 41),
            Ntb16Codec.build(followingFrame, sequence = 42),
        )
        for (chunkSize in listOf(1, 2, 64, 512, 32_768)) {
            val (bridge, script) = newBridge()
            input.asList().chunked(chunkSize).forEach { chunk ->
                script.enqueueRead(UsbRequestWaitFixture.Completed(chunk.toByteArray()))
            }

            bridge.use {
                assertArrayEquals(nextFrame, it.recv(100))
                assertArrayEquals(followingFrame, it.recv(100))
                assertEquals(2L, it.diagnosticSnapshot().ethernetRxFrames)
            }
        }
    }

    @Test
    fun oversizedDatagramLengthWaitsForTheFollowingSequencePairAcrossReads() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 40).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 22, 60_000)
        }
        val nextBlock = Ntb16Codec.build(nextFrame, sequence = 41)
        val followingBlock = Ntb16Codec.build(followingFrame, sequence = 42)
        val splitOffset = followingBlock.size / 2
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, nextBlock, followingBlock.copyOfRange(0, splitOffset)),
        ))
        script.enqueueRead(UsbRequestWaitFixture.Completed(followingBlock.copyOfRange(splitOffset, followingBlock.size)))

        bridge.use {
            assertArrayEquals(nextFrame, it.recv(100))
            assertArrayEquals(followingFrame, it.recv(100))
            assertEquals(2L, it.diagnosticSnapshot().ethernetRxFrames)
        }
    }

    @Test
    fun oversizedIncompleteNdpLengthDoesNotHideFollowingConsecutiveBlocks() {
        val damagedBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 40,
        ).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 16, 65_520)
        }
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val input = Step4TestFixtures.coalesced(
            damagedBlock,
            Ntb16Codec.build(nextFrame, sequence = 41),
            Ntb16Codec.build(followingFrame, sequence = 42),
        )
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(input))

        bridge.use {
            assertArrayEquals(nextFrame, it.recv(100))
            assertArrayEquals(followingFrame, it.recv(100))
            assertEquals(2L, it.diagnosticSnapshot().ethernetRxFrames)
        }
    }

    @Test
    fun singleFollowingSequenceCandidateTimesOutWithoutBlockingClose() {
        val damagedBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 40,
        ).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 22, 60_000)
        }
        val onlyFollowingBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42),
            sequence = 41,
        )
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(damagedBlock, onlyFollowingBlock),
        ))
        script.enqueueRead(UsbRequestWaitFixture.Timeout)
        script.enqueueRead(UsbRequestWaitFixture.Timeout)

        assertNull(bridge.recv(100))
        assertNull(bridge.recv(100))
        bridge.close()
        assertTrue(script.connectionClosed)
    }

    @Test
    fun incompleteBogusNtbDoesNotHideLaterCompleteConsecutivePair() {
        val damagedFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val nextFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val followingFrame = Step4TestFixtures.ethernetFrame(length = 80, seed = 0x53)
        val damagedBlock = Ntb16Codec.build(damagedFrame, sequence = 40).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 22, 60_000)
            writeU32(this, 32, Ntb16Codec.NTH16_SIG)
            writeU16(this, 36, 12)
            writeU16(this, 38, 41)
            writeU16(this, 40, 0xffff)
            writeU16(this, 42, 60_000)
        }
        val input = Step4TestFixtures.coalesced(
            damagedBlock,
            Ntb16Codec.build(nextFrame, sequence = 41),
            Ntb16Codec.build(followingFrame, sequence = 42),
        )
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(input))

        bridge.use {
            assertArrayEquals(nextFrame, it.recv(100))
            assertArrayEquals(followingFrame, it.recv(100))
            assertEquals(2L, it.diagnosticSnapshot().ethernetRxFrames)
        }
    }

    @Test
    fun oversizedDatagramBytewiseRecoveryUsesLinearCandidateChecks() {
        val damagedBlock = Ntb16Codec.build(
            Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31),
            sequence = 40,
        ).copyOf().apply {
            writeU16(this, 8, 0xffff)
            writeU16(this, 22, 60_000)
        }
        val input = Step4TestFixtures.coalesced(damagedBlock, ByteArray(8_192) { 0x5a })
        val (bridge, script) = newBridge()
        input.forEach { byte -> script.enqueueRead(UsbRequestWaitFixture.Completed(byteArrayOf(byte))) }

        bridge.use {
            assertNull(it.recv(100))
            assertTrue(it.resyncCandidateChecksForTesting <= input.size.toLong() * 2)
        }
    }

    @Test
    fun unknownNTH32SignatureAndInvalidHeaderLengthRecoverTheFollowingNTB() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x7a)
        val validBlock = Ntb16Codec.build(frame, sequence = 6)
        val unknownNtb32 = ByteArray(32).apply {
            writeU32(this, 0, 0x686d434e)
            writeU16(this, 4, 16)
            writeU16(this, 8, size)
        }
        val invalidHeaderLength = ByteArray(28).apply {
            writeU32(this, 0, Ntb16Codec.NTH16_SIG)
            writeU16(this, 4, 11)
            writeU16(this, 8, 28)
            writeU16(this, 10, 12)
        }
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(
            Step4TestFixtures.coalesced(unknownNtb32, invalidHeaderLength, validBlock),
        ))

        bridge.use { assertArrayEquals(frame, it.recv(100)) }
    }

    @Test
    fun multipleDatagramsInOneNtb16AreDeliveredInOrder() {
        val firstFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x31)
        val secondFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x42)
        val block = multiDatagramNtb16(firstFrame, secondFrame)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(block))

        bridge.use {
            assertArrayEquals(firstFrame, it.recv(100))
            assertArrayEquals(secondFrame, it.recv(100))
        }
    }

    @Test
    fun damagedFrameQueueStillFailsAtItsExistingBound() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val blocks = Array(257) { index -> Ntb16Codec.build(frame, sequence = index) }
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.coalesced(*blocks)))

        val error = runCatching { bridge.recv(100) }.exceptionOrNull()
        bridge.close()

        assertTrue(error is IphoneUsbException.DeviceUnavailable)
        assertTrue(error?.message.orEmpty().contains("frame queue exceeded its bounds"))
    }

    @Test
    fun closeDuringQueuedReadWakesTheReceiverWithoutRequeueing() {
        val (bridge, script) = newBridge()
        val result = AtomicReference<ByteArray?>(null)
        val error = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val reader = Thread({
            try {
                result.set(bridge.recv(60_000))
            } catch (caught: Throwable) {
                error.set(caught)
            } finally {
                finished.countDown()
            }
        })
        reader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (script.lastQueuedBufferCapacity == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(32_768, script.lastQueuedBufferCapacity)

        bridge.close()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        reader.join(2_000)
        assertEquals(null, error.get())
        assertEquals(null, result.get())
    }

    @Test
    fun closeReleasesTheRequestEvenWhenCancelReturnsFalse() {
        val (bridge, script) = newBridge()
        val result = AtomicReference<ByteArray?>(null)
        val error = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        script.cancelResult = false
        val reader = Thread({
            try {
                result.set(bridge.recv(60_000))
            } catch (caught: Throwable) {
                error.set(caught)
            } finally {
                finished.countDown()
            }
        })
        reader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (bridge.diagnosticSnapshot().readQueues == 0L && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(1L, bridge.diagnosticSnapshot().readQueues)

        bridge.close()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        reader.join(2_000)
        assertNull(error.get())
        assertNull(result.get())
        assertEquals(1, script.requestCancelCount)
        assertTrue(script.connectionClosed)
        assertEquals(1, script.requestCloseCount)
    }

    @Test
    fun overOneMiBOfGarbageStopsAtTheBoundedResyncLimit() {
        val (bridge, script) = newBridge()
        val garbageChunk = ByteArray(32_768) { 0x55 }
        repeat((1 shl 20) / garbageChunk.size + 1) {
            script.enqueueRead(UsbRequestWaitFixture.Completed(garbageChunk))
        }

        val error = runCatching { bridge.recv(10_000) }.exceptionOrNull()
        bridge.close()

        assertTrue(error is IphoneUsbException.DeviceUnavailable)
        assertTrue(error?.message.orEmpty().contains("NCM_RESYNC_ERROR"))
    }

    @Test
    fun syncBulkInUsesTheSameOptionalPadAndResyncRules() {
        val frame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val (bridge, script) = newBridge(diagnosticProfile = NcmDiagnosticProfile.SYNC_BULK_IN)
        script.enqueueBulkInData(Step4TestFixtures.unpadded512ByteNtb())

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
            assertEquals(1L, it.diagnosticSnapshot().readCompletions)
        }
    }

    private fun multiDatagramNtb16(firstFrame: ByteArray, secondFrame: ByteArray): ByteArray {
        val ndpOffset = 12
        val ndpLength = 20
        val firstIndex = ndpOffset + ndpLength
        val secondIndex = firstIndex + firstFrame.size
        val blockLength = secondIndex + secondFrame.size
        return ByteArray(blockLength).apply {
            writeU32(this, 0, Ntb16Codec.NTH16_SIG)
            writeU16(this, 4, 12)
            writeU16(this, 6, 0x1234)
            writeU16(this, 8, blockLength)
            writeU16(this, 10, ndpOffset)
            writeU32(this, ndpOffset, Ntb16Codec.NDP16_SIG)
            writeU16(this, ndpOffset + 4, ndpLength)
            writeU16(this, ndpOffset + 6, 0)
            writeU16(this, ndpOffset + 8, firstIndex)
            writeU16(this, ndpOffset + 10, firstFrame.size)
            writeU16(this, ndpOffset + 12, secondIndex)
            writeU16(this, ndpOffset + 14, secondFrame.size)
            writeU16(this, ndpOffset + 16, 0)
            writeU16(this, ndpOffset + 18, 0)
            firstFrame.copyInto(this, firstIndex)
            secondFrame.copyInto(this, secondIndex)
        }
    }

    @Test
    fun timeoutKeepsThePersistentRequestQueuedForItsNextCompletion() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val block = Ntb16Codec.build(frame, sequence = 11)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.TimeoutAfter(delayMillis = 300L))
        script.enqueueRead(UsbRequestWaitFixture.Completed(block))

        bridge.use {
            assertNull(it.recv(200))
            val timedOut = it.diagnosticSnapshot()
            assertEquals(PersistentUsbReadRequestState.State.QUEUED, timedOut.readState)
            assertEquals(1L, timedOut.readQueues)
            assertArrayEquals(frame, it.recv(100))
            assertEquals(1L, it.diagnosticSnapshot().readQueues)
        }
    }

    @Test
    fun queueFalseNullAndUnexpectedCompletionRemainDistinctFixtureOutcomes() {
        val queueFailed = newBridge()
        queueFailed.second.enqueueQueueResult(false)
        val queueError = runCatching { queueFailed.first.recv(2) }.exceptionOrNull()
        assertTrue(queueError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, queueFailed.first.diagnosticSnapshot().readQueueFailures)
        queueFailed.first.close()

        val nullWait = newBridge()
        nullWait.second.enqueueRead(UsbRequestWaitFixture.NullResult)
        val nullError = runCatching { nullWait.first.recv(2) }.exceptionOrNull()
        assertTrue(nullError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, nullWait.first.diagnosticSnapshot().readNullErrors)
        nullWait.first.close()

        val unexpected = newBridge()
        unexpected.second.enqueueRead(UsbRequestWaitFixture.UnexpectedCompletion)
        val unexpectedError = runCatching { unexpected.first.recv(2) }.exceptionOrNull()
        assertTrue(unexpectedError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, unexpected.first.diagnosticSnapshot().readUnexpectedCompletions)
        unexpected.first.close()
    }

    @Test
    fun fakeCapturesTheExistingThirtyTwoKiBReadQueueContract() {
        val (bridge, script) = newBridge()
        script.enqueueQueueResult(false)

        bridge.use {
            val error = runCatching { it.recv(2) }.exceptionOrNull()
            assertTrue(error is IphoneUsbException.DeviceUnavailable)
            assertEquals(32_768, script.lastQueuedBufferCapacity)
            assertEquals(1L, it.diagnosticSnapshot().readQueueFailures)
        }
    }

    @Test
    fun fakeBulkOutDistinguishesNotReadySentAndPartialFailure() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val (bridge, script) = newBridge()
        script.enqueueBulkOutResult(0)

        bridge.use {
            assertEquals(NcmSendResult.NotReady, it.send(frame, timeoutMillis = 100))
            assertEquals(NcmSendResult.Sent, it.send(frame, timeoutMillis = 100))
            assertEquals(1L, it.diagnosticSnapshot().writeNotReady)
            assertEquals(1L, it.diagnosticSnapshot().writeSuccesses)
            assertArrayEquals(frame, Ntb16Codec.parse(script.outgoingBlocks.single()).single())
        }

        val partialTiming = WiredStartupTiming(attemptId = 3L)
        val partialWrite = newBridge(wiredStartupTiming = partialTiming)
        partialWrite.second.enqueueBulkOutResult(1)
        val result = partialWrite.first.send(frame, timeoutMillis = 100)
        assertTrue(result is NcmSendResult.Failed)
        assertEquals(1L, partialWrite.first.diagnosticSnapshot().writePartialFailures)
        assertEquals(null, partialTiming.snapshot().firstFullBulkOutNs)
        partialWrite.first.close()
    }

    @Test
    fun bulkOutShortPacketPaddingUsesTheEndpointPacketSize() {
        val frame = Step4TestFixtures.ethernetFrame(length = 100)
        val (bridge, script) = newBridge(bulkOutMaxPacketSize = 64)

        bridge.use {
            assertEquals(NcmSendResult.Sent, it.send(frame, timeoutMillis = 100))
        }

        val written = script.outgoingBlocks.single()
        assertEquals(129, written.size)
        assertEquals(128, (written[8].toInt() and 0xff) or ((written[9].toInt() and 0xff) shl 8))
        assertArrayEquals(frame, Ntb16Codec.parse(written).single())
    }

    @Test
    fun wiredPathDiagnosticsDoNotChangeBulkOutResults() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val disabled = WiredPathDiagnostics("disabled", 1, enabled = false)
        val enabled = WiredPathDiagnostics("run-fixture", 1, enabled = true)
        val alwaysOnTiming = WiredStartupTiming(attemptId = 2L)
        val (withoutDiagnostics, disabledScript) = newBridge(disabled, wiredStartupTiming = alwaysOnTiming)
        val (withDiagnostics, enabledScript) = newBridge(enabled)
        withoutDiagnostics.setWiredPathAttachGeneration(4L)
        disabledScript.enqueueBulkOutResult(0)
        enabledScript.enqueueBulkOutResult(0)

        val disabledResults = listOf(
            withoutDiagnostics.send(frame, timeoutMillis = 100),
            withoutDiagnostics.send(frame, timeoutMillis = 100),
        )
        val enabledResults = listOf(
            withDiagnostics.send(frame, timeoutMillis = 100),
            withDiagnostics.send(frame, timeoutMillis = 100),
        )
        withoutDiagnostics.close()
        withDiagnostics.close()

        assertEquals(disabledResults, enabledResults)
        assertEquals(1, disabledScript.outgoingBlocks.size)
        assertEquals(1, enabledScript.outgoingBlocks.size)
        assertEquals(0L, disabled.snapshot(WiredPathReportReason.FINAL)
            .counter(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT).count)
        assertTrue(alwaysOnTiming.snapshot().firstFullBulkOutNs != null)
        assertEquals(1L, enabled.snapshot(WiredPathReportReason.FINAL)
            .counter(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT).count)
    }

    private fun newBridge(
        wiredPathDiagnostics: WiredPathDiagnostics? = null,
        wiredStartupTiming: WiredStartupTiming? = null,
        diagnosticProfile: NcmDiagnosticProfile = NcmDiagnosticProfile.AUTO,
        bulkInMaxPacketSize: Int = 512,
        bulkOutMaxPacketSize: Int = 512,
        incompleteNtbNoProgressTimeoutMillis: Long = 30_000,
    ): Pair<NcmUsbBridge, ScriptedUsbDeviceConnectionShadow> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
        val script = Shadow.extract<ScriptedUsbDeviceConnectionShadow>(connection)
        val input = endpoint(address = 0x81, maxPacketSize = bulkInMaxPacketSize, direction = 0x80)
        val output = endpoint(address = 0x02, maxPacketSize = bulkOutMaxPacketSize, direction = 0x00)
        val bridge = NcmUsbBridge(
            connection = connection,
            outEndpoint = output,
            inEndpoint = input,
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "fixture",
            onDiagnosticEvent = {},
            diagnosticProfile = diagnosticProfile,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
            wiredPathDiagnostics = wiredPathDiagnostics,
            wiredStartupTiming = wiredStartupTiming,
            incompleteNtbNoProgressTimeoutMillis = incompleteNtbNoProgressTimeoutMillis,
        )
        return bridge to script
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun endpoint(address: Int, maxPacketSize: Int, direction: Int): UsbEndpoint =
        ReflectionHelpers.callConstructor(UsbEndpoint::class.java).also { endpoint ->
            Shadow.extract<ScriptedUsbEndpointShadow>(endpoint).apply {
                endpointAddress = address
                endpointMaxPacketSize = maxPacketSize
                endpointDirection = direction
            }
        }
}

@Implements(UsbEndpoint::class)
internal class ScriptedUsbEndpointShadow {
    var endpointAddress: Int = 0
    var endpointMaxPacketSize: Int = 0
    var endpointDirection: Int = 0

    @Implementation
    protected fun getAddress(): Int = endpointAddress

    @Implementation
    protected fun getMaxPacketSize(): Int = endpointMaxPacketSize

    @Implementation
    protected fun getDirection(): Int = endpointDirection
}

@Implements(UsbDeviceConnection::class)
internal class ScriptedUsbDeviceConnectionShadow {
    private val waitOutcomes = ArrayDeque<UsbRequestWaitFixture>()
    private val queueResults = ArrayDeque<Boolean>()
    private val bulkOutResults = ArrayDeque<Int>()
    private val bulkInResults = ArrayDeque<ByteArray>()
    private var queuedRequest: UsbRequest? = null
    private var queuedBuffer: ByteBuffer? = null
    var connectionClosed: Boolean = false
        private set
    var requestCloseCount: Int = 0
        private set
    var requestCancelCount: Int = 0
        private set
    var cancelResult: Boolean = true
    var lastQueuedBufferCapacity: Int = 0
        private set
    val outgoingBlocks = ArrayList<ByteArray>()

    fun enqueueRead(outcome: UsbRequestWaitFixture) {
        waitOutcomes.addLast(outcome)
    }

    fun enqueueQueueResult(result: Boolean) {
        queueResults.addLast(result)
    }

    fun enqueueBulkOutResult(result: Int) {
        bulkOutResults.addLast(result)
    }

    fun enqueueBulkInData(bytes: ByteArray) {
        bulkInResults.addLast(bytes)
    }

    internal fun queueRequest(request: UsbRequest, buffer: ByteBuffer): Boolean {
        lastQueuedBufferCapacity = buffer.capacity()
        val result = if (queueResults.isEmpty()) true else queueResults.removeFirst()
        if (result) {
            queuedRequest = request
            queuedBuffer = buffer
        }
        return result
    }

    internal fun deliverRead(bytes: ByteArray) {
        val buffer = requireNotNull(queuedBuffer) { "No queued USB read buffer" }
        buffer.put(bytes)
    }

    internal fun recordRequestClose() {
        requestCloseCount++
    }

    internal fun cancelRequest(): Boolean {
        requestCancelCount++
        return cancelResult
    }

    @Implementation
    protected fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int {
        if (endpoint.direction == 0x80) {
            val bytes = if (bulkInResults.isEmpty()) return 0 else bulkInResults.removeFirst()
            val transferred = minOf(length, bytes.size)
            bytes.copyInto(buffer, 0, 0, transferred)
            return transferred
        }
        val result = if (bulkOutResults.isEmpty()) length else bulkOutResults.removeFirst()
        if (endpoint.direction == 0x00 && result > 0) {
            outgoingBlocks += buffer.copyOf(minOf(length, result))
        }
        return result
    }

    @Implementation
    protected fun requestWait(timeoutMillis: Long): UsbRequest? {
        val outcome = if (waitOutcomes.isEmpty()) UsbRequestWaitFixture.Timeout else waitOutcomes.removeFirst()
        return when (outcome) {
            is UsbRequestWaitFixture.Completed -> {
                deliverRead(outcome.bytes)
                queuedRequest
            }
            UsbRequestWaitFixture.Timeout -> {
                Thread.sleep(timeoutMillis.coerceAtLeast(1L).coerceAtMost(2L))
                throw TimeoutException("fixture timeout")
            }
            is UsbRequestWaitFixture.TimeoutAfter -> {
                Thread.sleep(outcome.delayMillis.coerceAtLeast(1L))
                throw TimeoutException("fixture timeout after ${outcome.delayMillis}ms")
            }
            UsbRequestWaitFixture.NullResult -> null
            UsbRequestWaitFixture.UnexpectedCompletion -> ReflectionHelpers.callConstructor(UsbRequest::class.java)
        }
    }

    @Implementation
    protected fun close() {
        connectionClosed = true
    }
}

@Implements(UsbRequest::class)
internal class ScriptedUsbRequestShadow {
    @field:RealObject
    lateinit var request: UsbRequest

    private var initializedConnection: UsbDeviceConnection? = null

    @Implementation
    protected fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        initializedConnection = connection
        return true
    }

    @Implementation
    protected fun queue(buffer: ByteBuffer): Boolean =
        initializedConnection?.let { Shadow.extract<ScriptedUsbDeviceConnectionShadow>(it).queueRequest(request, buffer) }
            ?: false

    @Implementation
    protected fun cancel(): Boolean =
        initializedConnection?.let { Shadow.extract<ScriptedUsbDeviceConnectionShadow>(it).cancelRequest() } ?: false

    @Implementation
    protected fun close() {
        initializedConnection?.let { Shadow.extract<ScriptedUsbDeviceConnectionShadow>(it).recordRequestClose() }
    }
}
