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

    /** Native screens (VPN, loading) use the same colours as the page theme. */
    data class Palette(val bg: Int, val panel: Int, val raised: Int, val raisedHi: Int, val text: Int, val muted: Int,
        val accent: Int, val focus: Int, val onFocus: Int)

    fun palette(id: String): Palette = when (id) {
        "turquoise" -> Palette(0xFF08142A.toInt(), 0xFF0E1F3B.toInt(), 0xFF15305A.toInt(), 0xFF1E3E6F.toInt(),
            0xFFEEF7FF.toInt(), 0xFFA9C3DD.toInt(), 0xFF2FE0D0.toInt(), 0xFF66F6E8.toInt(), 0xFF00302C.toInt())
        "pomegranate" -> Palette(0xFF1B0A11.toInt(), 0xFF27101A.toInt(), 0xFF3D1824.toInt(), 0xFF4D2131.toInt(),
            0xFFFBEFDF.toInt(), 0xFFDFC6B0.toInt(), 0xFFD98B52.toInt(), 0xFFF4AB72.toInt(), 0xFF2B1005.toInt())
        else -> Palette(0xFF141114.toInt(), 0xFF1F1A1B.toInt(), 0xFF2C251E.toInt(), 0xFF3B3127.toInt(),
            0xFFFBF3E4.toInt(), 0xFFCFC1A8.toInt(), 0xFFF2B544.toInt(), 0xFFFFD062.toInt(), 0xFF2A1A00.toInt())
    }
}
