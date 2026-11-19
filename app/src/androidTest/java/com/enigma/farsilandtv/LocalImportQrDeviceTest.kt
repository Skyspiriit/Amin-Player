package com.enigma.farsilandtv

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class LocalImportQrDeviceTest {
    @Test fun tvQrIsVisibleNonFocusableDecodesAndClearsWhenClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            LocalVpnImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var image: ImageView? = null
        try {
            for (attempt in 0..80) {
                instrumentation.runOnMainSync {
                    image = all(activity.findViewById(android.R.id.content)).filterIsInstance<ImageView>()
                        .firstOrNull { it.contentDescription == activity.getString(R.string.vpn_local_qr) }
                }
                if (image?.drawable != null) break
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync {
                val qr = checkNotNull(image)
                assertFalse(qr.isFocusable)
                assertFalse(qr.isClickable)
                assertFalse(qr.isSaveEnabled)
                val visible = Rect()
                assertTrue(qr.getGlobalVisibleRect(visible))
                assertEquals(qr.width, visible.width())
                assertEquals(qr.height, visible.height())
                val bitmap = (qr.drawable as BitmapDrawable).bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
                val address = all(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .first { it.text.startsWith("https://") }.text.toString()
                assertEquals(address, result.text)
                assertNull(java.net.URI(result.text).rawQuery)
                val close = all(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                    .first { it.text == activity.getString(R.string.vpn_local_close) }
                assertTrue(close.getGlobalVisibleRect(visible))
                assertEquals(close.height, visible.height())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // Android finishes its window transition after the first idle callback.
            for (attempt in 0..60) {
                var cleared = false
                instrumentation.runOnMainSync { cleared = image?.drawable == null }
                if (cleared) break
                Thread.sleep(50)
            }
            instrumentation.runOnMainSync { assertNull(image?.drawable) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        assertFalse(VpnEngine.running())
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
}
