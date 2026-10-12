package com.edib.openwhispr

import android.content.Context
import android.content.SharedPreferences
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/** Lightweight in-app "new version available" check against this repo's
 * GitHub Releases, plus the download half of an in-app update: fetches the
 * release's .apk asset directly so the install flow never has to leave the
 * app for a browser. No backend involved -- just the public GitHub API. */
object UpdateChecker {
    // [security] apkSha256 carries the checksum GitHub publishes for the
    // release's .apk asset, so downloadApk can verify the file before it is
    // handed to the installer.
    data class UpdateInfo(
        val version: String,
        val url: String,
        val apkUrl: String?,
        val notes: String?,
        val apkSha256: String? = null
    )

    private val client = OkHttpClient()
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // don't hammer GitHub on every app open
    private const val RELEASES_URL =
        "https://api.github.com/repos/EdiBianco/OpenWhispr/releases/latest"

    // Matches the <!--WHATS_NEW_START-->...<!--WHATS_NEW_END--> block the
    // release workflow wraps around that version's CHANGELOG.md section, so
    // the update dialog can show a short "what's new" instead of just a
    // version number.
    private val WHATS_NEW_REGEX = Regex(
        "<!--WHATS_NEW_START-->(.*?)<!--WHATS_NEW_END-->",
        RegexOption.DOT_MATCHES_ALL
    )

    /** True if [latest] (e.g. "v3.2.0") is a strictly newer version than
     * [current] (e.g. "3.1.1"). Compares numeric dot-separated parts. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = parts(latest)
        val c = parts(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    // [security] GitHub's release API reports each asset's checksum as
    // "digest": "sha256:<64 hex chars>". Returns the lowercase hex, or null
    // if the field is missing or isn't a well-formed SHA-256.
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun parseSha256Digest(raw: String?): String? {
        val hex = raw?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
        return hex.takeIf { SHA256_HEX.matches(it) }
    }

    /** [security] True only if a checksum was published AND the downloaded
     * file's checksum matches it. A missing checksum counts as a failure,
     * so an unverifiable file is never installed. */
    fun checksumMatches(expectedSha256: String?, actualSha256: String): Boolean =
        expectedSha256 != null && expectedSha256.equals(actualSha256, ignoreCase = true)

    /** Calls back with an [UpdateInfo] if a newer release exists, or null
     * otherwise. Respects a cache interval so this isn't a network call on
     * every app open; falls back to the last cached result if the network
     * check fails. Callback always runs on a background thread. */
    fun checkForUpdate(
        prefs: SharedPreferences,
        currentVersion: String,
        force: Boolean = false,
        callback: (UpdateInfo?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong("last_update_check", 0)
        val cachedVersion = prefs.getString("cached_update_version", null)
        val cachedUrl = prefs.getString("cached_update_url", null)
        val cachedApkUrl = prefs.getString("cached_update_apk_url", null)
        val cachedNotes = prefs.getString("cached_update_notes", null)
        // [security] Cached with the rest so an offline/cached result can still be verified.
        val cachedApkSha256 = prefs.getString("cached_update_apk_sha256", null)

        fun cachedResult(): UpdateInfo? =
            if (cachedVersion != null && cachedUrl != null && isNewer(cachedVersion, currentVersion))
                UpdateInfo(cachedVersion, cachedUrl, cachedApkUrl, cachedNotes, cachedApkSha256)
            else null

        if (!force && now - lastCheck < CHECK_INTERVAL_MS) {
            callback(cachedResult())
            return
        }

        val request = Request.Builder().url(RELEASES_URL).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(cachedResult())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val obj = JSONObject(body)
                    val tag = obj.optString("tag_name", "")
                    val url = obj.optString("html_url", "")
                    val releaseBody = obj.optString("body", "")
                    val notes = WHATS_NEW_REGEX.find(releaseBody)
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

                    var apkUrl: String? = null
                    var apkSha256: String? = null
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", null)
                                // [security] Checksum of this same asset, used by downloadApk.
                                apkSha256 = parseSha256Digest(asset.optString("digest", null))
                                break
                            }
                        }
                    }

                    prefs.edit()
                        .putLong("last_update_check", now)
                        .putString("cached_update_version", tag)
                        .putString("cached_update_url", url)
                        .putString("cached_update_apk_url", apkUrl)
                        .putString("cached_update_notes", notes)
                        .putString("cached_update_apk_sha256", apkSha256)
                        .apply()
                    if (tag.isNotBlank() && url.isNotBlank() && isNewer(tag, currentVersion)) {
                        callback(UpdateInfo(tag, url, apkUrl, notes, apkSha256))
                    } else {
                        callback(null)
                    }
                } catch (e: Exception) {
                    callback(cachedResult())
                }
            }
        })
    }

    /** Downloads [apkUrl] into the app's private cache dir and calls back
     * with the resulting file, or null + an error message on failure.
     * Runs on a background thread (OkHttp's own dispatcher); the caller is
     * responsible for hopping back to the UI thread before touching views.
     *
     * [security] The file is hashed while it downloads and only returned if
     * its SHA-256 matches [expectedSha256] (GitHub's published checksum for
     * the asset). On a mismatch, or if no checksum was published, the file is
     * deleted and an error returned, so it never reaches the installer. This
     * catches corrupted or swapped downloads; it cannot protect against a
     * malicious release published by the repo owner.
     */
    fun downloadApk(
        context: Context,
        apkUrl: String,
        expectedSha256: String?,
        callback: (File?, String?) -> Unit
    ) {
        val request = Request.Builder().url(apkUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback(null, "HTTP ${response.code}")
                    return
                }
                try {
                    val body = response.body
                    if (body == null) {
                        callback(null, "Empty response body")
                        return
                    }
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    val file = File(dir, "openwhispr-update.apk")
                    // [security] Hash while copying (no second pass over the file).
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(body.byteStream(), digest).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!checksumMatches(expectedSha256, actualSha256)) {
                        file.delete()
                        callback(
                            null,
                            if (expectedSha256 == null) "No checksum available for this download"
                            else "Checksum mismatch"
                        )
                        return
                    }
                    callback(file, null)
                } catch (e: Exception) {
                    callback(null, e.message)
                }
            }
        })
    }
}
