package com.enigma.farsilandtv

// 1.32: three themes. Fresh installs get the gold theme; a pick saved by an older version opens as the closest new one.
internal object AppThemes {
    val ids = listOf("cinema", "turquoise", "pomegranate")

    private val legacy = mapOf(
        "midnight" to "turquoise", "saffron" to "cinema", "garden" to "turquoise", "galaxy" to "pomegranate",
        "forest" to "turquoise", "nights" to "cinema", "ice" to "turquoise", "dragon" to "cinema",
        "castle" to "pomegranate", "orbit" to "turquoise", "theatre" to "pomegranate", "rose" to "pomegranate",
        "highlands" to "cinema")

    fun forSaved(saved: String?): String = legacy[saved] ?: saved?.takeIf { it in ids } ?: ids.first()
}
