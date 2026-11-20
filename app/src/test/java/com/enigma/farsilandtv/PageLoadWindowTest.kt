package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoadWindowTest {
    @Test fun redirectsCannotRestartTheTwoMinuteDeadline() {
        val window = PageLoadWindow()
        assertEquals(120_000L, window.begin(10_000, true))
        assertEquals(85_000L, window.begin(45_000, false))
        assertFalse(window.expired(45_000))
        assertEquals(1L, window.remaining(129_999))
        assertTrue(window.expired(130_000))
        assertEquals(0L, window.begin(140_000, false))
    }
    @Test fun successfulLoadsAndExplicitRetriesStartIndependentWindows() {
        val window = PageLoadWindow()
        window.begin(0, true)
        window.finish()
        assertFalse(window.expired(200_000))
        assertEquals(120_000L, window.begin(200_000, false))
        assertEquals(120_000L, window.begin(250_000, true))
        assertFalse(window.expired(320_000))
    }
}
