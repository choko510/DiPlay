package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspMessageParserTest {
    @Test
    fun parsesFragmentedHeadersBodiesAndCoalescedRequests() {
        val body = "hello".toByteArray(Charsets.US_ASCII)
        val wire = (
            "POST /first RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n" +
                "hello" +
                "OPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val parser = RtspMessage.Parser(maxInputChunkBytes = 7)
        val messages = ArrayList<RtspMessage.Request>()
        var offset = 0
        while (offset < wire.size) {
            val count = minOf(7, wire.size - offset)
            messages += parser.append(wire, offset, count)
            offset += count
        }

        assertEquals(2, messages.size)
        assertEquals("POST", messages[0].method)
        assertEquals("/first", messages[0].path)
        assertEquals("1", messages[0].headers["cseq"])
        assertArrayEquals(body, messages[0].body)
        assertEquals("OPTIONS", messages[1].method)
        assertEquals("2", messages[1].headers["cseq"])
        assertTrue(parser.pendingBytes().isEmpty())
    }

    @Test
    fun rejectsOversizedDeclaredBodyBeforeBufferingIt() {
        val parser = RtspMessage.Parser(
            maxHeaderBytes = 256,
            maxBodyBytes = 1024,
            maxInputChunkBytes = 32,
        )
        val header = "POST /large RTSP/1.0\r\nContent-Length: 1048576\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

        assertIllegalArgument {
            var offset = 0
            while (offset < header.size) {
                val count = minOf(32, header.size - offset)
                parser.append(header, offset, count)
                offset += count
            }
        }
    }

    @Test
    fun rejectsAnUnterminatedHeaderPastItsLimit() {
        val parser = RtspMessage.Parser(
            maxHeaderBytes = 32,
            maxBodyBytes = 64,
            maxInputChunkBytes = 8,
        )
        val incomplete = ByteArray(40) { 'x'.code.toByte() }

        assertIllegalArgument {
            var offset = 0
            while (offset < incomplete.size) {
                parser.append(incomplete, offset, minOf(8, incomplete.size - offset))
                offset += 8
            }
        }
    }

    @Test
    fun decryptsFragmentedFramesWithoutRetainingPreviousInputArrays() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "POST /command RTSP/1.0\r\nContent-Length: 4\r\n\r\ntest"
            .toByteArray(Charsets.US_ASCII)
        val encrypted = ControlCipher(key, key).encrypt(plaintext)
        val decoder = ControlCipher(key, key).newStreamDecoder()
        val decoded = ArrayList<Byte>()

        encrypted.forEach { byte -> decoded.addAll(decoder.decrypt(byteArrayOf(byte)).toList()) }

        assertArrayEquals(plaintext, decoded.toByteArray())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
