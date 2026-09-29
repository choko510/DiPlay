package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** Finds CDC-NCM control/data pairs from the raw CDC function descriptors. */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val DATA_ALTERNATE_SETTING = 1

    private const val INTERFACE_DESCRIPTOR = 0x04
    private const val CONFIGURATION_DESCRIPTOR = 0x02
    private const val CDC_FUNCTIONAL_DESCRIPTOR = 0x24
    private const val CDC_UNION_SUBTYPE = 0x06
    private const val CDC_ETHERNET_SUBTYPE = 0x0f
    private const val CDC_NCM_SUBTYPE = 0x1a

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        val hasUnion: Boolean,
        val hasEthernetDescriptor: Boolean,
        val hasNcmDescriptor: Boolean,
        val ncmNetworkCapabilities: Int?,
        val selectionReason: String,
    )

    data class DiscoveryResult(
        val selected: NcmFunction?,
        val candidateSummaries: List<String>,
        val selectionReason: String,
        val descriptorParseError: Boolean,
    ) {
        fun diagnosticSummary(configuration: UsbConfiguration): String = buildString {
            append("config=").append(configuration.id)
            append(" interfaces=").append((0 until configuration.interfaceCount).joinToString(",") {
                configuration.getInterface(it).let { intf -> "${intf.id}/${intf.alternateSetting}" }
            })
            candidateSummaries.forEachIndexed { index, candidate ->
                append(" candidate[").append(index).append("]{").append(candidate).append("}")
            }
            append(" selected=")
            selected?.let { append("${it.control.id}->${it.data.id}/${it.data.alternateSetting}") }
                ?: append("none")
            append(" reason=").append(selectionReason)
            append(" descriptorParseError=").append(descriptorParseError)
        }
    }

    internal data class CdcUnionDescriptor(
        val masterInterface: Int,
        val slaveInterfaces: List<Int>,
    )

    internal data class CdcNcmDescriptor(
        val interfaceNumber: Int,
        val networkCapabilities: Int,
    )

    internal data class FunctionalDescriptors(
        val unions: List<CdcUnionDescriptor> = emptyList(),
        val ethernetInterfaceNumbers: Set<Int> = emptySet(),
        val ncmDescriptors: List<CdcNcmDescriptor> = emptyList(),
        val malformed: Boolean = false,
    )

    internal data class InterfaceRecord(
        val id: Int,
        val alternateSetting: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val hasStatusIn: Boolean,
        val hasBulkIn: Boolean,
        val hasBulkOut: Boolean,
    )

    internal data class PairCandidate(
        val controlId: Int,
        val controlAlternateSetting: Int,
        val dataId: Int,
        val dataAlternateSetting: Int,
        val hasUnion: Boolean,
        val hasEthernetDescriptor: Boolean,
        val hasNcmDescriptor: Boolean,
        val ncmNetworkCapabilities: Int?,
        val hasStatusIn: Boolean,
        val hasBulkIn: Boolean,
        val hasBulkOut: Boolean,
        val eligible: Boolean = true,
        val reason: String,
        val evidenceScore: Int,
    ) {
        fun diagnostic(): String =
            "control=$controlId/$controlAlternateSetting data=$dataId/$dataAlternateSetting " +
                "statusIn=$hasStatusIn bulkIn=$hasBulkIn bulkOut=$hasBulkOut hasUnion=$hasUnion " +
                "hasEthernetDescriptor=$hasEthernetDescriptor hasNcmDescriptor=$hasNcmDescriptor " +
                "ncmNetworkCapabilities=${ncmNetworkCapabilities?.let { "0x${it.toString(16)}" } ?: "unknown"} " +
                "eligible=$eligible score=$evidenceScore reason=$reason"
    }

    fun find(configuration: UsbConfiguration): NcmFunction? =
        discover(configuration, ByteArray(0)).selected

    fun discover(configuration: UsbConfiguration, rawDescriptors: ByteArray): DiscoveryResult {
        val interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface)
        val parsed = parseFunctionalDescriptors(rawDescriptors, configuration.id)
        val records = interfaces.map(::record)
        val candidateRecords = pairCandidates(records, parsed)
        val usable = candidateRecords.filter { it.eligible && hasBulkPair(interfaces, it) }
        val selectedRecord = selectCandidate(usable)
        val tiedOnEvidence = selectedRecord != null &&
            usable.count { it.evidenceScore == selectedRecord.evidenceScore } > 1
        val selected = selectedRecord?.let { candidate ->
            val control = interfaces.firstOrNull {
                it.id == candidate.controlId && it.alternateSetting == candidate.controlAlternateSetting
            }
            val data = interfaces.firstOrNull {
                it.id == candidate.dataId && it.alternateSetting == candidate.dataAlternateSetting
            }
            val endpoints = data?.let(::bulkEndpoints)
            if (control == null || data == null || endpoints == null) {
                null
            } else {
                NcmFunction(
                    control = control,
                    data = data,
                    statusIn = endpoint(control, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT),
                    bulkIn = endpoints.first,
                    bulkOut = endpoints.second,
                    hasUnion = candidate.hasUnion,
                    hasEthernetDescriptor = candidate.hasEthernetDescriptor,
                    hasNcmDescriptor = candidate.hasNcmDescriptor,
                    ncmNetworkCapabilities = candidate.ncmNetworkCapabilities,
                    selectionReason = if (tiedOnEvidence) {
                        "${candidate.reason}; equal evidence resolved by USB descriptor order"
                    } else {
                        candidate.reason
                    },
                )
            }
        }
        val decision = when {
            selected == null && parsed.malformed -> "no valid pair; raw CDC descriptors were malformed"
            selected == null -> "no unambiguous CDC-NCM control/data pair"
            else -> selected.selectionReason
        }
        return DiscoveryResult(
            selected = selected,
            candidateSummaries = candidateRecords.map(PairCandidate::diagnostic),
            selectionReason = decision,
            descriptorParseError = parsed.malformed,
        )
    }

    internal fun parseFunctionalDescriptors(
        rawDescriptors: ByteArray,
        configurationId: Int? = null,
    ): FunctionalDescriptors {
        if (rawDescriptors.isEmpty()) return FunctionalDescriptors()
        val unions = mutableListOf<CdcUnionDescriptor>()
        val ethernetInterfaces = linkedSetOf<Int>()
        val ncm = mutableListOf<CdcNcmDescriptor>()
        var malformed = false
        var currentConfiguration: Int? = null
        var currentInterface: Int? = null
        var sawConfigurationDescriptor = false
        var offset = 0
        while (offset + 2 <= rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xff
            val type = rawDescriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > rawDescriptors.size) {
                malformed = true
                break
            }
            when (type) {
                CONFIGURATION_DESCRIPTOR -> {
                    sawConfigurationDescriptor = true
                    currentConfiguration = if (length >= 6) u8(rawDescriptors, offset + 5) else null
                    currentInterface = null
                }
                INTERFACE_DESCRIPTOR -> {
                    currentInterface = if (length >= 9) u8(rawDescriptors, offset + 2) else null
                    if (length < 9) malformed = true
                }
                CDC_FUNCTIONAL_DESCRIPTOR -> {
                    val configurationMatches = configurationId == null || !sawConfigurationDescriptor ||
                        currentConfiguration == configurationId
                    val interfaceNumber = currentInterface
                    if (configurationMatches && interfaceNumber != null && length >= 3) {
                        when (u8(rawDescriptors, offset + 2)) {
                            CDC_UNION_SUBTYPE -> {
                                if (length >= 5) {
                                    unions += CdcUnionDescriptor(
                                        masterInterface = u8(rawDescriptors, offset + 3),
                                        slaveInterfaces = (offset + 4 until offset + length).map {
                                            u8(rawDescriptors, it)
                                        },
                                    )
                                } else {
                                    malformed = true
                                }
                            }
                            CDC_ETHERNET_SUBTYPE -> {
                                if (length >= 13) ethernetInterfaces += interfaceNumber else malformed = true
                            }
                            CDC_NCM_SUBTYPE -> {
                                if (length >= 6) {
                                    ncm += CdcNcmDescriptor(interfaceNumber, u8(rawDescriptors, offset + 5))
                                } else {
                                    malformed = true
                                }
                            }
                        }
                    }
                }
            }
            offset += length
        }
        if (offset != rawDescriptors.size) malformed = true
        return FunctionalDescriptors(unions, ethernetInterfaces, ncm, malformed)
    }

    internal fun pairCandidates(
        interfaces: List<InterfaceRecord>,
        descriptors: FunctionalDescriptors,
    ): List<PairCandidate> {
        val controls = interfaces.filter {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        }.groupBy(InterfaceRecord::id).mapValues { (_, alternatives) ->
            alternatives.minByOrNull { if (it.alternateSetting == 0) 0 else 1 }!!
        }
        val dataInterfaces = interfaces.filter {
            it.interfaceClass == DATA_CLASS && it.hasBulkIn && it.hasBulkOut
        }.groupBy(InterfaceRecord::id).mapValues { (_, alternatives) ->
            alternatives.minByOrNull { if (it.alternateSetting == DATA_ALTERNATE_SETTING) 0 else 1 }!!
        }
        val interfacesById = interfaces.groupBy(InterfaceRecord::id).mapValues { (_, alternatives) ->
            alternatives.minByOrNull {
                when {
                    it.interfaceClass == DATA_CLASS && it.hasBulkIn && it.hasBulkOut &&
                        it.alternateSetting == DATA_ALTERNATE_SETTING -> 0
                    it.interfaceClass == DATA_CLASS && it.hasBulkIn && it.hasBulkOut -> 1
                    it.interfaceClass == DATA_CLASS -> 2
                    else -> 3
                }
            }!!
        }

        val unionCandidates = buildList {
            controls.forEach { (controlId, control) ->
                descriptors.unions.filter { it.masterInterface == controlId }.forEach { union ->
                    union.slaveInterfaces.forEach slaveLoop@{ dataId ->
                        val data = interfacesById[dataId]
                        if (data == null) {
                            add(missingDataCandidate(control, dataId, descriptors))
                        } else if (data.interfaceClass != DATA_CLASS || !data.hasBulkIn || !data.hasBulkOut) {
                            add(
                                candidate(
                                    control,
                                    data,
                                    descriptors,
                                    hasUnion = true,
                                    reason = "CDC Union target lacks a CDC Data bulk IN/OUT pair",
                                    eligible = false,
                                ),
                            )
                        } else {
                            add(candidate(control, data, descriptors, hasUnion = true, reason = "CDC Union pairs control and data interfaces"))
                        }
                    }
                }
            }
        }
        if (unionCandidates.isNotEmpty() || descriptors.unions.any { it.masterInterface in controls }) {
            return unionCandidates
        }

        if (controls.size != 1 || dataInterfaces.size != 1) {
            return buildList {
                controls.values.forEach { control ->
                    dataInterfaces.values.forEach { data ->
                        add(
                            candidate(
                                control,
                                data,
                                descriptors,
                                hasUnion = false,
                                reason = "no CDC Union; ambiguous fallback was rejected",
                                eligible = false,
                            ),
                        )
                    }
                }
            }
        }
        return listOf(
            candidate(
                controls.values.single(),
                dataInterfaces.values.single(),
                descriptors,
                hasUnion = false,
                reason = "explicit fallback: one NCM control and one bulk data interface",
            ),
        )
    }

    internal fun selectCandidate(candidates: List<PairCandidate>): PairCandidate? =
        candidates.filter(PairCandidate::eligible).maxByOrNull(PairCandidate::evidenceScore)

    private fun candidate(
        control: InterfaceRecord,
        data: InterfaceRecord,
        descriptors: FunctionalDescriptors,
        hasUnion: Boolean,
        reason: String,
        eligible: Boolean = true,
    ): PairCandidate {
        val hasEthernetDescriptor = control.id in descriptors.ethernetInterfaceNumbers
        val ncmDescriptor = descriptors.ncmDescriptors.firstOrNull { it.interfaceNumber == control.id }
        val hasNcmDescriptor = ncmDescriptor != null
        val score = (if (hasUnion) 100 else 0) +
            (if (hasNcmDescriptor) 20 else 0) +
            (if (hasEthernetDescriptor) 10 else 0) +
            (if (control.hasStatusIn) 5 else 0) +
            (if (data.alternateSetting == DATA_ALTERNATE_SETTING) 2 else 0)
        return PairCandidate(
            controlId = control.id,
            controlAlternateSetting = control.alternateSetting,
            dataId = data.id,
            dataAlternateSetting = data.alternateSetting,
            hasUnion = hasUnion,
            hasEthernetDescriptor = hasEthernetDescriptor,
            hasNcmDescriptor = hasNcmDescriptor,
            ncmNetworkCapabilities = ncmDescriptor?.networkCapabilities,
            hasStatusIn = control.hasStatusIn,
            hasBulkIn = data.hasBulkIn,
            hasBulkOut = data.hasBulkOut,
            eligible = eligible,
            reason = reason,
            evidenceScore = score,
        )
    }

    private fun missingDataCandidate(
        control: InterfaceRecord,
        dataId: Int,
        descriptors: FunctionalDescriptors,
    ): PairCandidate {
        val ncmDescriptor = descriptors.ncmDescriptors.firstOrNull { it.interfaceNumber == control.id }
        val hasEthernetDescriptor = control.id in descriptors.ethernetInterfaceNumbers
        val score = 100 + (if (ncmDescriptor != null) 20 else 0) +
            (if (hasEthernetDescriptor) 10 else 0) + (if (control.hasStatusIn) 5 else 0)
        return PairCandidate(
            controlId = control.id,
            controlAlternateSetting = control.alternateSetting,
            dataId = dataId,
            dataAlternateSetting = -1,
            hasUnion = true,
            hasEthernetDescriptor = hasEthernetDescriptor,
            hasNcmDescriptor = ncmDescriptor != null,
            ncmNetworkCapabilities = ncmDescriptor?.networkCapabilities,
            hasStatusIn = control.hasStatusIn,
            hasBulkIn = false,
            hasBulkOut = false,
            eligible = false,
            reason = "CDC Union references a missing interface",
            evidenceScore = score,
        )
    }

    private fun record(usbInterface: UsbInterface): InterfaceRecord = InterfaceRecord(
        id = usbInterface.id,
        alternateSetting = usbInterface.alternateSetting,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        hasStatusIn = endpoint(usbInterface, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT) != null,
        hasBulkIn = endpoint(usbInterface, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK) != null,
        hasBulkOut = endpoint(usbInterface, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK) != null,
    )

    private fun hasBulkPair(interfaces: List<UsbInterface>, candidate: PairCandidate): Boolean =
        interfaces.any {
            it.id == candidate.dataId && it.alternateSetting == candidate.dataAlternateSetting &&
                bulkEndpoints(it) != null
        }

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val input = endpoint(usbInterface, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK)
        val output = endpoint(usbInterface, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK)
        return if (input != null && output != null) input to output else null
    }

    private fun endpoint(usbInterface: UsbInterface, direction: Int, type: Int): UsbEndpoint? =
        (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).singleOrNull {
            it.direction == direction && it.type == type
        }

    private fun u8(source: ByteArray, offset: Int): Int = source[offset].toInt() and 0xff
}
