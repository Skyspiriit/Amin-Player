package com.enigma.farsilandtv

import java.net.InetAddress
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object SubscriptionAddress {
    const val MAX_BYTES = 1024 * 1024

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            require(output.size() + count <= limit) { "Subscription is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun parse(text: String): URI {
        val value = text.trim()
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.length in 10..4096 && value.none(Char::isWhitespace) && uri != null &&
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && publicHost(uri.host)) {
            "Enter a public HTTPS subscription URL."
        }
        return uri
    }

    private fun publicHost(host: String): Boolean {
        val name = host.lowercase().removeSurrounding("[", "]")
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return false
        if (name.contains(':') || name.matches(Regex("[0-9.]+"))) {
            return runCatching { publicAddress(InetAddress.getByName(name)) }.getOrDefault(false)
        }
        return name.contains('.')
    }

    fun publicAddress(address: InetAddress) = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isSiteLocalAddress && !address.isLinkLocalAddress && !address.isMulticastAddress &&
        !(address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
}
