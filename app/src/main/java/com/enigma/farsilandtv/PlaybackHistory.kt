package com.enigma.farsilandtv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

private fun catalogueUrl(value: String, paths: Regex): String? {
    if (value.length > 1024) return null
    val uri = Uri.parse(value)
    val path = uri.encodedPath.orEmpty().trimEnd('/')
    val host = uri.host?.lowercase()?.removePrefix("www.")
    if (uri.scheme != "https" || host != "farsiland.com" || uri.userInfo != null ||
        uri.port !in listOf(-1, 443) || !paths.matches(path)) return null
    return "https://farsiland.com$path/"
}

private val watchPaths = Regex("/(episodes|movies)/[^/]+")
private val titlePaths = Regex("/(tvshows|movies)/[^/]+")

internal fun watchUrl(value: String) = catalogueUrl(value, watchPaths)

internal fun titleUrl(value: String) = catalogueUrl(value, titlePaths)

private fun posterUrl(value: String): String? {
    val uri = Uri.parse(value)
    return value.takeIf {
        it.length <= 1024 && uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase()?.removePrefix("www.") == "farsiland.com" &&
            uri.encodedPath.orEmpty().startsWith("/wp-content/uploads/")
    }
}

internal fun videoQuality(value: Int) = value.takeIf { it in setOf(480, 720, 1080, 1440, 2160) }

internal data class LibraryTitle(val url: String, val title: String, val poster: String?) {
    fun json() = JSONObject().put("url", url).put("title", title).put("poster", poster)

    companion object {
        fun fromJson(details: JSONObject): LibraryTitle? {
            val url = titleUrl(details.optString("url")) ?: return null
            val title = details.optString("title").trim().take(180).ifBlank {
                Uri.parse(url).lastPathSegment.orEmpty().replace('-', ' ')
            }
            return LibraryTitle(url, title, posterUrl(details.optString("poster")))
        }
    }
}

internal data class WatchItem(
    val url: String,
    val title: String,
    val poster: String?,
    val next: NextEpisode?,
    val series: LibraryTitle? = null,
) {
    val libraryKey get() = series?.url ?: url

    fun json() = JSONObject().put("url", url).put("title", title).put("poster", poster)
        .put("next", next?.url).put("nextTitle", next?.title).put("nextSeason", next?.startsSeason)
        .put("series", series?.json())

    companion object {
        fun fromPage(page: String, details: JSONObject): WatchItem? {
            val url = watchUrl(page) ?: return null
            val fallbackTitle = Uri.parse(url).lastPathSegment.orEmpty().replace('-', ' ')
            val title = details.optString("title").trim().take(180).ifBlank { fallbackTitle }
            val poster = posterUrl(details.optString("poster"))
            val next = watchUrl(details.optString("next"))
                ?.takeIf { it != url && url.contains("/episodes/") && it.contains("/episodes/") }
                ?.let { NextEpisode(it, details.optString("nextTitle").trim().take(180), details.optBoolean("nextSeason")) }
            val series = details.optJSONObject("series")?.let(LibraryTitle::fromJson)
                ?.takeIf { "/episodes/" in url && "/tvshows/" in it.url }
            return WatchItem(url, title, poster, next, series)
        }
    }
}

internal data class NextEpisode(val url: String, val title: String, val startsSeason: Boolean) {
    fun asWatchItem(poster: String?, series: LibraryTitle?) = WatchItem(url, title.ifBlank {
        Uri.parse(url).lastPathSegment.orEmpty().replace('-', ' ')
    }, poster, null, series)
}

internal data class WatchProgress(
    val item: WatchItem,
    val position: Int,
    val duration: Int,
    val quality: Int?,
    val finished: Boolean = false,
) {
    fun json() = item.json().put("position", position).put("duration", duration).put("quality", quality)
        .put("finished", finished)
}

