package com.enigma.farsilandtv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Explicit, short-lived LAN listener. Standard TLS, with a temporary self-signed server certificate. */
internal class LocalVpnImportServer private constructor(
    address: Inet4Address,
    private val prefixLength: Int,
    private val page: ByteArray,
    private val onUpload: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
    lifetimeMillis: Long
) : AutoCloseable {
    private val alias = "amin-player-import-${UUID.randomUUID()}"
    private val stopped = AtomicBoolean()
    private val pending = AtomicBoolean()
    private val failures = AtomicInteger()
    private val peers = java.util.Collections.newSetFromMap(ConcurrentHashMap<SSLSocket, Boolean>())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private val expiry = ScheduledThreadPoolExecutor(1)
    private val server: SSLServerSocket
    val certificate: X509Certificate
    val code: String = String.format(java.util.Locale.ROOT, "%08d", SecureRandom().nextInt(100_000_000))
    val deadline = SystemClock.elapsedRealtime() + lifetimeMillis
    val url: String
    val fingerprint: String
    val closed get() = stopped.get()
    private val scriptHash = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256")
        .digest(page.toString(Charsets.UTF_8).substringAfter("<script>", "").substringBefore("</script>")
            .toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)

    init {
        try {
            val now = System.currentTimeMillis()
            // This short-lived identity stays only in RAM. Some TV Keystores cannot sign TLS
            // handshakes. The actual imported profile still uses Android Keystore encryption.
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val subject = X500Name("CN=Amin Player private import")
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger(128, SecureRandom()).add(BigInteger.ONE),
                Date(now - 60_000), Date(now + lifetimeMillis + 60_000), subject, pair.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.subjectAlternativeName, false,
                    GeneralNames(GeneralName(GeneralName.iPAddress, address.hostAddress)))
            val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            certificate = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(holder.encoded.inputStream()) as X509Certificate
            certificate.verify(certificate.publicKey)
            val key = pair.private
            val manager = object : X509KeyManager {
                override fun chooseServerAlias(type: String?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?): String? {
                    return if (type == "RSA") alias else null
                }
                override fun getServerAliases(type: String?, issuers: Array<java.security.Principal>?) =
                    if (type == "RSA") arrayOf(alias) else null
                override fun getCertificateChain(name: String?): Array<X509Certificate>? = if (name == alias) arrayOf(certificate) else null
                override fun getPrivateKey(name: String?): PrivateKey? = if (name == alias) key else null
                override fun getClientAliases(type: String?, issuers: Array<java.security.Principal>?) = null
                override fun chooseClientAlias(types: Array<String>?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?) = null
            }
            val tls = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, SecureRandom()) }
            server = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                useClientMode = false
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                enabledCipherSuites = supportedCipherSuites.filter {
                    it in setOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_AES_128_GCM_SHA256",
                        "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")
                }.toTypedArray()
                bind(InetSocketAddress(address, 0), 4)
            }
            url = "https://${address.hostAddress}:${server.localPort}"
            fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
            expiry.schedule({ close() }, lifetimeMillis, TimeUnit.MILLISECONDS)
            Thread({ accept() }, "private-vpn-import").start()
        } catch (e: Exception) {
            workers.shutdownNow()
            expiry.shutdownNow()
            throw e
        }
    }

    private fun accept() {
        try {
            while (!closed) {
                val peer = server.accept() as SSLSocket
                if (!sameSubnet(server.inetAddress, peer.inetAddress, prefixLength)) {
                    peer.close(); continue
                }
                peers.add(peer)
                try { workers.execute { peer.use { serve(it) }; peers.remove(peer) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { peers.remove(peer); peer.close() }
            }
        } catch (_: Exception) { if (!closed) close() }
    }

    private fun serve(peer: SSLSocket) {
        try {
            peer.soTimeout = 8_000
            peer.startHandshake()
            val input = peer.inputStream.buffered()
            val request = LocalImportHttp.read(input)
            if (closed || SystemClock.elapsedRealtime() >= deadline) return
            if (request.headers["host"] != url.removePrefix("https://")) { reply(peer, 400); return }
            when {
                request.method == "GET" && request.path == "/" -> reply(peer, 200, page, "text/html; charset=utf-8")
                request.method == "POST" && request.path == "/import" -> {
                    if (request.headers["origin"] != url) { reply(peer, 403); return }
                    val supplied = request.headers["x-amin-pairing-code"].orEmpty()
                    if (!MessageDigest.isEqual(code.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
                        reply(peer, 401)
                        if (failures.incrementAndGet() >= 5) close()
                        return
                    }
                    val length = request.headers["content-length"]?.toIntOrNull()
                    if (length == null || length !in 1..SubscriptionAddress.MAX_BYTES ||
                        request.headers["content-type"] != "application/octet-stream") { reply(peer, 413); return }
                    if (!pending.compareAndSet(false, true)) { reply(peer, 409); return }
                    val bytes = try { LocalImportHttp.body(input, length) }
                    catch (e: Exception) { pending.set(false); throw e }
                    // Respond first. No secret or file name is ever reflected in the page or response.
                    try { reply(peer, 202) } catch (e: Exception) { bytes.fill(0); pending.set(false); throw e }
                    if (closed) bytes.fill(0) else try { onUpload(bytes) }
                    catch (e: Exception) { bytes.fill(0); pending.set(false); throw e }
                }
                else -> reply(peer, 404)
            }
        } catch (_: Exception) { runCatching { reply(peer, 400) } }
    }

    fun retry() { pending.set(false) }

    private fun reply(peer: SSLSocket, code: Int, body: ByteArray = ByteArray(0), type: String = "text/plain") {
        val head = "HTTP/1.1 $code Response\r\nContent-Length: ${body.size}\r\nContent-Type: $type\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nPragma: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-$scriptHash'; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n\r\n"
        peer.outputStream.apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server.close() }
        peers.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        expiry.shutdownNow()
        onClosed()
    }

    companion object {
        fun start(context: Context, onUpload: (ByteArray) -> Unit, onClosed: () -> Unit): LocalVpnImportServer {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val candidate = (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct().firstNotNullOfOrNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) null
                else manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                    it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 8..30
                }
            } ?: error("Connect this TV to Wi-Fi or Ethernet")
            return LocalVpnImportServer(candidate.address as Inet4Address, candidate.prefixLength,
                context.assets.open("vpn-import.html").use { it.readBytes() }, onUpload, onClosed, 600_000)
        }

        internal fun testing(address: Inet4Address, onUpload: (ByteArray) -> Unit, lifetimeMillis: Long = 60_000,
            page: ByteArray = "test".toByteArray()) =
            LocalVpnImportServer(address, 32, page, onUpload, {}, lifetimeMillis)

        internal fun sameSubnet(local: InetAddress, remote: InetAddress, prefix: Int): Boolean {
            if (local.address.size != 4 || remote.address.size != 4 || prefix !in 0..32) return false
            val a = local.address
            val b = remote.address
            for (i in 0 until 4) {
                val bits = (prefix - 8 * i).coerceIn(0, 8)
                val mask = (0xff shl (8 - bits)) and 0xff
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            }
            return true
        }
    }
}
