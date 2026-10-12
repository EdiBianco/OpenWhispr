package com.edib.openwhispr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores API credentials encrypted with a non-exportable Android Keystore key. */
object SecureKeyStorage {
    private const val SECURE_PREFS = "openwhispr_secure"
    private const val KEY_ALIAS = "openwhispr_api_key_encryption"
    private const val GROQ_KEY = "groq_api_key"
    private const val CLEANUP_KEY = "cleanup_api_key"
    private const val LEGACY_GROQ_KEY = "api_key"
    private const val AES_GCM_TAG_BITS = 128
    private val lock = Any()

    fun groqApiKey(context: Context): String = synchronized(lock) {
        val legacyPrefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        val encrypted = securePrefs(context).getString(GROQ_KEY, null)
        if (encrypted != null) {
            val decrypted = decrypt(encrypted) ?: return@synchronized ""
            if (legacyPrefs.contains(LEGACY_GROQ_KEY) &&
                !legacyPrefs.edit().remove(LEGACY_GROQ_KEY).commit()
            ) return@synchronized ""
            return@synchronized decrypted
        }

        val legacyKey = legacyPrefs.getString(LEGACY_GROQ_KEY, null)
        if (legacyKey.isNullOrBlank()) return@synchronized ""

        try {
            check(securePrefs(context).edit().putString(GROQ_KEY, encrypt(legacyKey)).commit())
            check(context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE).edit()
                .remove(LEGACY_GROQ_KEY).commit())
            legacyKey
        } catch (_: Exception) {
            ""
        }
    }

    fun cleanupApiKey(context: Context): String = synchronized(lock) {
        securePrefs(context).getString(CLEANUP_KEY, null)?.let { decrypt(it) } ?: ""
    }

    fun saveGroqApiKey(context: Context, value: String) = save(context, GROQ_KEY, value)

    fun saveCleanupApiKey(context: Context, value: String) = save(context, CLEANUP_KEY, value)

    private fun save(context: Context, preferenceKey: String, value: String) = synchronized(lock) {
        val editor = securePrefs(context).edit()
        if (value.isBlank()) editor.remove(preferenceKey)
        else editor.putString(preferenceKey, encrypt(value))
        check(editor.commit())
        if (preferenceKey == GROQ_KEY) {
            check(context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE).edit()
                .remove(LEGACY_GROQ_KEY).commit())
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = cipher.iv + encrypted
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? {
        return try {
            val encoded = Base64.decode(value, Base64.NO_WRAP)
            if (encoded.size <= 12) return null
            val iv = encoded.copyOfRange(0, 12)
            val encrypted = encoded.copyOfRange(12, encoded.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun securePrefs(context: Context) =
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
}
