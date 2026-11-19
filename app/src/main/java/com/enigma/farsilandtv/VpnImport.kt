package com.enigma.farsilandtv

internal object VpnImport {
    fun text(value: String): String {
        val content = value.removePrefix("\uFEFF").trim()
        require(content.isNotEmpty() && content.toByteArray(Charsets.UTF_8).size <= SubscriptionAddress.MAX_BYTES) {
            "Choose a supported VPN file."
        }
        if (remote(content)) SubscriptionAddress.parse(content)
        else require(!content.startsWith("http:", ignoreCase = true)) { "Use a HTTPS subscription." }
        return content
    }

    fun remote(value: String) = value.startsWith("https:", ignoreCase = true)
}
