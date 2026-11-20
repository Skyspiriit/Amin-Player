package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSitesTest {
    @Test fun bothPlayerHostsAndTheirWwwVariantsAreSupported() {
        for (host in listOf("farsiplex.com", "www.farsiplex.com", "farsiland.buzz", "www.farsiland.buzz")) {
            assertTrue(host, PlaybackSites.isPlayer("https://$host/smart-play"))
            assertTrue(host, PlaybackSites.isPlayer("https://$host:443/smart-play?video=test"))
            assertFalse(host, PlaybackSites.isCatalogue("https://$host/"))
        }
        assertTrue(PlaybackSites.isPlayer("HTTPS://WWW.FARSIPLEX.COM/smart-play"))
        assertEquals("farsiplex.com", PlaybackSites.host("https://www.farsiplex.com/smart-play"))
    }

    @Test fun unrelatedHostsCredentialsAndInsecureUrlsAreRejected() {
        for (url in listOf(
            "http://farsiplex.com/smart-play", "https://farsiplex.com:8443/smart-play",
            "https://farsiplex.com.evil.example/smart-play", "https://evilfarsiplex.com/smart-play",
            "https://farsiplex-com.example/smart-play", "https://player.farsiplex.com/smart-play",
            "https://www.www.farsiplex.com/smart-play", "https://farsiplex.com./smart-play",
            "https://farsiplex.com@evil.example/smart-play", "https://evil.example@farsiplex.com/smart-play",
            "https://user:password@farsiplex.com/smart-play", "https://farsiplex.com%2eevil.example/",
            "https://farsiplex.com\\@evil.example/", "//farsiplex.com/smart-play", "/smart-play",
            "javascript:location='https://farsiplex.com/smart-play'", "https://example.com/"
        )) assertFalse(url, PlaybackSites.isPlayer(url))
    }

    @Test fun catalogueRemainsSeparateFromPlayerOrigins() {
        assertTrue(PlaybackSites.isCatalogue("https://farsiland.com/episodes/taxi-ep01/"))
        assertTrue(PlaybackSites.isCatalogue("https://www.farsiland.com/home"))
        assertFalse(PlaybackSites.isPlayer("https://farsiland.com/episodes/taxi-ep01/"))
        assertFalse(PlaybackSites.isCatalogue("https://farsiland.com.evil.example/home"))
    }
}
