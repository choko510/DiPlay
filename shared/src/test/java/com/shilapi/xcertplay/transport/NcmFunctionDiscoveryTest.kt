package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmFunctionDiscoveryTest {
    @Test
    fun unionDescriptorPairsControlThreeWithDataFourAndCapturesFunctionalEvidence() {
        val parsed = NcmFunctionDiscovery.parseFunctionalDescriptors(
            rawDescriptors(
                interfaceDescriptor(3, 0, 2, 0x0d, 0),
                bytes(5, 0x24, 0x06, 3, 4),
                bytes(13, 0x24, 0x0f, 1, 0, 0, 0, 0, 0xdc, 0x05, 0, 0, 0),
                bytes(6, 0x24, 0x1a, 0x10, 0x01, 0x21),
            ),
            configurationId = 4,
        )
        val candidates = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(control(3), data(4)),
            descriptors = parsed,
        )

        assertEquals(1, candidates.size)
        assertEquals(3, candidates.single().controlId)
        assertEquals(4, candidates.single().dataId)
        assertTrue(candidates.single().hasUnion)
        assertTrue(candidates.single().hasEthernetDescriptor)
        assertTrue(candidates.single().hasNcmDescriptor)
        assertEquals(0x21, candidates.single().ncmNetworkCapabilities)
    }

    @Test
    fun twoNcmPairsRemainPairedByUnionWhenInterfaceOrderIsReversed() {
        val parsed = NcmFunctionDiscovery.parseFunctionalDescriptors(
            rawDescriptors(
                interfaceDescriptor(3, 0, 2, 0x0d, 0),
                bytes(5, 0x24, 0x06, 3, 4),
                interfaceDescriptor(5, 0, 2, 0x0d, 0),
                bytes(5, 0x24, 0x06, 5, 6),
            ),
            configurationId = 4,
        )
        val candidates = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(control(3), data(6), control(5), data(4)),
            descriptors = parsed,
        )

        assertEquals(setOf(3 to 4, 5 to 6), candidates.map { it.controlId to it.dataId }.toSet())
        assertTrue(candidates.all { it.hasUnion && it.eligible })
        assertEquals(4, NcmFunctionDiscovery.selectCandidate(candidates)?.dataId)
        assertEquals(5, NcmFunctionDiscovery.selectCandidate(candidates, 5 to 6)?.controlId)
        assertEquals(6, NcmFunctionDiscovery.selectCandidate(candidates, 5 to 6)?.dataId)
        assertEquals(null, NcmFunctionDiscovery.selectCandidate(candidates, 7 to 8))
    }

    @Test
    fun noUnionFallbackIsAllowedOnlyForOneControlAndOneDataInterface() {
        val single = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(control(3), data(4)),
            descriptors = NcmFunctionDiscovery.FunctionalDescriptors(),
        )
        val ambiguous = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(control(3), data(4), data(6)),
            descriptors = NcmFunctionDiscovery.FunctionalDescriptors(),
        )

        assertEquals(1, single.size)
        assertFalse(single.single().hasUnion)
        assertTrue(single.single().eligible)
        assertTrue(single.single().reason.contains("fallback"))
        assertEquals(2, ambiguous.size)
        assertTrue(ambiguous.none { it.eligible })
    }

    @Test
    fun unionToMissingDataDoesNotFallBackToAUnrelatedBulkInterface() {
        val parsed = NcmFunctionDiscovery.FunctionalDescriptors(
            unions = listOf(NcmFunctionDiscovery.CdcUnionDescriptor(3, listOf(9))),
        )

        val candidates = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(control(3), data(4)),
            descriptors = parsed,
        )

        assertEquals(1, candidates.size)
        assertFalse(candidates.single().eligible)
        assertTrue(candidates.single().reason.contains("missing"))
    }

    @Test
    fun invalidUnionDescriptorIsReportedAsMalformed() {
        val parsed = NcmFunctionDiscovery.parseFunctionalDescriptors(
            rawDescriptors(
                interfaceDescriptor(3, 0, 2, 0x0d, 0),
                bytes(3, 0x24, 0x06),
            ),
            configurationId = 4,
        )

        assertTrue(parsed.malformed)
        assertTrue(parsed.unions.isEmpty())
    }

    private fun control(id: Int) = NcmFunctionDiscovery.InterfaceRecord(
        id = id,
        alternateSetting = 0,
        interfaceClass = 2,
        interfaceSubclass = 0x0d,
        interfaceProtocol = 0,
        hasStatusIn = true,
        hasBulkIn = false,
        hasBulkOut = false,
    )

    private fun data(id: Int) = NcmFunctionDiscovery.InterfaceRecord(
        id = id,
        alternateSetting = 1,
        interfaceClass = 0x0a,
        interfaceSubclass = 0,
        interfaceProtocol = 0,
        hasStatusIn = false,
        hasBulkIn = true,
        hasBulkOut = true,
    )

    private fun rawDescriptors(vararg descriptors: ByteArray): ByteArray =
        (bytes(9, 2, 0, 0, 0, 4, 0, 0x80, 50) + descriptors.fold(ByteArray(0)) { acc, next -> acc + next })

    private fun interfaceDescriptor(id: Int, alternate: Int, clazz: Int, subclass: Int, protocol: Int): ByteArray =
        bytes(9, 4, id, alternate, 1, clazz, subclass, protocol, 0)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
