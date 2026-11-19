package com.enigma.farsilandtv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalImportHttpTest {
    @Test fun rejectsAmbiguousAndUnboundedHttpBodies() {
        val invalid = listOf(
            "POST /import HTTP/1.1\r\nContent-Length: 2\r\ncontent-length: 8\r\n\r\n",
            "POST /import HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST /import HTTP/1.1\r\nExpect: 100-continue\r\n\r\n",
            "GET / HTTP/1.1\n\n",
            "GET / HTTP/1.1\r\nHost : attacker\r\n\r\n",
            "GET / HTTP/1.1\r\nX-Fill: ${"a".repeat(16_384)}\r\n\r\n"
        )
        invalid.forEach { assertTrue(runCatching { LocalImportHttp.read(it.byteInputStream()) }.isFailure) }
        assertTrue(runCatching { LocalImportHttp.body(ByteArrayInputStream(byteArrayOf(1)), 2) }.isFailure)
        assertTrue(runCatching { LocalImportHttp.body(ByteArrayInputStream(byteArrayOf()), 1_048_577) }.isFailure)
    }

    @Test fun rejectsCorruptUtf8AndPreservesPersianText() {
        assertTrue(runCatching { LocalImportHttp.text(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertEquals("آمین", LocalImportHttp.text("آمین".toByteArray(Charsets.UTF_8)))
        val input = "POST /import HTTP/1.1\r\nHost: 192.168.1.2:1234\r\nContent-Length: 3\r\n\r\nabc".byteInputStream()
        assertEquals("192.168.1.2:1234", LocalImportHttp.read(input).headers["host"])
        assertEquals("abc", LocalImportHttp.text(LocalImportHttp.body(input, 3)))
    }
}
