package com.enigma.farsilandtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AminVpnService : VpnService() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val networks by lazy { getSystemService(ConnectivityManager::class.java) }
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var cancelled = false
    @Volatile private var active = false
    @Volatile private var request: HttpURLConnection? = null
    private var token = 0
    @Volatile private var startId = 0
    @Volatile private var closed = false
    private var healthFailures = 0
    private var underlying: Network? = null
    private var vpnNetwork: Network? = null
    @Volatile private var cancellationFailure: VpnFailure? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "FreeSocks VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        startId = id
        if (intent?.action == DISCONNECT) {
            cancel()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        if (prepare(this) != null) {
            token = VpnStatus.begin()
            VpnStatus.fail(token, VpnFailure.CONSENT)
            closed = true
            stopSelf(id)
            return START_NOT_STICKY
        }
        active = true
        cancelled = false
        cancellationFailure = null
        closed = false
        healthFailures = 0
        token = VpnStatus.begin()
        startForeground(NOTIFICATION, notification(getString(R.string.vpn_connecting)))
        worker.execute { connect() }
        return START_NOT_STICKY
    }

    private fun connect() {
        try {
            val address = runCatching { VpnSecrets(this).read() }
                .getOrElse { throw VpnProblem(VpnFailure.SETUP) }
            // Censorship can block Android's validation probe even with usable Wi-Fi.
            // Prefer the current physical network without requiring that probe to pass.
            underlying = (listOfNotNull(networks.activeNetwork) + networks.allNetworks).firstOrNull { network ->
                networks.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } ?: throw VpnProblem(VpnFailure.NETWORK)
            val text = if (VpnImport.remote(address)) fetchSubscription(address) else address
            ensureActive()
            val nodes = VpnEngine.nodes(text)
            val protector = object : DialerController {
                override fun protectFd(fd: Long) = protect(fd.toInt())
            }
            LibXray.registerDialerController(protector)
            LibXray.registerListenerController(protector)
            val dns = networks.getLinkProperties(underlying)?.dnsServers
                ?.firstOrNull { it.address.size == 4 }?.hostAddress ?: "1.1.1.1"
            LibXray.setDNS(protector, "$dns:53")
            setUnderlyingNetworks(arrayOf(checkNotNull(underlying)))
            val previousVpns = networks.allNetworks.filter {
                networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.toSet()
            tunnel = Builder().setSession("Amin Player · FreeSocks")
                .setMtu(1500).addAddress("10.111.0.2", 30)
                .addAddress("fd71:616d:696e::2", 126)
                .addRoute("0.0.0.0", 0).addRoute("::", 0)
                .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
                .setConfigureIntent(PendingIntent.getActivity(this, 0,
                    Intent(this, VpnSetupActivity::class.java), PENDING_FLAGS))
                .setBlocking(false).establish() ?: throw VpnProblem(VpnFailure.CONSENT)
            for (attempt in 0 until 20) {
                ensureActive()
                vpnNetwork = networks.allNetworks.firstOrNull { network ->
                    network !in previousVpns && networks.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                if (vpnNetwork != null) break
                Thread.sleep(100)
            }
            if (vpnNetwork == null) throw VpnProblem(VpnFailure.NETWORK)
            for (node in nodes.take(6)) {
                ensureActive()
                try {
                    VpnEngine.invoke("runXray", JSONObject().put("xrayJson",
                        VpnEngine.config(node, checkNotNull(tunnel).fd)))
                    if (probe()) {
                        ensureActive()
                        VpnStatus.connected(token)
                        startForeground(NOTIFICATION, notification(getString(R.string.vpn_connected)))
                        scheduleHealth()
                        return
                    }
                } catch (_: Exception) {
                    ensureActive()
                }
                VpnEngine.stop()
            }
            throw VpnProblem(VpnFailure.NETWORK)
        } catch (e: VpnProblem) {
            finish(e.reason)
        } catch (_: Exception) {
            finish(VpnFailure.ENGINE)
        } catch (_: LinkageError) {
            finish(VpnFailure.ENGINE)
        }
    }

    private fun fetchSubscription(address: String): String {
        var uri = SubscriptionAddress.parse(address)
        repeat(4) {
            ensureActive()
            if (!InetAddress.getAllByName(uri.host).all(SubscriptionAddress::publicAddress)) {
                throw VpnProblem(VpnFailure.SUBSCRIPTION)
            }
            val connection = checkNotNull(underlying).openConnection(uri.toURL()) as HttpURLConnection
            request = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "AminPlayer/1.23 Xray/26.11.9")
                connection.setRequestProperty("Accept", "text/plain, application/json")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location")
                        ?: throw VpnProblem(VpnFailure.SUBSCRIPTION)
                    uri = SubscriptionAddress.parse(uri.resolve(next).toString())
                } else {
                    val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                    if (status != 200 || (length != null && length > SubscriptionAddress.MAX_BYTES)) {
                        throw VpnProblem(VpnFailure.SUBSCRIPTION)
                    }
                    val bytes = connection.inputStream.use { SubscriptionAddress.readBounded(it) }
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
                request = null
            }
        }
        throw VpnProblem(VpnFailure.SUBSCRIPTION)
    }

    private fun probe(): Boolean {
        // Bind to Android's VPN network. A successful direct Wi-Fi request must never
        // be mistaken for a working tunnel when Android falls back after a failure.
        val network = vpnNetwork ?: return false
        val connection = runCatching {
            network.openConnection(URL("https://www.gstatic.com/generate_204")) as HttpURLConnection
        }.getOrElse { return false }
        request = connection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.responseCode == 204
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
            request = null
        }
    }

    private fun scheduleHealth() {
        worker.schedule({
            if (cancelled || closed) return@schedule
            val alive = runCatching { VpnEngine.running() }.getOrDefault(false)
            val link = underlying?.let(networks::getNetworkCapabilities) != null
            healthFailures = if (alive && link && probe()) 0 else healthFailures + 1
            if (!alive || !link || healthFailures >= 2) finish(VpnFailure.DROPPED)
            else scheduleHealth()
        }, 20, TimeUnit.SECONDS)
    }

    private fun ensureActive() {
        if (cancelled || VpnStatus.current.attempt != token) throw VpnProblem(VpnFailure.DROPPED)
    }

    private fun cancel(reason: VpnFailure? = null) {
        cancellationFailure = reason
        cancelled = true
        VpnStatus.stopping()
        request?.disconnect()
        worker.execute { finish(reason) }
    }

    private fun finish(reason: VpnFailure?) {
        if (closed) return
        closed = true
        runCatching { VpnEngine.stop() }
        runCatching { LibXray.resetDNS() }
        runCatching { tunnel?.close() }
        tunnel = null
        active = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelfResult(startId)
        val failure = if (cancelled) cancellationFailure else reason
        if (failure != null) VpnStatus.fail(token, failure)
        else VpnStatus.stop()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, VpnSetupActivity::class.java), PENDING_FLAGS)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, AminVpnService::class.java).setAction(DISCONNECT), PENDING_FLAGS)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.tv_icon)
            .setContentTitle("FreeSocks VPN").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(0, getString(R.string.vpn_disconnect), stop).build()
    }

    override fun onRevoke() { cancel(VpnFailure.CONSENT) }

    override fun onDestroy() {
        if (!closed) cancel(VpnFailure.DROPPED)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "freesocks-vpn"
        private const val NOTIFICATION = 701
        private const val DISCONNECT = "com.enigma.farsilandtv.DISCONNECT_VPN"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun connect(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AminVpnService::class.java))
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, AminVpnService::class.java).setAction(DISCONNECT))
        }
    }
}
