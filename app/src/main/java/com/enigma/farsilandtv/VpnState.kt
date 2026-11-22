package com.enigma.farsilandtv

import android.os.Handler
import android.os.Looper

internal enum class VpnPhase { OFF, CONNECTING, ON, STOPPING, ERROR }
internal enum class VpnFailure { SETUP, SUBSCRIPTION, NETWORK, ENGINE, DROPPED, CONSENT, SITE, PLAYER }

/**
 * How far a connection has been checked (1.35). A tunnel alone is never shown as connected: Connect only reports
 * «VPN وصل» from [PLAYER] (Farsiland and a playback host answered through the tunnel); [PLAYBACK] is reached
 * once a real episode has played over it.
 */
internal enum class VpnCheck { TUNNEL, INTERNET, CATALOGUE, PLAYER, PLAYBACK }

// An attempt number prevents an old download or health check from reviving a cancelled session.
internal data class VpnSession(
    val attempt: Int = 0,
    val phase: VpnPhase = VpnPhase.OFF,
    val failure: VpnFailure? = null,
    /** Local display name of the profile being tried or in use; never a server address or key. */
    val profile: String? = null,
    val profileId: String? = null,
    val check: VpnCheck? = null,
    val recovering: Boolean = false
) {
    fun begin() = VpnSession(attempt = attempt + 1, phase = VpnPhase.CONNECTING)
    fun stop() = VpnSession(attempt = attempt + 1, phase = VpnPhase.OFF)
    fun stopping() = copy(phase = VpnPhase.STOPPING)
    fun trying(token: Int, name: String) = if (token == attempt && phase == VpnPhase.CONNECTING) {
        copy(profile = name)
    } else this
    fun connected(token: Int, id: String? = null, name: String? = null, check: VpnCheck = VpnCheck.PLAYER) =
        if (token == attempt && phase == VpnPhase.CONNECTING) {
            copy(phase = VpnPhase.ON, profile = name ?: profile, profileId = id, check = check, recovering = false)
        } else this
    fun recovering(token: Int) = if (token == attempt && phase == VpnPhase.ON) {
        copy(phase = VpnPhase.CONNECTING, recovering = true, check = null)
    } else this
    fun playback() = if (phase == VpnPhase.ON) copy(check = VpnCheck.PLAYBACK) else this
    fun failed(token: Int, reason: VpnFailure) = if (token == attempt) {
        copy(phase = VpnPhase.ERROR, failure = reason, check = null, recovering = false)
    } else this
}

internal object VpnStatus {
    private val ui = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<() -> Unit>()
    @Volatile var current = VpnSession()
        private set

    @Synchronized fun begin(): Int {
        update(current.begin())
        return current.attempt
    }

    @Synchronized fun stop() = update(current.stop())
    @Synchronized fun stopping() = update(current.stopping())
    @Synchronized fun trying(token: Int, name: String) = update(current.trying(token, name))
    @Synchronized fun connected(token: Int, id: String, name: String, check: VpnCheck) =
        update(current.connected(token, id, name, check))
    @Synchronized fun recovering(token: Int) = update(current.recovering(token))
    /** Marks the session playback-verified; true only the first time, so the profile is recorded once. */
    @Synchronized fun playback(): Boolean {
        val before = current.check
        update(current.playback())
        return before != VpnCheck.PLAYBACK && current.check == VpnCheck.PLAYBACK
    }
    @Synchronized fun fail(token: Int, reason: VpnFailure) = update(current.failed(token, reason))

    /** Reports a problem found before any attempt started (for example nothing saved). */
    @Synchronized fun failNow(reason: VpnFailure) = fail(begin(), reason)

    @Synchronized fun observe(listener: () -> Unit) { observers.add(listener) }
    @Synchronized fun remove(listener: () -> Unit) { observers.remove(listener) }

    private fun update(value: VpnSession) {
        if (value == current) return
        current = value
        val listeners = observers.toList()
        ui.post { listeners.forEach { it() } }
    }
}
