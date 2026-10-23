package com.edib.openwhispr

import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Exporting and importing the passphrase-encrypted settings backup: the
 * checklist of what to include, the passphrase prompt, the file picker and the
 * file itself.
 *
 * It is a class rather than a handful of activity methods because it is a whole
 * feature with state that lives *between* steps -- the passphrase and the
 * chosen snapshot sit between a dialog and the file picker's callback. The
 * activity keeps policy: it supplies [onRestored], which the caller uses to
 * refresh the service and redraw once restored settings are in place, and it
 * decides what a backup contains only through `SettingsBackup`.
 */
internal class BackupFlow(
    private val activity: AppCompatActivity,
    /** Called after a restore put its settings in place. */
    private val onRestored: () -> Unit,
) {
    // Both live only between a dialog and the file picker that follows it.
    private var pendingPassphrase: String? = null
    private var pendingExport: SettingsBackup.Snapshot? = null

    // Registered while the activity is being built, which is the only time the
    // framework accepts it.
    private val createFile = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) write(uri) }

    private val openFile = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) read(uri) }

    /** Asks what to include, for a passphrase, then where to put the file. */
    fun export() {
        val snapshot = currentSnapshot()
        val items = snapshot.prefs.keys.filterNot { SettingsBackup.isExcludedPreference(it) }.sorted()
            .map { prefItemId(it) to it } +
            snapshot.secrets.filterValues { it.isNotBlank() }.keys.sorted()
                .map { secretItemId(it) to secretLabel(it) }
        chooseItems(R.string.backup_choose_export, items) { chosen ->
            askPassphrase(exporting = true) { passphrase ->
                pendingPassphrase = passphrase
                pendingExport = SettingsBackup.Snapshot(
                    prefs = snapshot.prefs.filterKeys { prefItemId(it) in chosen },
                    secrets = snapshot.secrets.filterKeys { secretItemId(it) in chosen },
                )
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                createFile.launch("openwispr-backup-$date.owb")
            }
        }
    }

    /** Picks a backup file; [read] asks for its passphrase afterwards. */
    fun restore() {
        openFile.launch(arrayOf("*/*"))
    }

    // --- Export ---

    private fun currentSnapshot(): SettingsBackup.Snapshot = SettingsBackup.Snapshot(
        prefs = activity.prefs().all.entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
        secrets = mapOf(
            "groq_api_key" to SecureKeyStorage.groqApiKey(activity),
            "cleanup_api_key" to SecureKeyStorage.cleanupApiKey(activity),
        ),
    )

    private fun write(uri: Uri) {
        val passphrase = pendingPassphrase ?: return
        val snapshot = pendingExport ?: return
        pendingPassphrase = null
        pendingExport = null
        thread {
            try {
                val file = SettingsBackup.encrypt(snapshot, passphrase)
                activity.contentResolver.openOutputStream(uri)?.use { it.write(file.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("cannot open the file")
                activity.runOnUiThread { activity.toast(activity.getString(R.string.backup_export_done)) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.backup_export_failed, e.message ?: ""))
                }
            }
        }
    }

    // --- Restore ---

    private fun read(uri: Uri) {
        val text = try {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            activity.toast(activity.getString(R.string.backup_restore_failed))
            return
        }
        askPassphrase(exporting = false) { passphrase ->
            thread {
                try {
                    val snapshot = SettingsBackup.decrypt(text, passphrase)
                    activity.runOnUiThread { chooseRestoreItems(snapshot) }
                } catch (e: SettingsBackup.BackupException) {
                    activity.runOnUiThread { activity.toast(activity.getString(R.string.backup_restore_failed)) }
                }
            }
        }
    }

    private fun chooseRestoreItems(snapshot: SettingsBackup.Snapshot) {
        val items = snapshot.prefs.keys.sorted().map { prefItemId(it) to it } +
            snapshot.secrets.keys.sorted().map { secretItemId(it) to secretLabel(it) }
        chooseItems(R.string.backup_choose_restore, items) { chosen ->
            apply(snapshot, chosen)
        }
    }

    /** Writes only the chosen settings and keys; everything else on the device
     * is left as it is. */
    private fun apply(snapshot: SettingsBackup.Snapshot, chosen: Set<String>) {
        val editor = activity.prefs().edit()
        for ((key, value) in snapshot.prefs) {
            if (prefItemId(key) !in chosen) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        editor.putBoolean("welcome_done", true)
        check(editor.commit())
        if (secretItemId("groq_api_key") in chosen) {
            SecureKeyStorage.saveGroqApiKey(activity, snapshot.secrets["groq_api_key"].orEmpty())
        }
        if (secretItemId("cleanup_api_key") in chosen) {
            SecureKeyStorage.saveCleanupApiKey(activity, snapshot.secrets["cleanup_api_key"].orEmpty())
        }
        activity.toast(activity.getString(R.string.backup_restore_done))
        onRestored()
    }

    // --- Dialogs ---

    /** Checklist with "select all"; [onConfirm] receives the chosen item ids. */
    private fun chooseItems(title: Int, items: List<Pair<String, String>>, onConfirm: (Set<String>) -> Unit) {
        if (items.isEmpty()) {
            activity.toast(activity.getString(R.string.backup_nothing_to_choose))
            return
        }
        val selected = items.map { it.first }.toMutableSet()
        val boxes = mutableListOf<CheckBox>()
        var syncing = false
        val selectAll = CheckBox(activity).apply {
            text = activity.getString(R.string.backup_select_all)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                syncing = true
                boxes.forEach { it.isChecked = checked }
                syncing = false
            }
        }
        val list = activity.vertical(0)
        items.forEach { (id, label) ->
            val box = CheckBox(activity).apply {
                text = label
                isChecked = true
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(id) else selected.remove(id)
                    if (!syncing) {
                        syncing = true
                        selectAll.isChecked = boxes.all { it.isChecked }
                        syncing = false
                    }
                }
            }
            boxes += box
            list.addView(box)
        }
        val container = activity.vertical(activity.dp(24), activity.dp(8)).apply {
            addView(selectAll)
            addView(ScrollView(activity).apply {
                addView(list)
                layoutParams = android.widget.LinearLayout.LayoutParams(LP_MATCH, activity.dp(360))
            })
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.continue_button) { _, _ ->
                if (selected.isEmpty()) activity.toast(activity.getString(R.string.backup_nothing_selected))
                else onConfirm(selected.toSet())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Passphrase prompt. Exports ask twice and enforce a minimum length. */
    private fun askPassphrase(exporting: Boolean, onOk: (String) -> Unit) {
        fun field(hintRes: Int) = EditText(activity).apply {
            hint = activity.getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val first = field(R.string.backup_passphrase_hint)
        val second = field(R.string.backup_passphrase_confirm_hint)
        val container = activity.vertical(activity.dp(24), activity.dp(8)).apply {
            addView(first)
            if (exporting) addView(second)
        }
        AlertDialog.Builder(activity)
            .setTitle(
                if (exporting) R.string.backup_passphrase_title_export
                else R.string.backup_passphrase_title_restore
            )
            .setView(container)
            .setPositiveButton(if (exporting) R.string.save else R.string.continue_button) { _, _ ->
                val passphrase = first.text.toString()
                when {
                    exporting && passphrase.length < SettingsBackup.MIN_PASSPHRASE_LENGTH ->
                        activity.toast(activity.getString(R.string.backup_passphrase_short))
                    exporting && passphrase != second.text.toString() ->
                        activity.toast(activity.getString(R.string.backup_passphrase_mismatch))
                    !exporting && passphrase.isEmpty() ->
                        activity.toast(activity.getString(R.string.backup_passphrase_short))
                    else -> onOk(passphrase)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Item ids and labels ---

    private fun prefItemId(key: String) = "pref:$key"
    private fun secretItemId(name: String) = "secret:$name"
    private fun secretLabel(name: String) = when (name) {
        "groq_api_key" -> activity.getString(R.string.backup_item_groq_key)
        "cleanup_api_key" -> activity.getString(R.string.backup_item_cleanup_key)
        else -> name
    }
}
