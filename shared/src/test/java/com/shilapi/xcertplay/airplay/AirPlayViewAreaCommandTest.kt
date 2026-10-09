package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirPlayViewAreaCommandTest {
    @Test fun outgoingCommandMatchesTheExperimentalCandidateAndRoundTripsAsBinaryPlist() {
        val command = AirPlayViewAreaCommand.update(index = 1, declaredAreaCount = 2)!!
        val params = command["params"] as Map<*, *>

        assertEquals("updateViewArea", command["type"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
        assertEquals(1, params["viewAreaIndex"])
        assertEquals(0, params["animationDurationMillis"])
        assertEquals(listOf(0), params["adjacentViewAreas"])
        @Suppress("UNCHECKED_CAST")
        val roundTrip = BplistCodec.decode(BplistCodec.encode(command)) as Map<*, *>
        val roundTripParams = roundTrip["params"] as Map<*, *>
        assertEquals(1, (roundTripParams["viewAreaIndex"] as Number).toInt())
        assertEquals(listOf(0L), roundTripParams["adjacentViewAreas"])
    }

    @Test fun outgoingCommandRejectsUndeclaredIndicesOrInvalidDuration() {
        assertNull(AirPlayViewAreaCommand.update(index = 0, declaredAreaCount = 1))
        assertNull(AirPlayViewAreaCommand.update(index = 2, declaredAreaCount = 2))
        assertNull(AirPlayViewAreaCommand.update(index = 0, declaredAreaCount = 2, animationDurationMillis = -1))
    }

    @Test fun incomingRequestParserValidatesDisplayAndIndexWithoutTouchingUnknownCommands() {
        val params = mapOf<String, Any?>("uuid" to AirPlayInfoPlist.MAIN_UUID, "viewAreaIndex" to 1L)
        assertNull(AirPlayViewAreaCommandParser.parseIncoming("unknown", params))
        val valid = AirPlayViewAreaCommandParser.parseIncoming("requestViewArea", params)!!
        assertNull(valid.validationError(declaredAreaCount = 2))

        val wrongDisplay = AirPlayViewAreaCommandParser.parseIncoming(
            "requestViewArea",
            params + ("uuid" to AirPlayInfoPlist.ALT_UUID),
        )!!
        assertEquals("unknown_display_uuid", wrongDisplay.validationError(declaredAreaCount = 2))
        val invalidIndex = AirPlayViewAreaCommandParser.parseIncoming(
            "requestViewArea",
            params + ("viewAreaIndex" to Double.NaN),
        )!!
        assertEquals("missing_or_invalid_index", invalidIndex.validationError(declaredAreaCount = 2))
        assertEquals("dynamic_view_areas_not_declared", valid.validationError(declaredAreaCount = 1))
        assertEquals("view_area_index_out_of_range", valid.copy(viewAreaIndex = 2).validationError(2))
    }

    @Test fun eventChannelReadinessIsDistinctAndDoesNotCloseTheSession() {
        val legacy = testSession(dynamicAreas = false)
        val dynamic = testSession(dynamicAreas = true)
        try {
            assertEquals(ViewAreaCommandWriteResult.DYNAMIC_AREAS_NOT_DECLARED, legacy.writeViewAreaSelection(1))
            assertEquals(ViewAreaCommandWriteResult.EVENT_CHANNEL_NOT_READY, dynamic.writeViewAreaSelection(1))
            assertFalse(legacy.isClosed)
            assertFalse(dynamic.isClosed)
        } finally {
            legacy.close()
            dynamic.close()
        }
    }

    @Test fun writeFailureDoesNotApplyTheOrdinaryCommandClosePolicy() {
        val failed = testSession(dynamicAreas = true)
        val successBytes = ByteArrayOutputStream()
        val succeeded = testSession(dynamicAreas = true)
        try {
            installEventChannel(failed, OutputSocket(object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("test write failure")
                }
            }))
            installEventChannel(succeeded, OutputSocket(successBytes))

            assertEquals(ViewAreaCommandWriteResult.WRITE_FAILED, failed.writeViewAreaSelection(1))
            assertEquals(ViewAreaCommandWriteResult.WRITTEN, succeeded.writeViewAreaSelection(1))
            assertTrue(successBytes.size() > 0)
            assertFalse(failed.isClosed)
            assertFalse(succeeded.isClosed)
        } finally {
            failed.close()
            succeeded.close()
        }
    }

    private fun testSession(dynamicAreas: Boolean): AirPlaySession {
        val baseDisplay = AirPlayDisplayConfig(1280, 720)
        val display = if (dynamicAreas) {
            baseDisplay.copy(dynamicViewAreas = DynamicViewAreaFactory.twoAreas(baseDisplay))
        } else {
            baseDisplay
        }
        return AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = display,
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
    }

    private fun installEventChannel(session: AirPlaySession, socket: Socket) {
        AirPlaySession::class.java.getDeclaredField("eventSocket").apply {
            isAccessible = true
            set(session, socket)
        }
        AirPlaySession::class.java.getDeclaredField("eventCipher").apply {
            isAccessible = true
            set(session, ControlCipher(ByteArray(32), ByteArray(32)))
        }
    }

    private class OutputSocket(private val output: OutputStream) : Socket() {
        override fun getOutputStream(): OutputStream = output
    }
}
