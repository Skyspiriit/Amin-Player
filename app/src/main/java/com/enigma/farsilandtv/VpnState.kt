package com.enigma.farsilandtv

import android.os.Handler
import android.os.Looper

internal enum class VpnPhase { OFF, CONNECTING, ON, STOPPING, ERROR }
internal enum class VpnFailure { SETUP, SUBSCRIPTION, NETWORK, ENGINE, DROPPED, CONSENT }

// An attempt number prevents an old download or health check from reviving a cancelled session.
internal data class VpnSession(
    val attempt: Int = 0,
    val phase: VpnPhase = VpnPhase.OFF,
    val failure: VpnFailure? = null
) {
    fun begin() = copy(attempt = attempt + 1, phase = VpnPhase.CONNECTING, failure = null)
    fun stop() = copy(attempt = attempt + 1, phase = VpnPhase.OFF, failure = null)
    fun stopping() = copy(phase = VpnPhase.STOPPING)
    fun connected(token: Int) = if (token == attempt && phase == VpnPhase.CONNECTING) {
        copy(phase = VpnPhase.ON)
    } else this
    fun failed(token: Int, reason: VpnFailure) = if (token == attempt) {
        copy(phase = VpnPhase.ERROR, failure = reason)
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
    @Synchronized fun connected(token: Int) = update(current.connected(token))
    @Synchronized fun fail(token: Int, reason: VpnFailure) = update(current.failed(token, reason))

    @Synchronized fun observe(listener: () -> Unit) { observers.add(listener) }
    @Synchronized fun remove(listener: () -> Unit) { observers.remove(listener) }

    private fun update(value: VpnSession) {
        current = value
        val listeners = observers.toList()
        ui.post { listeners.forEach { it() } }
    }
}
