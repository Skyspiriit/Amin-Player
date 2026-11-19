package com.enigma.farsilandtv

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.InetAddress
import java.net.URI

/** Encode only the current LAN page address. Never include pairing codes or profile data. */
internal object LocalImportQr {
    fun matrix(address: String, size: Int): BitMatrix {
        val uri = URI(address)
        val host = uri.host.orEmpty()
        require(uri.scheme == "https" && host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
            host.split('.').all { it.toInt() in 0..255 } && InetAddress.getByName(host).isSiteLocalAddress &&
            uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in setOf("", "/") && size in 160..1024) { "Use the private import page address" }
        return QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    }
}
