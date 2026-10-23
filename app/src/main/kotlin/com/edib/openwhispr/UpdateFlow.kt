package com.edib.openwhispr

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The in-app update feature: ask GitHub Releases whether a newer build exists,
 * show what changed, download the release APK and hand it to the system
 * installer -- checking GitHub's published SHA-256 before anything is
 * installed, and saving the APK to Downloads first when the new version
 * changes signing keys.
 *
 * It is a class rather than a handful of activity methods because it is a
 * whole feature whose halves run on different threads: the release check and
 * the download both call back off the main thread and then touch this screen
 * again. The activity keeps the two decisions that are its own -- which
 * version is installed, and what "back up first" means -- and passes the
 * latter in as [onBackUpFirst].
 */
internal class UpdateFlow(
    private val activity: AppCompatActivity,
    private val onBackUpFirst: () -> Unit,
) {

    /** Checks this repo's GitHub Releases. No backend involved. Shows a
     * dialog linking to the release page when a newer version is
     * available. [privacy] Runs on app-open only if the user turned on
     * "Check for updates automatically" (silently, when nothing's new);
     * [force] bypasses the cache interval and always gives feedback, for
     * the manual "Check for updates" row. */
    fun check(force: Boolean = false) {
        val currentVersion = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: return

        UpdateChecker.checkForUpdate(activity.prefs(), currentVersion, force) { info ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (info != null) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_available)
                        .setMessage(
                            buildString {
                                append(
                                    activity.getString(
                                        R.string.update_available_message,
                                        info.version,
                                        currentVersion,
                                    )
                                )
                                append("\n\n${activity.getString(R.string.whats_new)}:\n")
                                append(
                                    info.notes?.takeIf { it.isNotBlank() }
                                        ?: activity.getString(R.string.no_release_notes)
                                )
                            }
                        )
                        .setPositiveButton(activity.getString(R.string.update)) { _, _ ->
                            downloadAndInstallUpdate(info)
                        }
                        // [security] Full release page (complete changelog) before deciding.
                        .setNeutralButton(activity.getString(R.string.view_on_github)) { _, _ ->
                            openReleasePage(info.url)
                        }
                        .setNegativeButton(activity.getString(R.string.later), null)
                        .show()
                } else if (force) {
                    activity.toast(activity.getString(R.string.up_to_date, currentVersion))
                }
            }
        }
    }

    /** Downloads the release's .apk directly in-app and hands it to the
     * system installer -- no browser tab, no external navigation. The final
     * "install this app?" confirmation is a mandatory Android system dialog
     * for a sideloaded APK and can't be skipped, but everything up to that
     * point (download, progress) happens invisibly inside OpenWispr. */
    private fun downloadAndInstallUpdate(info: UpdateChecker.UpdateInfo) {
        val apkUrl = info.apkUrl
        if (apkUrl == null) {
            // Release has no .apk asset (shouldn't normally happen) -- fall
            // back to the release page rather than doing nothing.
            openReleasePage(info.url)
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.allow_install_updates)
                .setMessage(R.string.allow_install_updates_message)
                .setPositiveButton(R.string.continue_button) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        activity.toast(
                            activity.getString(
                                R.string.open_settings_failed,
                                e.message ?: activity.getString(R.string.unknown_error),
                            )
                        )
                    }
                }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .show()
            return
        }

        activity.toast(activity.getString(R.string.downloading_update))
        // [security] Pass GitHub's published checksum; downloadApk only
        // returns a file if it matches.
        UpdateChecker.downloadApk(activity, apkUrl, info.apkSha256) { file, error ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (file == null) {
                    // [security] A failed download or failed checksum both end
                    // here; nothing is installed. Offer the release page instead.
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_not_installed_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_not_installed_message,
                                error ?: activity.getString(R.string.unknown_error),
                            )
                        )
                        .setPositiveButton(activity.getString(R.string.view_on_github)) { _, _ ->
                            openReleasePage(info.url)
                        }
                        .setNegativeButton(activity.getString(R.string.close_dialog), null)
                        .show()
                    return@runOnUiThread
                }
                if (isMajorVersion4(info.version)) showMigrationToV4(info, file) else installApk(file)
            }
        }
    }

    private fun openReleasePage(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            activity.toast(activity.getString(R.string.browser_open_failed, e.message ?: ""))
        }
    }

    /** 4.0.0 is signed with a new key, so an upgrade from 3.x has to go
     * through the uninstall in [showMigrationToV4] rather than the installer. */
    private fun isMajorVersion4(version: String): Boolean =
        (UpdateChecker.majorVersion(version) ?: 0) >= 4

    /** 4.0.0 is signed with a new key, so Android won't install it over 3.x. The APK is
     * saved to Downloads first, because uninstalling wipes the app's cache. Then the
     * user backs up, uninstalls 3.x, installs the saved APK, and restores the backup. */
    private fun showMigrationToV4(info: UpdateChecker.UpdateInfo, file: File) {
        val name = "openwispr-v${info.version.removePrefix("v")}.apk"
        val saved = saveApkToDownloads(file, name)
        AlertDialog.Builder(activity)
            .setTitle(R.string.migration_title)
            .setMessage(
                activity.getString(
                    if (saved) R.string.migration_message_saved else R.string.migration_message_not_saved,
                    name,
                )
            )
            .setPositiveButton(R.string.migration_uninstall) { _, _ ->
                activity.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNeutralButton(R.string.migration_back_up_first) { _, _ -> onBackUpFirst() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun saveApkToDownloads(file: File, name: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val copied = uri != null && activity.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } != null
            if (uri == null || !copied) return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            activity.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun installApk(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.toast(
                activity.getString(
                    R.string.installer_start_failed,
                    e.message ?: activity.getString(R.string.unknown_error),
                )
            )
        }
    }
}
