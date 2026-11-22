package com.enigma.farsilandtv

import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Kerio (and any standard IKEv2/IPsec server) through Android's own built-in IKEv2 client (Android 11+). Kerio's
 * proprietary "Kerio VPN" protocol has no Android implementation, so only Kerio's IKEv2 mode is used. The server
 * certificate is always checked: against the imported CA when there is one, otherwise against Android's trusted CAs.
 */
internal object PlatformVpn {
    val supported get() = Build.VERSION.SDK_INT >= 30

    fun certificate(pem: String): X509Certificate? {
        if (pem.isBlank()) return null
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.trim().toByteArray(Charsets.US_ASCII))) as X509Certificate
    }

    fun looksLikeCertificate(text: String) = text.trim().let {
        it.startsWith("-----BEGIN CERTIFICATE-----") && it.endsWith("-----END CERTIFICATE-----") && it.length < 16_384
    } && runCatching { certificate(text) }.isSuccess

    @RequiresApi(30)
    private fun profile(fields: Ikev2Fields): Ikev2VpnProfile {
        val builder = Ikev2VpnProfile.Builder(fields.server.trim(), fields.user.trim())
        if (fields.usesPsk()) builder.setAuthPsk(fields.psk.toByteArray(Charsets.UTF_8))
        else builder.setAuthUsernamePassword(fields.user.trim(), fields.password, certificate(fields.ca))
        return builder.setBypassable(false).setMetered(false).setMaxMtu(1360).build()
    }

    /** Saves [fields] as this app's Android VPN profile. Returns Android's consent screen when it is still needed. */
    @RequiresApi(30)
    fun provision(context: Context, fields: Ikev2Fields): Intent? =
        context.getSystemService(VpnManager::class.java).provisionVpnProfile(profile(fields))

    @RequiresApi(30)
    fun start(context: Context) = context.getSystemService(VpnManager::class.java).startProvisionedVpnProfile()

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.getSystemService(VpnManager::class.java).stopProvisionedVpnProfile() }
        }
    }

    fun forget(context: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.getSystemService(VpnManager::class.java).deleteProvisionedVpnProfile() }
        }
    }
}
