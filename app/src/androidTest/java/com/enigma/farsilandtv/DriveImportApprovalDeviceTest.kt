package com.enigma.farsilandtv

import android.app.AlertDialog
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run only in the disposable emulator: never replace a real box's saved VPN for a test. */
class DriveImportApprovalDeviceTest {
    @Test fun invalidCancelledAndRepeatedImportsRequireTvApprovalBeforeEncryptedReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val secrets = VpnSecrets(context)
        assumeFalse("A pre-existing private configuration must be left untouched", secrets.configured())
        val first = "vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=tls&sni=example.com&type=tcp#AminImportFixture"
        val second = "vless://22222222-2222-4222-8222-222222222222@example.org:443?encryption=none&security=tls&sni=example.org&type=tcp#AminImportFixtureTwo"
        secrets.save(first)
        val activity = instrumentation.startActivitySync(Intent(context, LocalVpnImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(LocalVpnImportActivity.DRIVE_IMPORT, true)) as LocalVpnImportActivity
        fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        fun await(label: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end) {
                var result = false
                instrumentation.runOnMainSync { result = condition() }
                if (result) return
                Thread.sleep(50)
            }
            fail(label)
        }
        fun received(value: String) {
            val method = activity.javaClass.getDeclaredMethod("received", ByteArray::class.java).apply { isAccessible = true }
            method.invoke(activity, value.toByteArray(Charsets.UTF_8))
        }
        try {
            await("QR receiver ready") { field("session") is LocalVpnImportServer }
            received("این یک یادداشت است، نه تنظیمات اتصال")
            await("Unsupported content message") {
                (field("status") as android.widget.TextView).text == context.getString(R.string.vpn_local_invalid)
            }
            assertEquals(first, secrets.read())
            received(second)
            await("TV replacement confirmation") { (field("confirmation") as? AlertDialog)?.isShowing == true }
            assertEquals(first, secrets.read())
            instrumentation.runOnMainSync {
                val dialog = field("confirmation") as AlertDialog
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus())
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            await("Cancellation closes confirmation") { field("confirmation") == null }
            assertEquals(first, secrets.read())
            received(second)
            await("Repeated selection requires a fresh confirmation") { (field("confirmation") as? AlertDialog)?.isShowing == true }
            assertEquals(first, secrets.read())
            instrumentation.runOnMainSync { (field("confirmation") as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end && secrets.read() != second) Thread.sleep(50)
            assertEquals(second, secrets.read())
            val stored = java.io.File(context.noBackupFilesDir, "vpn-subscription.enc").readBytes()
            assertFalse(stored.toString(Charsets.UTF_8).contains("example.org"))
            assertFalse(VpnEngine.running())
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            secrets.clear()
        }
    }
}
