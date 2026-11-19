package com.enigma.farsilandtv

import android.content.Context
import org.json.JSONArray

internal class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private var titles = read()

    fun contains(url: String) = titles.any { it.url == url }

    fun forScreen() = JSONArray(titles.map(LibraryTitle::json))

    fun toggle(title: LibraryTitle): Boolean {
        val added = !contains(title.url)
        titles = if (added) listOf(title) + titles else titles.filter { it.url != title.url }
        prefs.edit().putString("titles", forScreen().toString()).apply()
        return added
    }

    private fun read(): List<LibraryTitle> {
        val data = runCatching { JSONArray(prefs.getString("titles", "[]")) }.getOrElse { JSONArray() }
        return (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.let(LibraryTitle::fromJson)
        }.distinctBy(LibraryTitle::url)
    }
}
