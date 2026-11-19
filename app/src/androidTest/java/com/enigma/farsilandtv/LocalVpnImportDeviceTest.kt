package com.enigma.farsilandtv

import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class LocalVpnImportDeviceTest {
    @Test fun tlsUploadRequiresPairingAndSameOriginAndOnlyStagesOneFile() {
        val received = CountDownLatch(1)
        var value: String? = null
        val server = LocalVpnImportServer.testing(InetAddress.getByName("127.0.0.1") as Inet4Address, {
            value = LocalImportHttp.text(it); it.fill(0); received.countDown()
        })
        try {
            assertTrue(request(server, "POST", "/import", "000000000", server.url, "fixture").startsWith("HTTP/1.1 401"))
            assertTrue(request(server, "POST", "/import", server.code, "https://unrelated.example", "fixture").startsWith("HTTP/1.1 403"))
            assertNull(value)
            val result = request(server, "POST", "/import", server.code, server.url, "public fixture")
            assertTrue(result.startsWith("HTTP/1.1 202"))
            assertFalse(result.contains("public fixture"))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals("public fixture", value)
            assertTrue(request(server, "POST", "/import", server.code, server.url, "again").startsWith("HTTP/1.1 409"))
            assertFalse(VpnEngine.running())
        } finally { server.close() }
        assertTrue(server.closed)
    }

    @Test fun fiveIncorrectCodesCloseListenerAndNoKeyIsPersisted() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = store.aliases().toList().toSet()
        val server = LocalVpnImportServer.testing(InetAddress.getByName("127.0.0.1") as Inet4Address, { fail("Wrong code admitted") })
        repeat(5) { assertTrue(request(server, "POST", "/import", "wrong", server.url, "x").startsWith("HTTP/1.1 401")) }
        for (i in 0..40) { if (server.closed) break; Thread.sleep(25) }
        assertTrue(server.closed)
        assertEquals(before, store.aliases().toList().toSet())
        assertTrue(runCatching { request(server, "GET", "/", "", server.url, "") }.isFailure)
    }

    @Test fun timerAndExplicitCloseEndSessionAndBundledScriptMatchesCsp() {
        val page = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("vpn-import.html")
            .bufferedReader().use { it.readText() }
        val server = LocalVpnImportServer.testing(InetAddress.getByName("127.0.0.1") as Inet4Address,
            { fail("No upload expected") }, 3000, page.toByteArray(Charsets.UTF_8))
        try {
            val result = request(server, "GET", "/", "", server.url, "")
            assertTrue(result.contains("Cache-Control: no-store"))
            assertTrue(result.contains("frame-ancestors 'none'"))
            assertTrue(result.endsWith(page))
            val script = page.substringAfter("<script>").substringBefore("</script>")
            val hash = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(script.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
            assertTrue(result.contains("'sha256-$hash'"))
            Thread.sleep(3500)
            assertTrue(server.closed)
            assertFalse(LocalVpnImportServer.sameSubnet(InetAddress.getByName("192.168.1.2"), InetAddress.getByName("192.168.2.2"), 24))
        } finally { server.close() }
    }

    private fun request(server: LocalVpnImportServer, method: String, path: String, pin: String, origin: String, body: String): String {
        // Trust only this session's certificate. Never use a trust-all manager or ignore TLS errors.
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("session", server.certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, SecureRandom()) }
        val address = java.net.URI(server.url)
        return (tls.socketFactory.createSocket(address.host, address.port) as SSLSocket).use { socket ->
            socket.soTimeout = 3000
            socket.startHandshake()
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "$method $path HTTP/1.1\r\nHost: ${address.rawAuthority}\r\nOrigin: $origin\r\nX-Amin-Pairing-Code: $pin\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.outputStream.write(head.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(bytes); socket.outputStream.flush()
            socket.inputStream.bufferedReader().readText()
        }
    }
}
