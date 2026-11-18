package com.fwr.app.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction for encrypting and decrypting persisted OAuth tokens.
 * Allows injecting deterministic ciphers in pure-JVM unit tests while using Android Keystore in production.
 */
interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(storedValue: String): String?

    companion object {
        const val ENCRYPTED_PREFIX = "enc:v1:"
    }
}

/**
 * Android Keystore backed AES-256-GCM cipher for OAuth token storage.
 * Prepends `"enc:v1:"` to encrypted payloads and falls back gracefully on legacy plaintext values.
 */
class AndroidKeystoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : TokenCipher {

    override fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return TokenCipher.ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(storedValue: String): String? {
        if (storedValue.isBlank()) return null
        if (!storedValue.startsWith(TokenCipher.ENCRYPTED_PREFIX)) {
            return storedValue
        }
        return try {
            val encodedPayload = storedValue.removePrefix(TokenCipher.ENCRYPTED_PREFIX)
            val combined = Base64.getDecoder().decode(encodedPayload)
            if (combined.size <= GCM_IV_LENGTH_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val secretKey = getExistingSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateSecretKey(): SecretKey = synchronized(this) {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "fwr_oauth_token_aes_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
