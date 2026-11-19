package com.enigma.farsilandtv

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One bounded request per connection. No multipart parser, file names, or disk staging. */
internal object LocalImportHttp {
    data class Request(val method: String, val path: String, val headers: Map<String, String>)

    fun read(input: InputStream): Request {
        var total = 0
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val c = input.read()
                require(c >= 0 && ++total <= 16_384) { "Invalid request" }
                if (c == 10) {
                    require(bytes.lastOrNull() == 13.toByte()) { "Invalid request" }
                    bytes.removeAt(bytes.lastIndex)
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                require(c == 13 || c in 32..126) { "Invalid request" }
                bytes.add(c.toByte())
            }
        }
        val start = line().split(' ')
        require(start.size == 3 && start[2] in setOf("HTTP/1.1", "HTTP/1.0")) { "Invalid request" }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val item = line()
            if (item.isEmpty()) break
            val colon = item.indexOf(':')
            require(colon > 0 && headers.size < 40) { "Invalid request" }
            val name = item.substring(0, colon).lowercase(java.util.Locale.ROOT)
            require(name.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(name)) { "Invalid request" }
            headers[name] = item.substring(colon + 1).trim()
        }
        require("transfer-encoding" !in headers && "expect" !in headers) { "Invalid request" }
        return Request(start[0], start[1], headers)
    }

    fun body(input: InputStream, length: Int): ByteArray {
        require(length in 1..SubscriptionAddress.MAX_BYTES) { "Invalid file size" }
        val result = ByteArray(length)
        try {
            var offset = 0
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                require(count > 0) { "Incomplete file" }
                offset += count
            }
            return result
        } catch (e: Exception) { result.fill(0); throw e }
    }

    fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
