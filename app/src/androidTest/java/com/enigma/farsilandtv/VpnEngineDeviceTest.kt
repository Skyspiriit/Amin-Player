package com.enigma.farsilandtv

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject
import java.io.File

// These tests use a reserved documentation address and a public fixture UUID.
// They never connect to FreeSocks or create a live VPN.
class VpnEngineDeviceTest {
    @Test fun setupKeepsSubscriptionEntryPrivate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            VpnSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val field = checkNotNull(findInput(activity.findViewById(android.R.id.content)))
                assertTrue(field.transformationMethod is PasswordTransformationMethod)
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    field.inputType and InputType.TYPE_MASK_VARIATION)
                assertFalse(field.isSaveEnabled)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun nativeEngineLoadsAndValidatesVlessOnTheDevice() {
        val version = VpnEngine.invoke("xrayVersion").getString("version")
        assertTrue(version.contains("26.11.9"))
        val nodes = VpnEngine.nodes(FIXTURE)
        assertEquals(1, nodes.size)
        val config = JSONObject(VpnEngine.config(nodes.single(), 42))
        assertEquals("none", config.getJSONObject("log").getString("loglevel"))
        assertEquals("42", config.getJSONObject("env").getString("xray.tun.fd"))
        assertEquals(1, config.getJSONArray("outbounds").length())
        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        VpnEngine.invoke("testXray", JSONObject().put("xrayJson", JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("outbounds", config.getJSONArray("outbounds")).toString()))
        assertFalse(VpnEngine.running())
    }

    @Test fun unsupportedOrUnencryptedSubscriptionIsRejected() {
        assertTrue(runCatching { VpnEngine.nodes("<html>Sign in</html>") }.isFailure)
        assertTrue(runCatching {
            VpnEngine.nodes(FIXTURE.replace("security=tls", "security=none"))
        }.isFailure)
        assertFalse(VpnEngine.running())
    }

    @Test fun subscriptionIsEncryptedByAndroidKeystoreAndCanBeForgotten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VpnProfileStore(context)
        assertTrue("Run this fixture only on a fresh VPN test installation", store.list().isEmpty())
        val fixtureUrl = "https://freesocks.org/sub/local-native-fixture"
        var id: String? = null
        try {
            id = VpnSetupActivity.addXray(context, fixtureUrl, false).id
            assertTrue(store.configured())
            assertEquals(fixtureUrl, store.get(id)?.secret)
            val file = File(context.noBackupFilesDir, "vpn-profiles.enc")
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(fixtureUrl))
        } finally {
            id?.let(store::remove)
        }
        assertFalse(store.configured())
    }

    @Test fun inlineVlessFilesAreValidatedAndStoredWithoutAnExternalUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VpnProfileStore(context)
        assertTrue("Run this fixture before configuring a live VPN", store.list().isEmpty())
        var id: String? = null
        try {
            id = VpnSetupActivity.addXray(context, FIXTURE, false).id
            assertEquals(FIXTURE, store.get(id)?.secret)
            assertFalse(VpnImport.remote(store.get(id)!!.secret))
            assertTrue(runCatching { VpnSetupActivity.addXray(context, "<html>not a VPN file</html>", false) }.isFailure)
            assertEquals(FIXTURE, store.get(id)?.secret)
        } finally { id?.let(store::remove) }
    }

    private companion object {
        const val FIXTURE = "vless://00000000-0000-4000-8000-000000000001@192.0.2.10:443" +
            "?encryption=none&type=tcp&security=tls&sni=example.com#fixture"
    }

    private fun findInput(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findInput(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
