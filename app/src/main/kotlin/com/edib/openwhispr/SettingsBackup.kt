package com.edib.openwhispr

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings and API keys as one passphrase-encrypted file. Keys in the device's
 * Keystore can't leave the device, so exports carry the decrypted key and each
 * import re-encrypts it for the new device.
 */
object SettingsBackup {
    const val FORMAT = "openwispr-backup"
    const val VERSION = 1
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    /** Preference names left out of a backup: volatile caches and migrated legacy keys. */
    fun isExcludedPreference(key: String) = key.startsWith("cached_update_") || key == "api_key"

    class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Plain settings: [prefs] values are String, Int, Long, Float, Boolean or Set<String>. */
    data class Snapshot(val prefs: Map<String, Any>, val secrets: Map<String, String>)

    fun encrypt(snapshot: Snapshot, passphrase: String): String {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "passphrase too short" }
        val plain = toJson(snapshot).toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", JSONObject().put("alg", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
                .put("salt", b64(salt)))
            .put("cipher", JSONObject().put("alg", "AES-256-GCM").put("iv", b64(iv)))
            .put("data", b64(encrypted))
            .toString(2)
    }

    fun decrypt(file: String, passphrase: String): Snapshot {
        try {
            val envelope = JSONObject(file)
            require(envelope.getString("format") == FORMAT) { "not an OpenWispr backup" }
            require(envelope.getInt("version") == VERSION) { "unsupported backup version" }
            val kdf = envelope.getJSONObject("kdf")
            val salt = unb64(kdf.getString("salt"))
            val iv = unb64(envelope.getJSONObject("cipher").getString("iv"))
            val data = unb64(envelope.getString("data"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, salt, kdf.getInt("iterations")),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            return fromJson(JSONObject(String(cipher.doFinal(data), Charsets.UTF_8)))
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("wrong passphrase or damaged backup", e)
        }
    }

    private fun toJson(snapshot: Snapshot): JSONObject {
        val prefs = JSONObject()
        for ((key, value) in snapshot.prefs) {
            if (isExcludedPreference(key)) continue
            val typed = JSONObject()
            when (value) {
                is String -> typed.put("type", "string").put("value", value)
                is Int -> typed.put("type", "int").put("value", value)
                is Long -> typed.put("type", "long").put("value", value)
                is Float -> typed.put("type", "float").put("value", value.toDouble())
                is Boolean -> typed.put("type", "boolean").put("value", value)
                is Set<*> -> typed.put("type", "stringSet").put("value", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            prefs.put(key, typed)
        }
        val secrets = JSONObject()
        for ((key, value) in snapshot.secrets) if (value.isNotBlank()) secrets.put(key, value)
        return JSONObject().put("prefs", prefs).put("secrets", secrets)
    }

    private fun fromJson(json: JSONObject): Snapshot {
        val prefs = mutableMapOf<String, Any>()
        val prefsJson = json.getJSONObject("prefs")
        for (key in prefsJson.keys()) {
            if (isExcludedPreference(key)) continue
            val typed = prefsJson.getJSONObject(key)
            prefs[key] = when (typed.getString("type")) {
                "string" -> typed.getString("value")
                "int" -> typed.getInt("value")
                "long" -> typed.getLong("value")
                "float" -> typed.getDouble("value").toFloat()
                "boolean" -> typed.getBoolean("value")
                "stringSet" -> typed.getJSONArray("value").let { array ->
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }
                else -> throw BackupException("unknown setting type for $key")
            }
        }
        val secretsJson = json.getJSONObject("secrets")
        val secrets = secretsJson.keys().asSequence().associateWith { secretsJson.getString(it) }
        return Snapshot(prefs, secrets)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
