package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AirPlayHidTest {
    @Test
    fun touchReportUsesFixedHidSlotIdsRatherThanInputOrder() {
        val report = AirPlayHid.touchReport(
            listOf(
                AirPlayContact(id = 1, x = 0x4567.toDouble(), y = 0x89ab.toDouble(), down = true),
                AirPlayContact(id = 0, x = 0x1234.toDouble(), y = 0xabcd.toDouble(), down = false),
            ),
        )

        assertArrayEquals(
            byteArrayOf(
                0, 0, 0x34, 0x12, 0xcd.toByte(), 0xab.toByte(),
                1, 1, 0x67, 0x45, 0xab.toByte(), 0x89.toByte(),
            ),
            report,
        )
    }
}
