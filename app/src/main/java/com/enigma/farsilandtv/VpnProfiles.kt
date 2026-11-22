package com.enigma.farsilandtv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** How a profile connects: libXray (VLESS) inside our own tunnel, or Android's built-in IKEv2/IPsec (Kerio). */
internal enum class VpnKind { XRAY, IKEV2 }

/**
 * One saved way to connect (1.35). Every profile lives only on this device, encrypted with a key that never leaves
 * Android Keystore. [free] marks shared free methods (FreeSocks keys, public links), tried after personal ones.
 */
internal data class VpnProfile(
    val id: String,
    val kind: VpnKind,
    val name: String,
    val free: Boolean,
    val enabled: Boolean = true,
    /** XRAY: share link, JSON or HTTPS subscription. IKEV2: JSON with server, user, password, psk and ca. */
    val secret: String,
    val lastSuccess: Long = 0,
    val lastTested: Long = 0,
    val lastFailure: VpnFailure? = null,
    val playbackVerified: Long = 0
) {
    // Never print the secret: a data class would otherwise put it in logs and crash reports.
    override fun toString() = "VpnProfile($id, $kind, free=$free, enabled=$enabled)"
}

/** Fields of a Kerio (IKEv2/IPsec) profile. */
internal data class Ikev2Fields(
    val server: String,
    val user: String,
    val password: String,
    val psk: String,
    val ca: String
) {
    fun usesPsk() = psk.isNotEmpty()
    fun valid() = server.isNotBlank() && server.length <= 253 && server.none(Char::isWhitespace) &&
        user.isNotBlank() && user.length <= 256 && (password.isNotEmpty() || psk.isNotEmpty())
    override fun toString() = "Ikev2Fields(server set=${server.isNotEmpty()})"
}

/** The order in which Connect tries profiles. Pure so it can be unit tested. */
internal object VpnPlan {
    /**
     * Personal profiles first, then free ones. Inside each group the most recently successful profile comes first,
     * then profiles that worked earlier, then the rest in the order they were added. [last] moves a profile that just
     * dropped to the end so recovery tries the others first.
     */
    fun order(profiles: List<VpnProfile>, supported: (VpnProfile) -> Boolean, last: String? = null): List<VpnProfile> {
        val usable = profiles.filter { it.enabled && supported(it) }
        fun group(free: Boolean) = usable.filter { it.free == free }.sortedByDescending { it.lastSuccess }
        val ordered = group(false) + group(true)
        if (last == null) return ordered
        return ordered.filter { it.id != last } + ordered.filter { it.id == last }
    }

    fun supported(profile: VpnProfile, sdk: Int) = profile.kind == VpnKind.XRAY || sdk >= 30
}

/** Encrypted, device-only list of profiles. Migrates the single 1.34 profile without touching it. */
internal class VpnProfileStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn-profiles.enc"))

    fun list(): List<VpnProfile> = synchronized(LOCK) { load() }

    fun get(id: String) = list().firstOrNull { it.id == id }

    fun configured() = list().isNotEmpty()

    fun add(kind: VpnKind, name: String, free: Boolean, secret: String): VpnProfile = synchronized(LOCK) {
        val profile = VpnProfile(UUID.randomUUID().toString(), kind, name.trim().take(60), free, secret = secret)
        write(load() + profile)
        profile
    }

    fun update(id: String, change: (VpnProfile) -> VpnProfile) = synchronized(LOCK) {
        val all = load()
        if (all.none { it.id == id }) return@synchronized
        write(all.map { if (it.id == id) change(it).copy(id = id, kind = it.kind) else it })
    }

    fun remove(id: String) = synchronized(LOCK) {
        write(load().filter { it.id != id })
        // The 1.34 file is kept next to the new list until its profile is deleted (it lets 1.34 still read it).
        if (id == LEGACY) VpnSecrets(context).clear()
    }

    fun recordSuccess(id: String) = update(id) {
        val now = System.currentTimeMillis()
        it.copy(lastSuccess = now, lastTested = now, lastFailure = null)
    }

    fun recordFailure(id: String, reason: VpnFailure) = update(id) {
        it.copy(lastTested = System.currentTimeMillis(), lastFailure = reason)
    }

    fun recordPlayback(id: String) = update(id) { it.copy(playbackVerified = System.currentTimeMillis()) }

    private fun load(): List<VpnProfile> {
        if (!file.baseFile.exists()) return migrate()
        val text = SecureBox.decrypt(file.readFully()).toString(Charsets.UTF_8)
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { runCatching { fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    private fun migrate(): List<VpnProfile> {
        val legacy = VpnSecrets(context)
        if (!legacy.configured()) return emptyList()
        val secret = runCatching { legacy.read() }.getOrNull() ?: return emptyList()
        val profile = VpnProfile(LEGACY, VpnKind.XRAY, context.getString(R.string.vpn_profile_legacy), free = false,
            secret = secret)
        write(listOf(profile))
        return listOf(profile)
    }

    private fun write(profiles: List<VpnProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(toJson(it)) }
        val bytes = SecureBox.encrypt(array.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun toJson(p: VpnProfile) = JSONObject().put("id", p.id).put("kind", p.kind.name).put("name", p.name)
        .put("free", p.free).put("enabled", p.enabled).put("secret", p.secret).put("lastSuccess", p.lastSuccess)
        .put("lastTested", p.lastTested).put("lastFailure", p.lastFailure?.name ?: "")
        .put("playbackVerified", p.playbackVerified)

    private fun fromJson(o: JSONObject) = VpnProfile(o.getString("id"), VpnKind.valueOf(o.getString("kind")),
        o.optString("name"), o.optBoolean("free"), o.optBoolean("enabled", true), o.getString("secret"),
        o.optLong("lastSuccess"), o.optLong("lastTested"),
        o.optString("lastFailure").takeIf(String::isNotEmpty)?.let { runCatching { VpnFailure.valueOf(it) }.getOrNull() },
        o.optLong("playbackVerified"))

    companion object {
        const val LEGACY = "legacy-1.34"
        private val LOCK = Any()

        fun ikev2(secret: String): Ikev2Fields = JSONObject(secret).let {
            Ikev2Fields(it.optString("server"), it.optString("user"), it.optString("password"), it.optString("psk"),
                it.optString("ca"))
        }

        fun ikev2Secret(f: Ikev2Fields): String = JSONObject().put("server", f.server.trim()).put("user", f.user.trim())
            .put("password", f.password).put("psk", f.psk).put("ca", f.ca).toString()
    }
}
