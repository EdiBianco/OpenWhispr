package com.edib.openwhispr

import android.content.Context
import android.content.SharedPreferences
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Lightweight in-app "new version available" check against this repo's
 * GitHub Releases, plus the download half of an in-app update: fetches the
 * release's .apk asset directly so the install flow never has to leave the
 * app for a browser. No backend involved -- just the public GitHub API. */
object UpdateChecker {
    data class UpdateInfo(val version: String, val url: String, val apkUrl: String?)

    private val client = OkHttpClient()
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // don't hammer GitHub on every app open
    private const val RELEASES_URL =
        "https://api.github.com/repos/EdiBianco/OpenWhispr/releases/latest"

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

        fun cachedResult(): UpdateInfo? =
            if (cachedVersion != null && cachedUrl != null && isNewer(cachedVersion, currentVersion))
                UpdateInfo(cachedVersion, cachedUrl, cachedApkUrl)
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

                    var apkUrl: String? = null
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", null)
                                break
                            }
                        }
                    }

                    prefs.edit()
                        .putLong("last_update_check", now)
                        .putString("cached_update_version", tag)
                        .putString("cached_update_url", url)
                        .putString("cached_update_apk_url", apkUrl)
                        .apply()
                    if (tag.isNotBlank() && url.isNotBlank() && isNewer(tag, currentVersion)) {
                        callback(UpdateInfo(tag, url, apkUrl))
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
     * responsible for hopping back to the UI thread before touching views. */
    fun downloadApk(context: Context, apkUrl: String, callback: (File?, String?) -> Unit) {
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
                    body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    callback(file, null)
                } catch (e: Exception) {
                    callback(null, e.message)
                }
            }
        })
    }
}
