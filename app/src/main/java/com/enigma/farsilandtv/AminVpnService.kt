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
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

/**
 * Connection manager (1.35). One press of the VPN power button starts this service, which tries every enabled
 * profile on this device in order (personal first, the last one that worked first, then free methods), stops at
 * the first one that reaches Farsiland and a playback host through the tunnel, and keeps it running. Only one engine
 * owns Android's VPN at a time: each failed attempt is fully stopped before the next starts. A manual disconnect
 * cancels everything and is never undone by recovery.
 */
class AminVpnService : VpnService() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    // Probes run here so a slow DNS lookup inside a dead tunnel can never hold the connection manager.
    private val probes = Executors.newCachedThreadPool()
    private val lock = Any()
    private val networks by lazy { getSystemService(ConnectivityManager::class.java) }
    private val store by lazy { VpnProfileStore(this) }
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var platformRunning = false
    @Volatile private var cancelled = false
    @Volatile private var active = false
    @Volatile private var request: HttpURLConnection? = null
    private var token = 0
    @Volatile private var startId = 0
    @Volatile private var closed = false
    private var healthFailures = 0
    private var recoveries = 0
    private var recoveredAt = 0L
    private var underlying: Network? = null
    private var vpnNetwork: Network? = null
    private var current: VpnProfile? = null
    @Volatile private var cancellationFailure: VpnFailure? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Amin Player VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        startId = id
        if (intent?.action == DISCONNECT) {
            if (active) cancel() else stopSelf(id)
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        active = true
        cancelled = false
        cancellationFailure = null
        closed = false
        healthFailures = 0
        recoveries = 0
        current = null
        token = VpnStatus.begin()
        startForeground(NOTIFICATION, notification(getString(R.string.vpn_connecting)))
        worker.execute { connect() }
        return START_NOT_STICKY
    }

    private fun connect() {
        try {
            run(plan())
        } catch (e: VpnProblem) {
            finish(e.reason)
        } catch (_: Exception) {
            finish(VpnFailure.ENGINE)
        } catch (_: LinkageError) {
            finish(VpnFailure.ENGINE)
        } finally {
            // A disconnect may have finished the session while this attempt was still running; anything the attempt
            // started after that is stopped here, so no tunnel outlives the session.
            if (closed) release()
        }
    }

    private fun plan(last: String? = null): List<VpnProfile> {
        val profiles = runCatching { store.list() }.getOrElse { throw VpnProblem(VpnFailure.SETUP) }
        return VpnPlan.order(profiles, { VpnPlan.supported(it, Build.VERSION.SDK_INT) }, last)
            .ifEmpty { throw VpnProblem(VpnFailure.SETUP) }
    }

    /** Tries [plan] in order until one profile is verified. Bounded: every profile once, within [TOTAL_LIMIT]. */
    private fun run(plan: List<VpnProfile>) {
        var reason = VpnFailure.NETWORK
        val deadline = SystemClock.elapsedRealtime() + TOTAL_LIMIT
        for (profile in plan) {
            ensureActive()
            if (SystemClock.elapsedRealtime() > deadline) break
            VpnStatus.trying(token, profile.name)
            val result = runCatching {
                when (profile.kind) {
                    VpnKind.XRAY -> attemptXray(profile)
                    VpnKind.IKEV2 -> attemptPlatform(profile)
                }
            }
            ensureActive()
            val check = result.getOrNull()
            if (check != null) {
                current = profile
                runCatching { store.recordSuccess(profile.id) }
                VpnStatus.connected(token, profile.id, profile.name, check)
                startForeground(NOTIFICATION, notification(getString(R.string.vpn_connected)))
                scheduleHealth()
                return
            }
            reason = (result.exceptionOrNull() as? VpnProblem)?.reason ?: VpnFailure.ENGINE
            runCatching { store.recordFailure(profile.id, reason) }
            release()
        }
        throw VpnProblem(reason)
    }

    private fun physicalNetwork(): Network {
        // Censorship can block Android's validation probe even with usable Wi-Fi.
        // Prefer the current physical network without requiring that probe to pass.
        return (listOfNotNull(networks.activeNetwork) + networks.allNetworks).firstOrNull { network ->
            networks.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } ?: throw VpnProblem(VpnFailure.NETWORK)
    }

    private fun vpnNetworks() = networks.allNetworks.filter {
        networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.toSet()

    private fun waitForVpnNetwork(previous: Set<Network>, limit: Long): Network {
        val end = SystemClock.elapsedRealtime() + limit
        while (SystemClock.elapsedRealtime() < end) {
            ensureActive()
            vpnNetworks().firstOrNull { it !in previous }?.let { return it }
            Thread.sleep(150)
        }
        throw VpnProblem(VpnFailure.NETWORK)
    }

    // ---- V2Ray / Xray (VLESS with TLS or REALITY) in our own tunnel ----
    private fun attemptXray(profile: VpnProfile): VpnCheck {
        if (prepare(this) != null) throw VpnProblem(VpnFailure.CONSENT)
        stopPlatform()
        val network = physicalNetwork().also { underlying = it }
        val text = if (VpnImport.remote(profile.secret)) fetchSubscription(profile.secret) else profile.secret
        ensureActive()
        val nodes = VpnEngine.nodes(text)
        val protector = object : DialerController {
            override fun protectFd(fd: Long) = protect(fd.toInt())
        }
        LibXray.registerDialerController(protector)
        LibXray.registerListenerController(protector)
        val dns = networks.getLinkProperties(network)?.dnsServers
            ?.firstOrNull { it.address.size == 4 }?.hostAddress ?: "1.1.1.1"
        LibXray.setDNS(protector, "$dns:53")
        setUnderlyingNetworks(arrayOf(network))
        val previous = vpnNetworks()
        val established = Builder().setSession("Amin Player · " + profile.name)
            .setMtu(1500).addAddress("10.111.0.2", 30)
            .addAddress("fd71:616d:696e::2", 126)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
            .setConfigureIntent(PendingIntent.getActivity(this, 0,
                Intent(this, VpnSetupActivity::class.java), PENDING_FLAGS))
            .setBlocking(false).establish() ?: throw VpnProblem(VpnFailure.CONSENT)
        synchronized(lock) {
            if (closed) { runCatching { established.close() }; throw VpnProblem(VpnFailure.DROPPED) }
            tunnel = established
        }
        vpnNetwork = waitForVpnNetwork(previous, 3000)
        var reason = VpnFailure.NETWORK
        for (node in nodes.take(4)) {
            ensureActive()
            try {
                VpnEngine.invoke("runXray", JSONObject().put("xrayJson",
                    VpnEngine.config(node, checkNotNull(tunnel).fd)))
                return verify()
            } catch (e: VpnProblem) {
                ensureActive()
                reason = e.reason
            } catch (_: Exception) {
                ensureActive()
            }
            runCatching { VpnEngine.stop() }
        }
        throw VpnProblem(reason)
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
                connection.setRequestProperty("User-Agent", "AminPlayer/1.35 Xray/26.11.9")
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
            } catch (e: VpnProblem) {
                throw e
            } catch (_: Exception) {
                throw VpnProblem(VpnFailure.SUBSCRIPTION)
            } finally {
                connection.disconnect()
                request = null
            }
        }
        throw VpnProblem(VpnFailure.SUBSCRIPTION)
    }

    // ---- Kerio / IKEv2 through Android's built-in client ----
    private fun attemptPlatform(profile: VpnProfile): VpnCheck {
        if (Build.VERSION.SDK_INT < 30) throw VpnProblem(VpnFailure.ENGINE)
        closeTunnel()
        val fields = VpnProfileStore.ikev2(profile.secret)
        if (!fields.valid()) throw VpnProblem(VpnFailure.SETUP)
        val previous = vpnNetworks()
        // Consent is asked when the profile is saved; a missing one is a setup problem, not a new prompt here.
        val consent = try { PlatformVpn.provision(this, fields) } catch (_: Exception) {
            throw VpnProblem(VpnFailure.SETUP)
        }
        if (consent != null) throw VpnProblem(VpnFailure.CONSENT)
        synchronized(lock) {
            if (closed) throw VpnProblem(VpnFailure.DROPPED)
            platformRunning = true
            try { PlatformVpn.start(this) } catch (_: SecurityException) { throw VpnProblem(VpnFailure.CONSENT) }
        }
        underlying = runCatching { physicalNetwork() }.getOrNull()
        vpnNetwork = waitForVpnNetwork(previous, PLATFORM_LIMIT)
        return verify()
    }

    // ---- verification ----
    /**
     * Checks, through the tunnel only, that Farsiland's catalogue and one of its playback hosts answer over HTTPS
     * (normal certificate checks; any HTTP answer counts, nothing is downloaded and no cookies are sent).
     */
    private fun verify(): VpnCheck {
        ensureActive()
        val catalogue = reach("https://farsiland.com/")
        ensureActive()
        if (!catalogue) {
            val internet = reach("https://www.gstatic.com/generate_204", expect = 204)
            throw VpnProblem(if (internet) VpnFailure.SITE else VpnFailure.NETWORK)
        }
        val player = PLAYER_HOSTS.any { ensureActive(); reach(it) }
        if (!player) throw VpnProblem(VpnFailure.PLAYER)
        return VpnCheck.PLAYER
    }

    private fun reach(url: String, expect: Int? = null): Boolean {
        val network = vpnNetwork ?: return false
        val task = try { probes.submit<Boolean> { probe(network, url, expect) } } catch (_: Exception) { return false }
        return try {
            task.get(PROBE_LIMIT, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            false
        }
    }

    private fun probe(network: Network, url: String, expect: Int?): Boolean {
        // Bind to Android's VPN network. A successful direct Wi-Fi request must never
        // be mistaken for a working tunnel when Android falls back after a failure.
        val connection = runCatching { network.openConnection(URL(url)) as HttpURLConnection }.getOrElse { return false }
        request = connection
        return try {
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = PROBE_TIMEOUT
            connection.readTimeout = PROBE_TIMEOUT
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AminPlayer/1.35")
            val code = connection.responseCode
            if (expect != null) code == expect else code in 100..599
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
            val alive = when (current?.kind) {
                VpnKind.XRAY -> runCatching { VpnEngine.running() }.getOrDefault(false) &&
                    underlying?.let(networks::getNetworkCapabilities) != null
                VpnKind.IKEV2 -> vpnNetwork?.let(networks::getNetworkCapabilities) != null
                null -> false
            }
            healthFailures = if (alive && reach("https://farsiland.com/")) 0 else healthFailures + 1
            if (!alive || healthFailures >= 2) recover() else scheduleHealth()
        }, HEALTH_INTERVAL, TimeUnit.SECONDS)
    }

    /**
     * The tunnel stopped working while the viewer still wants it: try the profiles again, the one that dropped
     * last. Bounded to [MAX_RECOVERIES] within ten minutes. A manual disconnect sets [cancelled] and always wins.
     */
    private fun recover() {
        if (cancelled || closed) return
        val now = SystemClock.elapsedRealtime()
        if (now - recoveredAt > RECOVERY_WINDOW) recoveries = 0
        if (recoveries >= MAX_RECOVERIES) { finish(VpnFailure.DROPPED); return }
        recoveries++
        recoveredAt = now
        healthFailures = 0
        val dropped = current?.id
        current = null
        release()
        VpnStatus.recovering(token)
        startForeground(NOTIFICATION, notification(getString(R.string.vpn_reconnecting)))
        try {
            run(plan(dropped))
        } catch (e: VpnProblem) {
            finish(if (e.reason == VpnFailure.SETUP) VpnFailure.DROPPED else e.reason)
        } catch (_: Throwable) {
            finish(VpnFailure.DROPPED)
        }
    }

    private fun ensureActive() {
        if (cancelled || VpnStatus.current.attempt != token) throw VpnProblem(VpnFailure.DROPPED)
    }

    /**
     * Manual disconnect (or Android revoking the VPN): takes effect at once on its own thread, whatever the connection
     * manager is doing; the manager notices [cancelled] and stops, and never reconnects afterwards.
     */
    private fun cancel(reason: VpnFailure? = null) {
        if (cancelled) return
        cancellationFailure = reason
        cancelled = true
        VpnStatus.stopping()
        Thread({
            runCatching { request?.disconnect() }
            finish(reason)
        }, "amin-vpn-stop").start()
    }

    private fun stopPlatform() {
        synchronized(lock) {
            if (!platformRunning) return
            PlatformVpn.stop(this)
            platformRunning = false
        }
    }

    private fun closeTunnel() {
        synchronized(lock) {
            runCatching { VpnEngine.stop() }
            runCatching { LibXray.resetDNS() }
            runCatching { tunnel?.close() }
            tunnel = null
        }
    }

    /** Stops whichever engine is running, so the next attempt starts from nothing. */
    private fun release() {
        closeTunnel()
        stopPlatform()
        vpnNetwork = null
    }

    private fun finish(reason: VpnFailure?) {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        release()
        current = null
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
            .setContentTitle("Amin Player VPN").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(0, getString(R.string.vpn_disconnect), stop).build()
    }

    override fun onRevoke() {
        // Starting Android's own IKEv2 VPN replaces our tunnel; that is not the viewer revoking anything.
        if (tunnel == null || platformRunning) return
        cancel(VpnFailure.CONSENT)
    }

    override fun onDestroy() {
        if (!closed) cancel(VpnFailure.DROPPED)
        worker.shutdown()
        probes.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "freesocks-vpn"
        private const val NOTIFICATION = 701
        private const val DISCONNECT = "com.enigma.farsilandtv.DISCONNECT_VPN"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val PROBE_TIMEOUT = 6000
        private const val PROBE_LIMIT = 8000L
        private const val PLATFORM_LIMIT = 25_000L
        private const val TOTAL_LIMIT = 4 * 60_000L
        private const val HEALTH_INTERVAL = 30L
        private const val MAX_RECOVERIES = 3
        private const val RECOVERY_WINDOW = 10 * 60_000L
        private val PLAYER_HOSTS = listOf("https://farsiland.buzz/", "https://farsiplex.com/")

        fun connect(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AminVpnService::class.java))
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, AminVpnService::class.java).setAction(DISCONNECT))
        }
    }
}
