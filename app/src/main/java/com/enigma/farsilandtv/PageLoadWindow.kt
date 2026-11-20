package com.enigma.farsilandtv

/** Redirects share one loading deadline; explicit navigation/retry gets a fresh budget. */
internal class PageLoadWindow(private val limitMs: Long = 120_000) {
    private var deadline: Long? = null
    fun begin(now: Long, fresh: Boolean): Long {
        if (fresh || deadline == null) deadline = now + limitMs
        return remaining(now)
    }
    fun remaining(now: Long) = deadline?.let { (it - now).coerceAtLeast(0) } ?: 0
    fun expired(now: Long) = deadline != null && remaining(now) == 0L
    fun finish() { deadline = null }
}
