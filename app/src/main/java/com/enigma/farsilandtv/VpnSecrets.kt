package com.enigma.farsilandtv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class VpnSecrets(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn-subscription.enc"))

    fun configured() = file.baseFile.exists()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun save(address: String) = synchronized(FILE_LOCK) {
        val value = VpnImport.text(address)
        if (!VpnImport.remote(value)) VpnEngine.nodes(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun read(): String = synchronized(FILE_LOCK) {
        val buffer = ByteBuffer.wrap(file.readFully())
        val size = buffer.get().toInt()
        require(size == 12 && buffer.remaining() > size + 16)
        val iv = ByteArray(size).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun clear() = synchronized(FILE_LOCK) { file.delete() }

    private companion object {
        const val KEY_ALIAS = "amin-player-vpn-subscription"
        val FILE_LOCK = Any()
    }
}
