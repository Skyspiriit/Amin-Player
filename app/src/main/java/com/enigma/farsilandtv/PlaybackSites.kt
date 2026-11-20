package com.enigma.farsilandtv

import java.net.URI
import java.util.Locale

/** Shared native navigation/bridge policy; media subresources keep the site's own URLs. */
internal object PlaybackSites {
    fun host(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.port !in listOf(-1, 443) ||
            uri.rawUserInfo != null) return null
        uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
    }.getOrNull()

    fun isCatalogue(value: String) = host(value) == "farsiland.com"
    fun isPlayer(value: String) = host(value) in setOf("farsiland.buzz", "farsiplex.com")
}
