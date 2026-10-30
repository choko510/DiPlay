package com.shilapi.xcertplay.airplay

/**
 * RTSP/HTTP-style request framing for the CarPlay control channel.
 *
 * The control connection on TCP :7000 speaks a text request line plus headers plus an optional
 * body, using the same framing RTSP and HTTP share. Encryption, once pair-verify completes, wraps
 * this framing in a separate layer.
 */
object RtspMessage {
    data class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val protocol: String? = null,
        val status: Int? = null,
        val statusText: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class Parsed(val messages: List<Request>, val rest: ByteArray)

    private val headerEnd = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val statusText = mapOf(
        200 to "OK",
        400 to "Bad Request",
        404 to "Not Found",
        500 to "Internal Server Error",
    )

    /** Parses complete requests as bytes arrive and retains only one bounded partial request. */
    class Parser(
        private val maxHeaderBytes: Int = MAX_HEADER_BYTES,
        private val maxBodyBytes: Int = MAX_BODY_BYTES,
        private val maxInputChunkBytes: Int = MAX_INPUT_CHUNK_BYTES,
    ) {
        private data class Head(
            val method: String,
            val path: String,
            val protocol: String,
            val headers: Map<String, String>,
            val bodyStartOffset: Int,
            val messageBytes: Int,
        )

        private val maxMessageBytes = maxHeaderBytes + headerEnd.size + maxBodyBytes
        private val maxBufferedBytes = maxMessageBytes + maxInputChunkBytes
        private var buffer = ByteArray(minOf(INITIAL_BUFFER_BYTES, maxBufferedBytes))
        private var readOffset = 0
        private var writeOffset = 0
        private var headerScanOffset = 0
        private var head: Head? = null

        init {
            require(maxHeaderBytes > 0)
            require(maxBodyBytes >= 0)
            require(maxInputChunkBytes > 0)
        }

        fun append(bytes: ByteArray): List<Request> = append(bytes, 0, bytes.size)

        fun append(bytes: ByteArray, offset: Int, length: Int): List<Request> {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            require(length <= maxInputChunkBytes) { "RTSP input chunk exceeds configured bound" }
            if (length == 0) return emptyList()

            val pendingBytes = writeOffset - readOffset
            require(pendingBytes + length <= maxBufferedBytes) { "RTSP message exceeds configured bound" }
            ensureCapacity(length)
            bytes.copyInto(buffer, writeOffset, offset, offset + length)
            writeOffset += length
            return parseAvailable()
        }

        fun pendingBytes(): ByteArray = buffer.copyOfRange(readOffset, writeOffset)

        private fun ensureCapacity(additionalBytes: Int) {
            if (writeOffset + additionalBytes <= buffer.size) return
            val pendingBytes = writeOffset - readOffset
            if (readOffset > 0) {
                buffer.copyInto(buffer, 0, readOffset, writeOffset)
                readOffset = 0
                writeOffset = pendingBytes
            }
            if (writeOffset + additionalBytes <= buffer.size) return

            val required = writeOffset + additionalBytes
            var newCapacity = buffer.size
            while (newCapacity < required) {
                newCapacity = minOf(maxBufferedBytes, maxOf(newCapacity * 2, INITIAL_BUFFER_BYTES))
            }
            buffer = buffer.copyOf(newCapacity)
        }

        private fun parseAvailable(): List<Request> {
            val messages = ArrayList<Request>()
            while (readOffset < writeOffset) {
                var currentHead = head
                if (currentHead == null) {
                    val headerEndOffset = findHeaderEnd()
                    if (headerEndOffset == null) {
                        val pendingBytes = writeOffset - readOffset
                        require(pendingBytes <= maxHeaderBytes + headerEnd.size - 1) {
                            "RTSP headers exceed configured bound"
                        }
                        headerScanOffset = (pendingBytes - headerEnd.size + 1).coerceAtLeast(0)
                        break
                    }
                    val headerBytes = headerEndOffset - readOffset
                    require(headerBytes <= maxHeaderBytes) { "RTSP headers exceed configured bound" }
                    currentHead = parseHead(headerBytes, headerEndOffset - readOffset + headerEnd.size)
                    require(currentHead.messageBytes <= maxMessageBytes) {
                        "RTSP message exceeds configured bound"
                    }
                    head = currentHead
                }

                if (writeOffset - readOffset < currentHead.messageBytes) break
                val bodyStart = readOffset + currentHead.bodyStartOffset
                val bodyEnd = readOffset + currentHead.messageBytes
                messages.add(
                    Request(
                        method = currentHead.method,
                        path = currentHead.path,
                        protocol = currentHead.protocol,
                        headers = currentHead.headers,
                        body = buffer.copyOfRange(bodyStart, bodyEnd),
                    ),
                )
                readOffset = bodyEnd
                head = null
                headerScanOffset = 0
            }
            if (readOffset == writeOffset) {
                readOffset = 0
                writeOffset = 0
                headerScanOffset = 0
            }
            return messages
        }

        private fun findHeaderEnd(): Int? {
            val lastPossibleStart = writeOffset - headerEnd.size
            var index = readOffset + headerScanOffset
            while (index <= lastPossibleStart) {
                if (buffer[index] == headerEnd[0] && matches(buffer, index, headerEnd)) return index
                index++
            }
            return null
        }

        private fun parseHead(headerBytes: Int, bodyStartOffset: Int): Head {
            val headerText = String(buffer, readOffset, headerBytes, Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ", limit = 3).orEmpty()
            require(requestLine.size >= 2 && requestLine[0].isNotEmpty() && requestLine[1].isNotEmpty()) {
                "Malformed RTSP request line"
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val separator = line.indexOf(':')
                require(separator > 0) { "Malformed RTSP header" }
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                require(name.isNotEmpty()) { "Malformed RTSP header" }
                if (name == "content-length") {
                    require(headers.put(name, value) == null) { "Duplicate RTSP Content-Length" }
                } else {
                    headers[name] = value
                }
            }
            val bodyBytes = headers["content-length"]?.let { value ->
                val parsed = value.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid RTSP Content-Length")
                require(parsed in 0..maxBodyBytes.toLong()) { "RTSP body exceeds configured bound" }
                parsed.toInt()
            } ?: 0
            val messageBytes = bodyStartOffset + bodyBytes
            return Head(
                method = requestLine[0],
                path = requestLine[1],
                protocol = requestLine.getOrElse(2) { "RTSP/1.0" },
                headers = headers,
                bodyStartOffset = bodyStartOffset,
                messageBytes = messageBytes,
            )
        }
    }

    /** Parses as many complete messages as are buffered, retaining any partial remainder. */
    fun parseMessages(buffer: ByteArray): Parsed {
        val parser = Parser()
        val messages = ArrayList<Request>()
        var offset = 0
        while (offset < buffer.size) {
            val length = minOf(MAX_INPUT_CHUNK_BYTES, buffer.size - offset)
            messages += parser.append(buffer, offset, length)
            offset += length
        }
        return Parsed(messages, parser.pendingBytes())
    }

    /** Builds a response, echoing the request protocol and CSeq. */
    fun buildResponse(request: Request, response: Response): ByteArray {
        val protocol = response.protocol ?: request.protocol
        val status = response.status ?: 200
        val statusLine = response.statusText ?: statusText[status] ?: "OK"
        val body = response.body
        val headers = LinkedHashMap<String, String>(response.headers)
        request.headers["cseq"]?.let { headers["CSeq"] = it }
        headers["Content-Length"] = body.size.toString()

        val head = buildString {
            append(protocol).append(' ').append(status).append(' ').append(statusLine).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private fun matches(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        for (index in target.indices) {
            if (source[offset + index] != target[index]) return false
        }
        return true
    }

    const val MAX_HEADER_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    const val MAX_INPUT_CHUNK_BYTES = 16 * 1024

    private const val INITIAL_BUFFER_BYTES = 4096
}
