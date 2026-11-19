package com.enigma.farsilandtv

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class LocalImportQrTest {
    @Test fun qrDecodesToExactLocalPageAtSmallAndTvSizes() {
        val address = "https://192.168.50.20:54321"
        for (size in listOf(160, 496, 744)) {
            val matrix = LocalImportQr.matrix(address, size)
            val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
            assertEquals(address, decoded.text)
            assertFalse(matrix[0, 0])
        }
    }

    @Test fun qrRefusesCredentialsCodesPublicServersAndUnexpectedPaths() {
        listOf("https://192.168.50.20:1234/?code=12345678", "https://192.168.50.20:1234/#secret",
            "https://user:secret@192.168.50.20:1234", "https://example.com:443", "http://192.168.50.20:1234",
            "https://192.168.50.20:1234/import", "https://192.168.50.20").forEach {
            assertTrue(runCatching { LocalImportQr.matrix(it, 496) }.isFailure)
        }
    }
}
