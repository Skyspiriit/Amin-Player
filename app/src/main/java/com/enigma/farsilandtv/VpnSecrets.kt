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

/** AES-GCM with a non-exportable Android Keystore key. The same key protects the 1.34 file and the 1.35 profiles. */
internal object SecureBox {
    private const val KEY_ALIAS = "amin-player-vpn-subscription"

    private fun key(): SecretKey = synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes)
        val size = buffer.get().toInt()
        require(size == 12 && buffer.remaining() > size + 16)
        val iv = ByteArray(size).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext)
    }
}

/** The single 1.34 profile. 1.35 only reads it once (migration) and deletes it with its profile. */
internal class VpnSecrets(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn-subscription.enc"))

    fun configured() = file.baseFile.exists()

    fun read(): String = synchronized(FILE_LOCK) { SecureBox.decrypt(file.readFully()).toString(Charsets.UTF_8) }

    fun clear() = synchronized(FILE_LOCK) { file.delete() }

    private companion object {
        val FILE_LOCK = Any()
    }
}