internal class PlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
    private var entries = read()
    private var dismissed = prefs.getStringSet(DISMISSED, emptySet()).orEmpty().toSet()

    fun forHome() = JSONArray(entries.distinctBy { it.item.libraryKey }
        .filter { !it.finished && it.item.libraryKey !in dismissed }
        .take(HOME_LIMIT).map { entry ->
        val title = entry.item.series
        JSONObject().put("url", entry.item.url).put("key", entry.item.libraryKey)
            .put("title", title?.title ?: entry.item.title).put("poster", title?.poster ?: entry.item.poster)
            .put("episode", entry.item.title.takeIf { title != null })
            .put("position", entry.position).put("duration", entry.duration)
    })

    fun find(url: String) = entries.firstOrNull { it.item.url == url }

    fun latestForTitle(url: String) = entries.firstOrNull { it.item.libraryKey == url }

    fun resumeForTitle(url: String) = latestForTitle(url)
        ?.takeUnless { it.finished || (it.position >= it.duration && it.duration > 0) }

    fun legacyEpisodes() = JSONArray(entries.filter { "/episodes/" in it.item.url && it.item.series == null }
        .take(8).map { it.item.url })

    fun associateSeries(episode: String, series: LibraryTitle) {
        val old = find(episode) ?: return
        if (old.item.series != null || "/episodes/" !in episode || "/tvshows/" !in series.url) return
        if (episode in dismissed) updateDismissed(dismissed - episode + series.url)
        save(entries.map { if (it.item.url == episode) it.copy(item = it.item.copy(series = series)) else it })
    }

    fun dismiss(url: String) {
        val item = find(url)?.item ?: return
        updateDismissed(dismissed + item.libraryKey)
    }

    fun remember(progress: WatchProgress) {
        if (progress.item.libraryKey in dismissed || progress.item.url in dismissed) {
            updateDismissed(dismissed - progress.item.libraryKey - progress.item.url)
        }
        if (entries.firstOrNull() == progress) return
        save(listOf(progress) + entries.filter { it.item.url != progress.item.url })
    }

    fun savePosition(progress: WatchProgress) {
        if (find(progress.item.url) == null) return
        save(entries.map { if (it.item.url == progress.item.url) progress else it })
    }

    fun complete(item: WatchItem, quality: Int?) {
        val saved = find(item.url)
        val completed = WatchProgress(item, saved?.duration ?: 0, saved?.duration ?: 0, quality, finished = true)
        val next = item.next?.let {
            val previous = find(it.url)?.takeUnless(WatchProgress::finished)
            previous?.copy(item = previous.item.copy(series = item.series ?: previous.item.series))
                ?: WatchProgress(it.asWatchItem(item.poster, item.series), 0, 0, quality)
        }
        save(listOfNotNull(next, completed) + entries.filter { it.item.url != item.url && it.item.url != next?.item?.url })
    }

    private fun updateDismissed(keys: Set<String>) {
        dismissed = keys
        prefs.edit().putStringSet(DISMISSED, keys).apply()
    }

    private fun save(updated: List<WatchProgress>) {
        entries = updated
        prefs.edit().putString(KEY, JSONArray(entries.map(WatchProgress::json)).toString()).apply()
    }

    private fun read(): List<WatchProgress> {
        val data = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return (0 until data.length()).mapNotNull { index ->
            val record = data.optJSONObject(index) ?: return@mapNotNull null
            val item = WatchItem.fromPage(record.optString("url"), record) ?: return@mapNotNull null
            val duration = record.optInt("duration").coerceIn(0, MAX_DURATION)
            val position = record.optInt("position").coerceIn(0, duration)
            WatchProgress(item, position, duration, videoQuality(record.optInt("quality")), record.optBoolean("finished"))
        }
    }

    companion object {
        const val MAX_DURATION = 24 * 60 * 60
        private const val KEY = "recent"
        private const val DISMISSED = "dismissed_titles"
        private const val HOME_LIMIT = 5
    }
}
