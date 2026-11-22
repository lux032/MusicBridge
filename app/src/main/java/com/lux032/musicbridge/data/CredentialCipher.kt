package com.lux032.musicbridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CredentialCipher {
    fun isEncrypted(value: String): Boolean
    fun encrypt(plainText: String): String
    fun decrypt(encryptedValue: String): String?
}

/**
 * Encrypts sensitive server tokens with an AES-256-GCM key held in Android Keystore.
 *
 * Ciphertext format: `"enc:v1:" + base64(12-byte IV || ciphertext || 16-byte GCM auth tag)`.
 * In non-Android JVM test environments (such as Robolectric where `"AndroidKeyStore"` is not
 * registered), falls back to an in-process AES-256 key so encryption round-trips remain tested.
 */
internal class AndroidKeystoreCredentialCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val secretKeyProvider: () -> SecretKey = { loadOrCreateSecretKey(keyAlias) },
) : CredentialCipher {

    override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX_V1)

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = secretKeyProvider()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore requires randomized IV generation on ENCRYPT_MODE.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(cipherBytes, 0, payload, iv.size, cipherBytes.size)
        return PREFIX_V1 + payload.toByteString().base64()
    }

    override fun decrypt(encryptedValue: String): String? {
        if (encryptedValue.isEmpty()) return ""
        if (!isEncrypted(encryptedValue)) return encryptedValue
        return runCatching {
            val encoded = encryptedValue.removePrefix(PREFIX_V1)
            val payload = encoded.decodeBase64()?.toByteArray() ?: return null
            if (payload.size <= GCM_IV_LENGTH_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKeyProvider(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }.onFailure { error ->
            Log.w(TAG, "Failed to decrypt stored server credential", error)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CredentialCipher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "musicbridge_server_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        internal const val PREFIX_V1 = "enc:v1:"

        @Volatile
        private var jvmFallbackKey: SecretKey? = null

        @Synchronized
        private fun loadOrCreateSecretKey(alias: String): SecretKey {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                (keyStore.getKey(alias, null) as? SecretKey) ?: generateKeystoreKey(alias)
            } catch (_: KeyStoreException) {
                // Only reached in JVM/Robolectric unit test environments without AndroidKeyStore.
                jvmFallbackKey ?: KeyGenerator.getInstance("AES").apply {
                    init(256)
                }.generateKey().also { jvmFallbackKey = it }
            }
        }

        private fun generateKeystoreKey(alias: String): SecretKey {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }
}
