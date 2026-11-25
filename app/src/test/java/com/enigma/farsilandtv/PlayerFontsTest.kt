package com.enigma.farsilandtv

import org.junit.Assert.*
import org.junit.Test

class PlayerFontsTest {
    @Test fun onlyFixedBundledFontsOnKnownHttpsHostsAreServed() {
        val path = "/__amin-player__/fonts/v33/Vazirmatn-Bold.woff2"
        for (host in listOf("farsiland.com", "www.farsiland.com", "farsiland.buzz", "farsiplex.com", "www.farsiplex.com")) {
            assertEquals("fonts/Vazirmatn-Bold.woff2", PlayerFonts.asset("https://$host$path"))
        }
        for (url in listOf("http://farsiland.com$path", "https://farsiland.com.evil.test$path",
                "https://user@farsiland.com$path", "https://farsiland.com:8443$path", "https://farsiland.com$path?file=secret",
                "https://farsiland.com/__amin-player__/fonts/v33/../../private", "https://farsiland.com/smart-play")) {
            assertNull(url, PlayerFonts.asset(url))
        }
    }
}
