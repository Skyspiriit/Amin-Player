package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemesTest {
    @Test fun freshInstallsAndUnknownValuesGetTheGoldTheme() {
        assertEquals("cinema", AppThemes.forSaved(null))
        assertEquals("cinema", AppThemes.forSaved("something-else"))
    }

    @Test fun newThemesAreKept() {
        AppThemes.ids.forEach { assertEquals(it, AppThemes.forSaved(it)) }
    }

    @Test fun everyOlderThemeOpensAsTheClosestNewTheme() {
        mapOf("midnight" to "turquoise", "saffron" to "cinema", "garden" to "turquoise", "galaxy" to "pomegranate",
            "forest" to "turquoise", "nights" to "cinema", "ice" to "turquoise", "dragon" to "cinema",
            "castle" to "pomegranate", "orbit" to "turquoise", "theatre" to "pomegranate", "rose" to "pomegranate",
            "highlands" to "cinema").forEach { (old, new) -> assertEquals(old, new, AppThemes.forSaved(old)) }
    }
}
