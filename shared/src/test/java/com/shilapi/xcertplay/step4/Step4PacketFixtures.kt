package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.Ntb16Codec

internal object Step4PacketFixtures {
    fun ntb16(
        frames: List<ByteArray>,
        sequence: Int = 0,
        shortPad: Boolean = false,
    ): ByteArray {
        require(frames.isNotEmpty())
        require(sequence in 0..0xffff)
        require(frames.all { it.isNotEmpty() })

        val ndpLength = 8 + (frames.size + 1) * 4
        val firstDatagram = 12 + ndpLength
        val datagramOffsets = ArrayList<Int>(frames.size)
        var blockLength = firstDatagram
        for (frame in frames) {
            blockLength = (blockLength + 3) and -4
            datagramOffsets.add(blockLength)
            blockLength += frame.size
        }
        require(blockLength <= 0xffff)
        require(!shortPad || blockLength % 512 == 0)

        val block = ByteArray(blockLength + if (shortPad) 1 else 0)
        writeU32Le(block, 0, Ntb16Codec.NTH16_SIG)
        writeU16Le(block, 4, 12)
        writeU16Le(block, 6, sequence)
        writeU16Le(block, 8, blockLength)
        writeU16Le(block, 10, 12)
        writeU32Le(block, 12, Ntb16Codec.NDP16_SIG)
        writeU16Le(block, 16, ndpLength)

        var datagramOffset = firstDatagram
        for ((index, frame) in frames.withIndex()) {
            val entryOffset = 20 + index * 4
            val datagramOffset = datagramOffsets[index]
            writeU16Le(block, entryOffset, datagramOffset)
            writeU16Le(block, entryOffset + 2, frame.size)
            frame.copyInto(block, datagramOffset)
        }
        return block
    }

    fun ntb16(frame: ByteArray, sequence: Int = 0, shortPad: Boolean = false): ByteArray =
        ntb16(listOf(frame), sequence, shortPad)

    fun ethernetIpv4Tcp(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        flags: Int = 0x02,
        sourceAddress: ByteArray = byteArrayOf(192.toByte(), 0, 2, 1),
        destinationAddress: ByteArray = byteArrayOf(192.toByte(), 0, 2, 2),
    ): ByteArray {
        require(sourceAddress.size == 4 && destinationAddress.size == 4)
        val tcp = tcpHeader(sourcePort, destinationPort, flags)
        val packet = ByteArray(14 + 20 + tcp.size)
        ethernetHeader(packet, 0x0800)
        val ip = 14
        packet[ip] = 0x45
        writeU16Be(packet, ip + 2, 20 + tcp.size)
        packet[ip + 8] = 64
        packet[ip + 9] = 6
        sourceAddress.copyInto(packet, ip + 12)
        destinationAddress.copyInto(packet, ip + 16)
        tcp.copyInto(packet, ip + 20)
        return packet
    }

    fun ethernetIpv6Tcp(
        sourcePort: Int = 49_152,
        destinationPort: Int = 7_000,
        flags: Int = 0x02,
        sourceAddress: ByteArray = ipv6Address(1),
        destinationAddress: ByteArray = ipv6Address(2),
    ): ByteArray {
        require(sourceAddress.size == 16 && destinationAddress.size == 16)
        val tcp = tcpHeader(sourcePort, destinationPort, flags)
        val packet = ByteArray(14 + 40 + tcp.size)
        ethernetHeader(packet, 0x86dd)
        val ip = 14
        packet[ip] = 0x60
        writeU16Be(packet, ip + 4, tcp.size)
        packet[ip + 6] = 6
        packet[ip + 7] = 64
        sourceAddress.copyInto(packet, ip + 8)
        destinationAddress.copyInto(packet, ip + 24)
        tcp.copyInto(packet, ip + 40)
        return packet
    }

    private fun ethernetHeader(target: ByteArray, etherType: Int) {
        byteArrayOf(0x02, 0, 0, 0, 0, 2).copyInto(target, 0)
        byteArrayOf(0x02, 0, 0, 0, 0, 1).copyInto(target, 6)
        writeU16Be(target, 12, etherType)
    }

    private fun tcpHeader(sourcePort: Int, destinationPort: Int, flags: Int): ByteArray =
        ByteArray(20).also {
            writeU16Be(it, 0, sourcePort)
            writeU16Be(it, 2, destinationPort)
            it[12] = 0x50
            it[13] = flags.toByte()
        }

    private fun ipv6Address(lastByte: Int): ByteArray = ByteArray(16).also {
        it[0] = 0x20
        it[1] = 0x01
        it[2] = 0x0d
        it[3] = 0xb8.toByte()
        it[15] = lastByte.toByte()
    }

    private fun writeU16Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }
}
