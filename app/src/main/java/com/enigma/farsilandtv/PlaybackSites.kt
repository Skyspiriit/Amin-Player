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

    /**
     * Google ad services used by the players' pre-roll ads. Where these hang (for example under
     * filtering), the player shows a black screen for about 45 seconds before the episode, so the
     * app makes them fail at once and the player starts the episode directly.
     */
    private val adDomains = listOf("imasdk.googleapis.com", "doubleclick.net", "googlesyndication.com",
        "googleadservices.com", "2mdn.net")

    fun isAd(value: String): Boolean {
        val host = runCatching { URI(value).host }.getOrNull()?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return adDomains.any { host == it || host.endsWith(".$it") }
    }
}
