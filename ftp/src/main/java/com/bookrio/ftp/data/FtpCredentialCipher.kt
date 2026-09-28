package com.bookrio.ftp.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential encryption boundary. The transfer engine only ever sees plaintext
 * briefly while opening a connection; Room stores ciphertext only.
 */
interface FtpCredentialCipher {
    /** @throws CredentialCipherException when encryption is impossible. */
    fun encrypt(plaintext: String): String

    /** Returns null when the blob is absent or cannot be decrypted (needs re-auth). */
    fun decrypt(ciphertext: String?): String?
}

class CredentialCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Android Keystore AES-256-GCM cipher.
 *
 * The key never leaves the Keystore. The stored blob is `base64(len(iv) ‖ iv ‖ ct)`,
 * so a device backup without the Keystore key is useless.
 */
class AndroidKeystoreCredentialCipher(
    private val alias: String = DEFAULT_ALIAS
) : FtpCredentialCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val blob = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
                .put(iv.size.toByte())
                .put(iv)
                .put(ciphertext)
                .array()
            Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (e: Throwable) {
            throw CredentialCipherException("Could not encrypt credentials", e)
        }
    }

    override fun decrypt(ciphertext: String?): String? {
        if (ciphertext.isNullOrBlank()) return null
        return try {
            val blob = Base64.decode(ciphertext, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(blob)
            val ivLength = buffer.get().toInt()
            if (ivLength <= 0 || ivLength > buffer.remaining()) return null
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "shelf_ftp_credentials_v1"
    }
}