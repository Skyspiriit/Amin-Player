package com.enigma.farsilandtv

import org.json.JSONObject
import java.net.URI

/** Only facts captured on the selected catalogue page travel across the provider's POST handoff. */
internal data class PlaybackTitle(
    val kind: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val year: Int? = null,
) {
    fun json() = JSONObject().put("kind", kind).put("title", title)
        .put("season", season).put("episode", episode).put("episodeTitle", episodeTitle).put("year", year)

    companion object {
        fun fromJson(page: String, source: JSONObject?): PlaybackTitle? {
            source ?: return null
            if (!PlaybackSites.isCatalogue(page)) return null
            val path = runCatching { URI(page).path }.getOrNull() ?: return null
            val kind = when {
                path.startsWith("/movies/") -> "movie"
                path.startsWith("/episodes/") -> "episode"
                else -> return null
            }
            if (source.optString("kind") != kind) return null
            val title = (source.opt("title") as? String)?.trim()?.take(240)?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
            fun number(key: String): Int? {
                val raw = source.opt(key) as? Number ?: return null
                val value = raw.toDouble()
                return value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= (if (key == "season") 0 else 1) && it <= 99_999 }
                    ?.toInt()
            }
            return PlaybackTitle(kind, title,
                season = if (kind == "episode") number("season") else null,
                episode = if (kind == "episode") number("episode") else null,
                episodeTitle = if (kind == "episode") (source.opt("episodeTitle") as? String)?.trim()?.take(240)
                    ?.takeIf { it.isNotEmpty() && it != "null" && it != title } else null,
                year = if (kind == "movie") number("year")?.takeIf { it in 1000..2100 } else null)
        }
    }
}

/** Static, versioned font assets only; never expose arbitrary files or intercept playback requests. */
internal object PlayerFonts {
    fun asset(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || PlaybackSites.host(url) !in setOf("farsiland.com", "farsiland.buzz", "farsiplex.com") ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        when (uri.rawPath) {
            "/__amin-player__/fonts/v33/Vazirmatn-Regular.woff2" -> "fonts/Vazirmatn-Regular.woff2"
            "/__amin-player__/fonts/v33/Vazirmatn-Bold.woff2" -> "fonts/Vazirmatn-Bold.woff2"
            else -> null
        }
    }.getOrNull()
}
