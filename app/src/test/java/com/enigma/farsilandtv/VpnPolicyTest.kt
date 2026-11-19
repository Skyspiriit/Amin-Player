package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class VpnPolicyTest {
    @Test fun subscriptionValidationRetainsTheExactOpaqueToken() {
        val url = "https://freesocks.org/sub/test-token?key=a%2Bb%2Fc"
        assertEquals(url, SubscriptionAddress.parse(" $url\n").toASCIIString())
        assertEquals(8443, SubscriptionAddress.parse("https://vpn.example.com:8443/sub/token").port)
    }

    @Test fun invalidOrLocalSubscriptionAddressesAreRejectedWithoutEchoingThem() {
        val values = listOf(
            "http://freesocks.org/sub/private-token", "file:///private-token", "vless://private-token",
            "https://user:private-token@freesocks.org/", "https://freesocks.org/#private-token",
            "https://127.0.0.1/private-token", "https://192.168.1.1/private-token",
            "https://[::1]/private-token", "https://[fd00::1]/private-token",
            "https://localhost/private-token", "https://router.local/private-token",
            "https://freesocks.org/sub/one\nhttps://freesocks.org/sub/private-token"
        )
        for (value in values) {
            val result = runCatching { SubscriptionAddress.parse(value) }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("private-token"))
        }
    }

    @Test fun oversizedOrTruncatedImportsCannotBeSilentlyAccepted() {
        assertEquals("1234", SubscriptionAddress.readBounded(ByteArrayInputStream("1234".toByteArray()), 4)
            .toString(Charsets.UTF_8))
        assertTrue(runCatching {
            SubscriptionAddress.readBounded(ByteArrayInputStream("12345".toByteArray()), 4)
        }.isFailure)
    }

    @Test fun cancelledAndReplacedConnectionsCannotBecomeConnectedFromAnOldCallback() {
        val connecting = VpnSession().begin()
        val stopped = connecting.stopping().stop()
        assertEquals(stopped, stopped.connected(connecting.attempt))
        val retry = stopped.begin()
        assertEquals(retry, retry.failed(connecting.attempt, VpnFailure.NETWORK))
        assertEquals(VpnPhase.ON, retry.connected(retry.attempt).phase)
    }

    @Test fun failedConnectionCanBeRetriedAndStoppedWithoutKeepingAnOldError() {
        val connecting = VpnSession().begin()
        val failed = connecting.failed(connecting.attempt, VpnFailure.SUBSCRIPTION)
        assertEquals(VpnPhase.ERROR, failed.phase)
        val retried = failed.begin()
        assertEquals(null, retried.failure)
        assertEquals(VpnPhase.CONNECTING, retried.phase)
        assertEquals(VpnPhase.OFF, retried.stop().phase)
    }
}
