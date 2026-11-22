package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnManagerTest {
    private fun profile(id: String, free: Boolean = false, kind: VpnKind = VpnKind.XRAY, enabled: Boolean = true,
                        success: Long = 0) =
        VpnProfile(id, kind, id, free, enabled, secret = "secret-$id", lastSuccess = success)

    private val all = { _: VpnProfile -> true }

    @Test fun personalProfilesAreTriedBeforeFreeOnes() {
        val order = VpnPlan.order(listOf(profile("free1", free = true, success = 900), profile("mine1"),
            profile("free2", free = true), profile("mine2")), all).map { it.id }
        assertEquals(listOf("mine1", "mine2", "free1", "free2"), order)
    }

    @Test fun theLastSuccessfulPersonalProfileComesFirst() {
        val order = VpnPlan.order(listOf(profile("old", success = 100), profile("never"), profile("recent", success = 500),
            profile("kerio", kind = VpnKind.IKEV2, success = 300)), all).map { it.id }
        assertEquals(listOf("recent", "kerio", "old", "never"), order)
    }

    @Test fun disabledAndUnsupportedProfilesAreSkipped() {
        val profiles = listOf(profile("off", enabled = false), profile("kerio", kind = VpnKind.IKEV2), profile("xray"))
        assertEquals(listOf("xray"), VpnPlan.order(profiles, { VpnPlan.supported(it, 29) }).map { it.id })
        assertEquals(listOf("kerio", "xray"), VpnPlan.order(profiles, { VpnPlan.supported(it, 30) }).map { it.id })
        assertTrue(VpnPlan.order(emptyList(), all).isEmpty())
    }

    @Test fun aProfileThatDroppedIsTriedLastWhenRecovering() {
        val profiles = listOf(profile("a", success = 900), profile("b", success = 100), profile("free", free = true))
        assertEquals(listOf("b", "free", "a"), VpnPlan.order(profiles, all, last = "a").map { it.id })
    }

    @Test fun profilesNeverPrintTheirSecrets() {
        val text = profile("x").toString() + Ikev2Fields("vpn.example.com", "mother", "pa55word", "", "").toString()
        assertFalse(text.contains("secret-x"))
        assertFalse(text.contains("pa55word"))
        assertFalse(text.contains("mother"))
    }

    @Test fun kerioFieldsNeedServerUserAndPasswordOrPsk() {
        assertTrue(Ikev2Fields("vpn.example.com", "user", "pass", "", "").valid())
        assertTrue(Ikev2Fields("vpn.example.com", "user", "", "shared", "").valid())
        assertFalse(Ikev2Fields("", "user", "pass", "", "").valid())
        assertFalse(Ikev2Fields("vpn example.com", "user", "pass", "", "").valid())
        assertFalse(Ikev2Fields("vpn.example.com", "", "pass", "", "").valid())
        assertFalse(Ikev2Fields("vpn.example.com", "user", "", "", "").valid())
    }

    @Test fun connectedNeedsTheCurrentAttemptAndAStillConnectingSession() {
        val connecting = VpnSession().begin()
        val on = connecting.connected(connecting.attempt, "id", "Mine", VpnCheck.PLAYER)
        assertEquals(VpnPhase.ON, on.phase)
        assertEquals("id", on.profileId)
        // A cancelled or stopped attempt can never become connected from a late callback.
        val stopping = connecting.stopping()
        assertEquals(stopping, stopping.connected(connecting.attempt, "id", "Mine"))
        val stopped = stopping.stop()
        assertEquals(stopped, stopped.connected(connecting.attempt, "id", "Mine"))
        assertNull(stopped.profileId)
    }

    @Test fun recoveryKeepsTheAttemptButAManualStopEndsIt() {
        val connecting = VpnSession().begin()
        val on = connecting.connected(connecting.attempt, "id", "Mine")
        val recovering = on.recovering(on.attempt)
        assertEquals(VpnPhase.CONNECTING, recovering.phase)
        assertTrue(recovering.recovering)
        assertEquals(on.attempt, recovering.attempt)
        val stopped = recovering.stopping().stop()
        assertEquals(VpnPhase.OFF, stopped.phase)
        // After a manual stop, the old attempt can neither reconnect nor start recovering again.
        assertEquals(stopped, stopped.connected(on.attempt, "id", "Mine"))
        assertEquals(stopped, stopped.recovering(on.attempt))
    }

    @Test fun playbackIsOnlyConfirmedWhileConnected() {
        val connecting = VpnSession().begin()
        assertNull(connecting.playback().check)
        val on = connecting.connected(connecting.attempt, "id", "Mine")
        assertEquals(VpnCheck.PLAYER, on.check)
        assertEquals(VpnCheck.PLAYBACK, on.playback().check)
        val failed = on.failed(on.attempt, VpnFailure.DROPPED)
        assertNull(failed.check)
        assertEquals(VpnPhase.ERROR, failed.phase)
    }
}
