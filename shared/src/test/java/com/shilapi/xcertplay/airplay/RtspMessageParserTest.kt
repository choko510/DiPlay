package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun acceptsHeaderAndBodyAtTheirLimitsAndRejectsOneByteOver() {
        val maxHeaderBytes = 64
        val headerPrefix = "OPTIONS * RTSP/1.0\r\nX: "
        val maximumHeader =
            headerPrefix + "a".repeat(maxHeaderBytes - headerPrefix.length) + "\r\n\r\n"
        val oneByteOverHeader =
            headerPrefix + "a".repeat(maxHeaderBytes + 1 - headerPrefix.length) + "\r\n\r\n"

        assertEquals(
            1,
            parseAll(
                maximumHeader.toByteArray(Charsets.US_ASCII),
                maxHeaderBytes = maxHeaderBytes,
                maxBodyBytes = 8,
                chunkBytes = 7,
            ).size,
        )
        assertIllegalArgument {
            parseAll(
                oneByteOverHeader.toByteArray(Charsets.US_ASCII),
                maxHeaderBytes = maxHeaderBytes,
                maxBodyBytes = 8,
                chunkBytes = 7,
            )
        }

        val maxBodyBytes = 64
        for (bodySize in maxBodyBytes - 1..maxBodyBytes) {
            val body = ByteArray(bodySize) { 'x'.code.toByte() }
            val header = "POST /body RTSP/1.0\r\nContent-Length: $bodySize\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
            val messages = parseAll(
                header + body,
                maxHeaderBytes = 128,
                maxBodyBytes = maxBodyBytes,
                chunkBytes = 16,
            )

            assertEquals(1, messages.size)
            assertArrayEquals(body, messages.single().body)
        }

        val tooLargeBodyHeader = "POST /body RTSP/1.0\r\nContent-Length: ${maxBodyBytes + 1}\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        assertIllegalArgument {
            parseAll(
                tooLargeBodyHeader,
                maxHeaderBytes = 128,
                maxBodyBytes = maxBodyBytes,
                chunkBytes = 16,
            )
        }
    }

    @Test
    fun handlesMissingAndMalformedContentLengthValues() {
        val missingLength = parseAll(
            "OPTIONS * RTSP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII),
        )
        assertEquals(1, missingLength.size)
        assertTrue(missingLength.single().body.isEmpty())

        for (contentLength in listOf("-1", "9223372036854775808")) {
            val header = "POST /body RTSP/1.0\r\nContent-Length: $contentLength\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
            assertIllegalArgument { parseAll(header, maxBodyBytes = 64) }
        }
    }

    @Test
    fun retainsOneMiBOfAnIncompleteBodyWithinTheConfiguredMessageLimit() {
        val header = "POST /slow RTSP/1.0\r\nContent-Length: 4194304\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val parser = RtspMessage.Parser()
        val chunk = ByteArray(RtspMessage.MAX_INPUT_CHUNK_BYTES) { 'x'.code.toByte() }
        val partialBodyBytes = 1024 * 1024

        assertTrue(parser.append(header).isEmpty())
        repeat(partialBodyBytes / chunk.size) {
            assertTrue(parser.append(chunk).isEmpty())
        }

        assertEquals(header.size + partialBodyBytes, parser.pendingBytes().size)
        assertFalse(parser.pendingBytes().isEmpty())
    }

    @Test
    fun parsesTenThousandCoalescedAndOneByteFragmentedRequests() {
        val request = "OPTIONS * RTSP/1.0\r\n\r\n"
        val wire = request.repeat(10_000).toByteArray(Charsets.US_ASCII)

        assertEquals(10_000, parseAll(wire).size)

        val parser = RtspMessage.Parser()
        var parsedCount = 0
        for (offset in wire.indices) {
            parsedCount += parser.append(wire, offset, 1).size
        }

        assertEquals(10_000, parsedCount)
        assertTrue(parser.pendingBytes().isEmpty())
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

    private fun parseAll(
        wire: ByteArray,
        maxHeaderBytes: Int = RtspMessage.MAX_HEADER_BYTES,
        maxBodyBytes: Int = RtspMessage.MAX_BODY_BYTES,
        chunkBytes: Int = RtspMessage.MAX_INPUT_CHUNK_BYTES,
    ): List<RtspMessage.Request> {
        val parser = RtspMessage.Parser(
            maxHeaderBytes = maxHeaderBytes,
            maxBodyBytes = maxBodyBytes,
            maxInputChunkBytes = chunkBytes,
        )
        val messages = ArrayList<RtspMessage.Request>()
        var offset = 0
        while (offset < wire.size) {
            val length = minOf(chunkBytes, wire.size - offset)
            messages += parser.append(wire, offset, length)
            offset += length
        }
        assertTrue(parser.pendingBytes().isEmpty())
        return messages
    }
}
