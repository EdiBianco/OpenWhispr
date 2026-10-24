package com.edib.openwhispr

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.tabs.TabLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    // [ui] Status row dot + tap-to-fix target, and the cloud switch's subtitle.
    private lateinit var statusRow: LinearLayout
    private lateinit var statusDotView: View
    private var statusAction: (() -> Unit)? = null
    private lateinit var cloudRowSub: TextView
    private lateinit var cloudModelRow: LinearLayout
    private lateinit var cloudModelRowSub: TextView
    private lateinit var audioRow: LinearLayout
    private lateinit var audioRowSub: TextView
    private lateinit var audioDot: View
    private lateinit var accRow: LinearLayout
    private lateinit var accRowSub: TextView
    private lateinit var accDot: View
    private lateinit var accCaption: TextView
    private lateinit var batteryRow: LinearLayout
    private lateinit var batteryRowSub: TextView
    private lateinit var batteryDot: View
    private lateinit var setupCollapsedRow: LinearLayout
    private lateinit var setupCollapsedRowSub: TextView
    private lateinit var setupDoneSummary: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var cleanupProviderRowSub: TextView
    private lateinit var customInstructionsRowSub: TextView
    private lateinit var customInstructionsRow: LinearLayout
    private lateinit var cleanupProviderRow: LinearLayout
    private lateinit var languageRowSub: TextView
    private lateinit var languageRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var voiceCommandsDetailContainer: LinearLayout
    private lateinit var triggerPhraseRowSub: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var statusContainer: LinearLayout
    private lateinit var dictationContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private var batteryWarningShown = false
    private var setupExpanded = false

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val delBtn: MaterialButton,
    )

    // Backup and restore is its own flow in BackupFlow.kt. It registers its
    // own file pickers, so it has to be created as a field, before the
    // activity is started -- and it calls back here once a restore wrote
    // settings, since refreshing the service is this screen's business.
    private val backup = BackupFlow(this) {
        WhisperAccessibilityService.instance?.apply {
            refreshBubbleSize()
            refreshPosition()
            reloadModel()
        }
        recreate()
    }

    // The update feature lives in UpdateFlow.kt. Its 3.x -> 4.0 migration
    // dialog offers "back up first", which is the backup flow's entry point,
    // so the two flows are wired together here rather than inside either one.
    private val updates = UpdateFlow(this) { backup.export() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceWatchdog.schedule(this)

        // First launch on a fresh install (no saved settings yet): offer to restore a backup.
        if (!prefs().contains("welcome_done") && prefs().all.isEmpty()) {
            window.decorView.post { showWelcome() }
        }

        // [ui] Android 12+ drops the splash as soon as the first frame is
        // drawn, which would cut the icon animation short on a fast phone.
        // Wait for whatever is left of it (at most 1s), then fade out.
        // The splash itself is the Theme.OpenWhispr.Launch theme.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                val start = splash.iconAnimationStart?.toEpochMilli()
                val duration = splash.iconAnimationDuration?.toMillis() ?: 0L
                val remaining = if (start == null) 0L
                    else (start + duration - System.currentTimeMillis()).coerceIn(0L, 1000L)
                splash.animate().alpha(0f).setStartDelay(remaining).setDuration(200)
                    .withEndAction { splash.remove() }.start()
            }
        }

        // Best-effort: lets the background service show its "still running"
        // notification (Android 13+ requires this permission for any
        // notification, including the foreground-service one). Not gated on
        // anything -- dictation works fine without it, this just makes the
        // service more likely to survive being swiped from Recents.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        // [privacy] The automatic update check on app open is opt-in (off by
        // default, including for existing installs), so the app makes no
        // request to GitHub unless the user enables it or taps "Check for
        // updates".
        if (prefs().getBoolean("auto_update_check", false)) updates.check()

        val outer = vertical(0, 0)

        // Top large header (like "Connected devices"), with the app icon
        // alongside the name.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // [ui] Less empty space above the title (was 64dp top).
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        header.addView(TextView(this).apply {
            text = "OpenWispr"
            textSize = 32f
        })
        outer.addView(header)

        tabLayout = TabLayout(this).apply {
            addTab(newTab().setText(getString(R.string.tab_status)))
            addTab(newTab().setText(getString(R.string.tab_dictation)))
            addTab(newTab().setText(getString(R.string.tab_settings)))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { showTab(tab.position) }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        outer.addView(tabLayout)

        // [ui] The status tab no longer starts with a section header, so
        // give it the same breathing room below the tabs.
        statusContainer = vertical(0).apply { setPadding(0, dp(16), 0, 0) }
        dictationContainer = vertical(0)
        settingsContainer = vertical(0)

        // [ui] Each section's rows sit on a rounded panel (see group()).
        // Rows, handlers and the references refresh() uses are unchanged;
        // only the parent they're added to is.

        // ================= Status tab =================

        statusDotView = statusDot()
        statusRow = settingsRow(getString(R.string.tab_status), getString(R.string.checking), leading = statusDotView) {
            statusAction?.invoke()
        }
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        statusContainer.addView(group().apply { addView(statusRow) })

        // --- Setup checklist card ---
        statusContainer.addView(sectionHeader(getString(R.string.setup)))
        val setupGroup = group()
        setupCollapsedRow = settingsRow(getString(R.string.setup), getString(R.string.checking)) {
            setupExpanded = !setupExpanded
            refresh()
        }
        setupCollapsedRowSub = setupCollapsedRow.findViewWithTag("subtitle")
        setupGroup.addView(setupCollapsedRow)

        setupDoneSummary = TextView(this).apply {
            textSize = 14f
            setTextColor(DOT_GREEN)
            setPadding(dp(24), 0, dp(24), dp(8))
        }
        setupGroup.addView(setupDoneSummary)

        audioDot = statusDot()
        audioRow = settingsRow(getString(R.string.audio_permission), getString(R.string.checking), leading = audioDot) {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        setupGroup.addView(audioRow)

        accDot = statusDot()
        accRow = settingsRow(getString(R.string.accessibility_service), getString(R.string.checking), leading = accDot) {
            val alreadyEnabled = WhisperAccessibilityService.instance != null
            // The restricted-settings help is for a toggle that can't be switched on yet;
            // an enabled-but-stopped service just needs the toggle cycled.
            if (!alreadyEnabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                !ServiceWatchdog.isEnabledInSettings(this)) {
                showRestrictedSettingsHelp()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        setupGroup.addView(accRow)

        accCaption = TextView(this).apply {
            text = getString(R.string.accessibility_help)
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            alpha = 0.8f
            setPadding(dp(24), 0, dp(24), dp(12))
        }
        setupGroup.addView(accCaption)

        batteryDot = statusDot()
        batteryRow = settingsRow(getString(R.string.battery_optimization), getString(R.string.checking), leading = batteryDot) {
            requestBatteryExemption()
        }
        batteryRowSub = batteryRow.findViewWithTag("subtitle")
        setupGroup.addView(batteryRow)
        statusContainer.addView(setupGroup)

        // --- Background service ---
        statusContainer.addView(sectionHeader("Service")) // [ui]
        val serviceEnabled = prefs().getBoolean("service_master_enabled", true)
        val serviceSwitch = MaterialSwitch(this).apply {
            isChecked = serviceEnabled
            isClickable = false
        }
        val serviceRow = settingsRow(
            getString(R.string.background_service),
            getString(R.string.background_service_summary),
            serviceSwitch
        ) {
            val newVal = !serviceSwitch.isChecked
            prefs().edit().putBoolean("service_master_enabled", newVal).apply()
            serviceSwitch.isChecked = newVal
            WhisperAccessibilityService.instance?.refreshMasterEnabled()
        }
        statusContainer.addView(group().apply { addView(serviceRow) })

        // ================= Dictation tab =================

        dictationContainer.addView(sectionHeader(getString(R.string.engine)))

        val isCloud = !prefs().getBoolean("use_local", true)
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        // [ui] Subtitle is set from the switch state in refresh().
        val cloudRow = settingsRow(getString(R.string.cloud_transcription), "", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        cloudRowSub = cloudRow.findViewWithTag("subtitle")
        // [ui] Only meaningful in cloud mode; refresh() shows or hides it.
        cloudModelRow = settingsRow(getString(R.string.cloud_model), "") { promptCloudModel() }
        cloudModelRowSub = cloudModelRow.findViewWithTag("subtitle")
        dictationContainer.addView(group().apply {
            addView(cloudRow)
            addView(cloudModelRow)
        })

        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader(getString(R.string.local_models)))
        val modelGroup = group()
        for (m in MODEL_CATALOG) modelGroup.addView(buildModelRow(m))
        modelContainer.addView(modelGroup)
        dictationContainer.addView(modelContainer)

        languageRow = settingsRow(getString(R.string.transcription_languages), "") {
            promptTranscriptionLanguages()
        }
        languageRowSub = languageRow.findViewWithTag("subtitle")
        dictationContainer.addView(group().apply { addView(languageRow) })

        dictationContainer.addView(sectionHeader(getString(R.string.text_enhancement)))
        val postGroup = group()

        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow(getString(R.string.cleanup_transcript), getString(R.string.text_enhancement_help), postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        postGroup.addView(postProcessRow)

        cleanupProviderRow = settingsRow(getString(R.string.cleanup_service), getString(R.string.cleanup_service_groq_ready)) {
            promptCleanupProvider()
        }
        cleanupProviderRowSub = cleanupProviderRow.findViewWithTag("subtitle")
        dictationContainer.addView(cleanupProviderRow)

        customInstructionsRow = settingsRow(getString(R.string.add_custom_instructions), getString(R.string.tap_add_refinements)) {
            promptCustomInstructions()
        }
        customInstructionsRowSub = customInstructionsRow.findViewWithTag("subtitle")
        customInstructionsRowSub.maxLines = 2
        customInstructionsRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        postGroup.addView(customInstructionsRow)
        dictationContainer.addView(postGroup)

        dictationContainer.addView(sectionHeader(getString(R.string.voice_commands)))
        val voiceGroup = group()

        val isVoiceCommands = prefs().getBoolean("voice_commands_enabled", false)
        val voiceCommandsSwitch = MaterialSwitch(this).apply {
            isChecked = isVoiceCommands
            isClickable = false
        }
        val voiceCommandsRow = settingsRow(
            getString(R.string.voice_commands),
            getString(R.string.voice_commands_summary),
            voiceCommandsSwitch
        ) {
            val newVal = !voiceCommandsSwitch.isChecked
            prefs().edit().putBoolean("voice_commands_enabled", newVal).apply()
            voiceCommandsSwitch.isChecked = newVal
            refresh()
        }
        voiceGroup.addView(voiceCommandsRow)

        voiceCommandsDetailContainer = vertical(0)

        val triggerPhraseRow = settingsRow(getString(R.string.trigger_phrase), getString(R.string.tap_to_change)) { promptTriggerPhrase() }
        triggerPhraseRowSub = triggerPhraseRow.findViewWithTag("subtitle")
        voiceCommandsDetailContainer.addView(triggerPhraseRow)

        val examplesRow = settingsRow(getString(R.string.command_examples), getString(R.string.see_what_you_can_say)) { showCommandExamples() }
        voiceCommandsDetailContainer.addView(examplesRow)

        voiceGroup.addView(voiceCommandsDetailContainer)
        dictationContainer.addView(voiceGroup)

        // ================= Settings tab =================

        settingsContainer.addView(sectionHeader(getString(R.string.tab_settings)))
        settingsContainer.addView(settingsRow(
            getString(R.string.dictionary_title),
            Dictionary.load(prefs()).let {
                if (it.isEmpty()) getString(R.string.dictionary_summary)
                else getString(R.string.dictionary_word_count, it.size)
            },
        ) { promptDictionary() })

        val keyRow = settingsRow(getString(R.string.groq_api_key), getString(R.string.tap_to_set)) { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        settingsContainer.addView(group().apply { addView(keyRow) })

        settingsContainer.addView(sectionHeader(getString(R.string.bubble_size_section)))
        val bubbleSizePanel = vertical(dp(24), dp(8))
        val bubbleSizeLabel = TextView(this).apply {
            text = getString(R.string.bubble_size)
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        }
        bubbleSizePanel.addView(bubbleSizeLabel)

        val selectedBubbleSize = TextView(this).apply {
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        bubbleSizePanel.addView(selectedBubbleSize)

        bubbleSizeLabel.id = View.generateViewId()
        val bubbleSizeSlider = Slider(this).apply {
            id = View.generateViewId()
            valueFrom = BubbleSize.MIN_PERCENT.toFloat()
            valueTo = BubbleSize.MAX_PERCENT.toFloat()
            stepSize = BubbleSize.STEP_PERCENT.toFloat()
            value = currentBubbleSizePercent().toFloat()
            setLabelFormatter { localizedBubbleLabel(it.toInt()) }
            contentDescription = getString(R.string.bubble_size_accessibility)
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(64))
        }
        bubbleSizeLabel.labelFor = bubbleSizeSlider.id
        bubbleSizeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val percent = BubbleSize.sliderPercent(value.toInt())
                prefs().edit().putInt(BubbleSize.PREFERENCE_KEY, percent).apply()
                selectedBubbleSize.text = localizedBubbleLabel(percent)
                slider.contentDescription = "${getString(R.string.bubble_size_accessibility)}, ${localizedBubbleDescription(percent)}"
                WhisperAccessibilityService.instance?.refreshBubbleSize()
            }
        }
        val initialBubbleSize = currentBubbleSizePercent()
        selectedBubbleSize.text = localizedBubbleLabel(initialBubbleSize)
        bubbleSizeSlider.contentDescription = "${getString(R.string.bubble_size_accessibility)}, ${localizedBubbleDescription(initialBubbleSize)}"
        bubbleSizePanel.addView(bubbleSizeSlider)

        val rangeCaption = TextView(this).apply {
            text = getString(
                R.string.bubble_size_help,
                BubbleSize.MIN_PERCENT,
                BubbleSize.MAX_PERCENT,
                BubbleSize.DEFAULT_PERCENT,
            )
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            labelFor = bubbleSizeSlider.id
        }
        bubbleSizePanel.addView(rangeCaption)
        settingsContainer.addView(bubbleSizePanel)

        // Bubble position: a slider for the height (share of the screen height) and a
        // left/right switch. A placeholder circle previews the spot; the bubble itself
        // moves once the control has rested for a moment.
        fun savedPositionPercent() = BubbleSize.positionPercent(
            prefs().getInt(BubbleSize.POSITION_PERCENT_KEY, BubbleSize.DEFAULT_POSITION_PERCENT)
        )
        fun savedOnLeft() = prefs().getBoolean(BubbleSize.ON_LEFT_KEY, false)
        val applyPosition = Runnable { WhisperAccessibilityService.instance?.refreshPosition() }

        val positionPanel = vertical(dp(24), dp(8))
        val positionLabel = TextView(this).apply {
            text = getString(R.string.overlay_position)
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        }
        positionPanel.addView(positionLabel)
        val selectedPosition = TextView(this).apply {
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = getString(R.string.overlay_position_value, savedPositionPercent())
        }
        positionPanel.addView(selectedPosition)
        positionLabel.id = View.generateViewId()
        val positionSlider = Slider(this).apply {
            id = View.generateViewId()
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = savedPositionPercent().toFloat()
            setLabelFormatter { getString(R.string.overlay_position_value, it.toInt()) }
            contentDescription = getString(R.string.overlay_position_accessibility)
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(64))
        }
        positionLabel.labelFor = positionSlider.id
        positionSlider.addOnChangeListener { slider, sliderValue, fromUser ->
            if (fromUser) {
                val percent = sliderValue.toInt()
                prefs().edit().putInt(BubbleSize.POSITION_PERCENT_KEY, percent).apply()
                selectedPosition.text = getString(R.string.overlay_position_value, percent)
                WhisperAccessibilityService.instance?.previewPosition(percent, savedOnLeft())
                slider.removeCallbacks(applyPosition)
                slider.postDelayed(applyPosition, 500)
            }
        }
        positionPanel.addView(positionSlider)
        positionPanel.addView(TextView(this).apply {
            text = getString(R.string.overlay_position_help)
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            labelFor = positionSlider.id
        })
        settingsContainer.addView(positionPanel)

        val sideSwitch = MaterialSwitch(this).apply {
            isChecked = savedOnLeft()
            isClickable = false
        }
        val sideRow = settingsRow(
            getString(R.string.overlay_side_left), getString(R.string.overlay_side_left_summary), sideSwitch
        ) {
            val left = !sideSwitch.isChecked
            sideSwitch.isChecked = left
            prefs().edit().putBoolean(BubbleSize.ON_LEFT_KEY, left).apply()
            WhisperAccessibilityService.instance?.previewPosition(savedPositionPercent(), left)
            positionSlider.removeCallbacks(applyPosition)
            positionSlider.postDelayed(applyPosition, 500)
        }
        settingsContainer.addView(group().apply { addView(sideRow) })

        settingsContainer.addView(sectionHeader(getString(R.string.backup_section)))
        val backupGroup = group()
        backupGroup.addView(settingsRow(getString(R.string.backup_export_title), getString(R.string.backup_export_summary)) {
            backup.export()
        })
        backupGroup.addView(settingsRow(getString(R.string.backup_restore_title), getString(R.string.backup_restore_summary)) {
            backup.restore()
        })
        settingsContainer.addView(backupGroup)

        settingsContainer.addView(sectionHeader(getString(R.string.about)))
        val aboutGroup = group()
        aboutGroup.addView(settingsRow(getString(R.string.language), getString(R.string.language_summary)) {
            // Native names for the new locales; "" follows the system.
            val options = listOf(
                getString(R.string.system_default) to "",
                getString(R.string.english) to "en",
                getString(R.string.french) to "fr",
                "Italiano" to "it",
                "Español" to "es",
                "Русский" to "ru",
                "日本語" to "ja",
                "Português (Brasil)" to "pt-BR",
                "中文 (简体)" to "zh-CN",
                "Türkçe" to "tr",
            )
            val appTag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val selected = options.indexOfFirst { (_, tag) ->
                if (tag.isEmpty()) appTag == null
                else appTag == tag || appTag?.substringBefore('-') == tag.substringBefore('-')
            }.coerceAtLeast(0)
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), selected) { dialog, which ->
                    val tag = options[which].second
                    val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(tag)
                    AppCompatDelegate.setApplicationLocales(locales)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: getString(R.string.unknown_version)
        } catch (e: Exception) {
            getString(R.string.unknown_version)
        }
        // [ui] The separate "Version" row is folded into "Check for updates" below.

        aboutGroup.addView(settingsRow(getString(R.string.github), getString(R.string.github_summary)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EdiBianco/OpenWhispr")))
            } catch (e: Exception) {
                toast(getString(R.string.open_browser_failed, e.message ?: getString(R.string.unknown_error)))
            }
        })

        aboutGroup.addView(settingsRow(getString(R.string.check_updates), getString(R.string.about_current_version, versionName)) {
            updates.check(force = true)
        })

        // [privacy] Opt-in switch for the automatic check on app open (see onCreate).
        val autoUpdateSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean("auto_update_check", false)
            isClickable = false
        }
        // [ui] Subtitle follows the switch state (it used to always say "Off").
        fun autoUpdateText(on: Boolean) =
            if (on) "On: checks when the app opens (at most every 12 h)"
            else "Off: only when you tap Check for updates"
        lateinit var autoUpdateRowSub: TextView
        val autoUpdateRow = settingsRow(
            "Check for updates automatically",
            autoUpdateText(autoUpdateSwitch.isChecked),
            autoUpdateSwitch
        ) {
            val newVal = !autoUpdateSwitch.isChecked
            prefs().edit().putBoolean("auto_update_check", newVal).apply()
            autoUpdateSwitch.isChecked = newVal
            autoUpdateRowSub.text = autoUpdateText(newVal)
        }
        autoUpdateRowSub = autoUpdateRow.findViewWithTag("subtitle")
        aboutGroup.addView(autoUpdateRow)
        settingsContainer.addView(aboutGroup)

        outer.addView(statusContainer)
        outer.addView(dictationContainer)
        outer.addView(settingsContainer)
        showTab(0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(outer)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    private fun showTab(index: Int) {
        statusContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
        dictationContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        settingsContainer.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            contentDescription = getString(R.string.download_model, model.name)
            textSize = 18f
            setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }

        val delBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "✕"
            contentDescription = getString(R.string.delete_model, model.name)
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            visibility = View.GONE
            setOnClickListener { confirmDeleteModel(model) }
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(delBtn)
            addView(dlBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            getString(R.string.model_specs, getString(model.qualityResId), model.sizeMb),
            rightContainer
        ) {
            onModelAction(model)
        }

        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, delBtn
        )
        refreshCard(model)

        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        views.dlBtn.isEnabled = false
        views.dlBtn.contentDescription = getString(R.string.downloading_model, model.name)
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = getString(R.string.download_starting)

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = getString(R.string.download_progress, (state.progress * 100).toInt())
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = getString(R.string.extracting)
                    }
                    is DownloadState.Done -> {
                        // Don't load the native model right after download: sherpa-onnx can
                        // SIGSEGV/SIGABRT below the JVM, which try/catch can't intercept.
                        // Keep it installed; the user taps the row to activate it.
                        views.progress.visibility = View.GONE
                        refreshAllCards()
                        refresh()
                        toast(getString(R.string.model_downloaded_tap_to_activate, model.name))
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = getString(R.string.model_download_error, state.message)
                        views.dlBtn.isEnabled = true
                        views.dlBtn.contentDescription = getString(R.string.download_model, model.name)
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.delBtn.visibility = if (installed && !active) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            val specs = getString(R.string.model_specs, getString(model.qualityResId), model.sizeMb)
            val needed = TranscriptionLanguages.saved(prefs())
            views.subtitle.text = if (needed.isEmpty()) specs else {
                val missing = needed - model.languages
                val coverage = if (missing.isEmpty()) getString(R.string.model_covers_languages)
                    else getString(R.string.model_missing_languages, missing.joinToString { TranscriptionLanguages.displayName(it) })
                "$specs\n$coverage"
            }
        }
    }

    private fun confirmDeleteModel(model: Model) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_model_title, model.name))
            .setMessage(R.string.delete_model_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                ModelDownloader.delete(this, model)
                refreshAllCards()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val groqApiKey = SecureKeyStorage.groqApiKey(this)
        val cleanupConfig = cleanupProviderConfig()
        val cleanupApiKey = if (cleanupConfig.provider == CleanupProviderConfig.Provider.GROQ)
            groqApiKey else SecureKeyStorage.cleanupApiKey(this)
        val hasGroqKey = groqApiKey.isNotBlank()
        val hasCleanupKey = cleanupApiKey.isNotBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()
        val unrestricted = isIgnoringBatteryOptimizations()

        audioRowSub.text = if (audio) getString(R.string.granted) else getString(R.string.tap_grant_permission)
        // Three states: running, switched on but not connected (killed or frozen), or off.
        accRowSub.text = when {
            acc -> getString(R.string.enabled)
            ServiceWatchdog.isEnabledInSettings(this) -> getString(R.string.accessibility_enabled_not_running)
            else -> getString(R.string.tap_enable_settings)
        }
        batteryRowSub.text = if (unrestricted)
            getString(R.string.battery_unrestricted)
        else
            getString(R.string.battery_restriction_hint)

        // --- Setup checklist card ---
        val allOk = audio && acc && unrestricted
        val doneCount = listOf(audio, acc, unrestricted).count { it }

        setupCollapsedRow.visibility = if (allOk) View.VISIBLE else View.GONE
        setupCollapsedRowSub.text = if (setupExpanded) getString(R.string.tap_to_collapse) else getString(R.string.tap_to_review)

        setupDoneSummary.visibility = if (!allOk && doneCount > 0) View.VISIBLE else View.GONE
        setupDoneSummary.text = "✓ ${resources.getQuantityString(R.plurals.setup_steps_ready, doneCount, doneCount)}"

        fun rowVisibility(ok: Boolean) =
            if (!ok || (allOk && setupExpanded)) View.VISIBLE else View.GONE

        audioRow.visibility = rowVisibility(audio)
        accRow.visibility = rowVisibility(acc)
        accCaption.visibility = accRow.visibility
        batteryRow.visibility = rowVisibility(unrestricted)

        audioDot.background = dotDrawable(if (audio) DOT_GREEN else DOT_RED)
        accDot.background = dotDrawable(if (acc) DOT_GREEN else DOT_RED)
        batteryDot.background = dotDrawable(if (unrestricted) DOT_GREEN else DOT_RED)

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        cloudModelRow.visibility = if (useLocal) View.GONE else View.VISIBLE
        cloudModelRowSub.text = cloudModelSummary()
        languageRowSub.text = languageSummary()
        customInstructionsRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        cleanupProviderRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        cleanupProviderRowSub.text = cleanupProviderSummary()

        val voiceCommandsEnabled = prefs().getBoolean("voice_commands_enabled", false)
        voiceCommandsDetailContainer.visibility = if (voiceCommandsEnabled) View.VISIBLE else View.GONE
        triggerPhraseRowSub.text = getString(
            R.string.trigger_phrase_display,
            prefs().getString("command_trigger_phrase", getString(R.string.default_trigger_phrase))
                ?: getString(R.string.default_trigger_phrase)
        )

        keyRowSub.text = if (hasGroqKey) getString(R.string.saved_securely) else getString(R.string.key_needed_hint)

        val customInstructions = prefs().getString("custom_instructions", "") ?: ""
        customInstructionsRowSub.text = if (customInstructions.isBlank())
            getString(R.string.tap_add_refinements)
        else
            customInstructions.replace("\n", " ")

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasGroqKey
        val postReady = !usePostProcessing || hasCleanupKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        val (missing, action) = when {
            !audio -> getString(R.string.status_missing_audio) to null
            !acc -> getString(R.string.status_missing_accessibility) to null
            useLocal && !hasModel -> getString(R.string.status_missing_model) to { tabLayout.getTabAt(1)?.select(); Unit }
            !useLocal && !hasGroqKey -> getString(R.string.status_missing_key) to { tabLayout.getTabAt(2)?.select(); Unit }
            usePostProcessing && !hasCleanupKey -> getString(R.string.status_missing_cleanup_key) to { tabLayout.getTabAt(2)?.select(); Unit }
            else -> null to null
        }
        val mode = (if (useLocal) getString(R.string.mode_on_device) else getString(R.string.mode_cloud)) +
            (if (usePostProcessing) getString(R.string.mode_cleanup_suffix) else "")
        statusSubtitle.text = if (ready) getString(R.string.status_ready_mode, mode) else missing ?: getString(R.string.setup_required)
        statusSubtitle.setTextColor(if (ready) attrColor(androidx.appcompat.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        statusDotView.background = dotDrawable(if (ready) DOT_GREEN else DOT_RED)
        statusAction = if (ready) null else action
        statusRow.isClickable = statusAction != null

        // [ui] The cloud switch's subtitle says where audio goes.
        cloudRowSub.text = getString(
            if (useLocal) R.string.cloud_switch_off else R.string.cloud_switch_on,
        )

        refreshAllCards()
        maybeShowBatteryWarning(acc, unrestricted)
    }

    /** Android 13+ silently disables the Accessibility toggle for apps
     * installed outside the Play Store ("Restricted settings"), with no
     * explanation in the Settings UI itself -- it just looks broken. Walks
     * the user through unlocking it before sending them to the system
     * screen, instead of letting them hit a dead end and assume the app
     * doesn't work. */
    private fun showRestrictedSettingsHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.restricted_settings_title)
            .setMessage(R.string.restricted_settings_message)
            .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // --- Battery optimization ---

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) { toast(getString(R.string.already_unrestricted)); return }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            // Some OEMs block the direct per-app request intent -- fall back
            // to the general battery-optimization list where the user can
            // find OpenWispr and exempt it manually.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast(getString(R.string.open_battery_settings_failed, e2.message ?: getString(R.string.unknown_error)))
            }
        }
    }

    /** Nags the user, once per app-open, if the accessibility service is on
     * but Android is still free to kill it to save battery -- this is the
     * single biggest cause of the overlay silently disappearing until the
     * user re-opens the app. */
    private fun maybeShowBatteryWarning(accessibilityEnabled: Boolean, unrestricted: Boolean) {
        if (!accessibilityEnabled || unrestricted || batteryWarningShown) return
        batteryWarningShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.keep_dictation_running)
            .setMessage(R.string.battery_warning_message)
            .setPositiveButton(R.string.disable_restrictions) { _, _ -> requestBatteryExemption() }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    private fun promptDictionary() {
        val input = EditText(this).apply {
            hint = getString(R.string.dictionary_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            setText(Dictionary.load(prefs()).joinToString("\n"))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_title)
            .setMessage(R.string.dictionary_saved_local_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                Dictionary.save(prefs(), Dictionary.parse(input.text.toString()))
                WhisperAccessibilityService.instance?.reloadModel()
                toast(getString(R.string.dictionary_added))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun promptApiKey() {
        val link = TextView(this).apply {
            text = android.text.Html.fromHtml(
                getString(R.string.groq_key_help_html),
                android.text.Html.FROM_HTML_MODE_LEGACY
            )
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        }
        val input = EditText(this).apply {
            hint = "gsk_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(SecureKeyStorage.groqApiKey(this@MainActivity))
        }
        val container = vertical(dp(24), dp(8)).apply {
            addView(link)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.groq_api_key)
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                SecureKeyStorage.saveGroqApiKey(this, input.text.toString().trim())
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun languageSummary(): String {
        val selected = TranscriptionLanguages.saved(prefs())
        if (selected.isEmpty()) return getString(R.string.transcription_languages_auto)
        return TranscriptionLanguages.ordered(selected)
            .filter { it in selected }
            .joinToString(", ") { TranscriptionLanguages.displayName(it) }
    }

    /** Searchable checklist; selected languages stay on top. Save with no
     * selection (or the Auto button) returns to auto-detect. */
    private fun promptTranscriptionLanguages() {
        val working = TranscriptionLanguages.saved(prefs()).toMutableSet()
        val search = EditText(this).apply {
            hint = getString(R.string.transcription_languages_search)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val list = vertical(0)
        fun renderList(query: String) {
            list.removeAllViews()
            val q = query.trim()
            TranscriptionLanguages.ordered(working)
                .filter { q.isEmpty() || TranscriptionLanguages.displayName(it).contains(q, ignoreCase = true) || it.contains(q, ignoreCase = true) }
                .forEach { code ->
                    list.addView(CheckBox(this).apply {
                        text = "${TranscriptionLanguages.displayName(code)} ($code)"
                        isChecked = code in working
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) working.add(code) else working.remove(code)
                        }
                    })
                }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = renderList(s?.toString().orEmpty())
        })
        renderList("")
        val container = vertical(dp(24), dp(8)).apply {
            addView(search)
            addView(ScrollView(this@MainActivity).apply {
                addView(list)
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(360))
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.transcription_languages)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> saveTranscriptionLanguages(working) }
            .setNeutralButton(R.string.transcription_languages_auto) { _, _ -> saveTranscriptionLanguages(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveTranscriptionLanguages(selected: Set<String>) {
        prefs().edit().putStringSet(TranscriptionLanguages.PREF_KEY, selected.toSet()).apply()
        refresh()
        if (prefs().getBoolean("use_local", true)) WhisperAccessibilityService.instance?.reloadModel()
        suggestModelForLanguages(selected)
    }

    /** Offers a local model that covers [needed] when the current one doesn't.
     * Cloud transcription already covers every Whisper language, so no prompt there. */
    private fun suggestModelForLanguages(needed: Set<String>) {
        if (needed.isEmpty() || !prefs().getBoolean("use_local", true)) return
        val current = MODEL_CATALOG.firstOrNull { it.archive == prefs().getString("model_name", "") }
        if (current?.languages?.containsAll(needed) == true) return

        val fits = MODEL_CATALOG.filter { it.languages.containsAll(needed) }
        val names = needed.joinToString { TranscriptionLanguages.displayName(it) }
        val installedFit = fits.filter { ModelDownloader.isInstalled(this, it) }
        if (installedFit.isNotEmpty()) {
            val target = installedFit.sortedByDescending { it.sizeMb }.first()
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language_switch_title)
                .setMessage(getString(R.string.language_switch_message, target.name, names))
                .setPositiveButton(R.string.language_switch_action) { _, _ -> selectModel(target.archive) }
                .setNegativeButton(R.string.language_keep_current, null)
                .show()
        } else {
            val best = fits.sortedByDescending { it.sizeMb }.first()
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language_no_model_title)
                .setMessage(getString(R.string.language_no_model_message, best.name, names))
                .setPositiveButton(getString(R.string.language_download_model, best.name)) { _, _ -> onModelAction(best) }
                .setNeutralButton(R.string.language_use_cloud) { _, _ ->
                    prefs().edit().putBoolean("use_local", false).apply()
                    refresh()
                }
                .setNegativeButton(R.string.language_keep_current, null)
                .show()
        }
    }

    private fun promptCleanupProvider() {
        val config = cleanupProviderConfig()
        val providers = CleanupProviderConfig.Provider.entries
        val providerPicker = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.groq_recommended), getString(R.string.custom_service)),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(providers.indexOf(config.provider))
        }
        fun fieldLabel(labelRes: Int) = TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), 0, dp(2))
        }
        val baseUrlLabel = fieldLabel(R.string.custom_service_url)
        val baseUrl = EditText(this).apply {
            hint = getString(R.string.openrouter_url_placeholder)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(config.baseUrl)
        }
        val modelLabel = fieldLabel(R.string.custom_service_model)
        // One field, two providers: an OpenRouter model id sent to Groq (or the
        // reverse) is a failing request, so each provider keeps its own value
        // while you switch between them, and the hint shows that provider's
        // default for a blank field.
        var groqModelDraft = config.groqModel
        var customModelDraft = config.model
        val model = EditText(this).apply {
            hint = CleanupProviderConfig.DEFAULT_MODEL
            setText(if (config.provider == CleanupProviderConfig.Provider.GROQ) config.groqModel else config.model)
        }
        val apiKeyLabel = fieldLabel(R.string.custom_service_key)
        val apiKey = EditText(this).apply {
            hint = getString(R.string.api_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(SecureKeyStorage.cleanupApiKey(this@MainActivity))
        }
        val details = TextView(this).apply {
            text = getString(R.string.custom_service_help)
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), 0, 0)
        }
        val customFields = listOf<View>(baseUrlLabel, baseUrl, apiKeyLabel, apiKey, details)
        fun updateCustomFields() {
            val provider = providers[providerPicker.selectedItemPosition]
            customFields.forEach { it.visibility = if (provider.requiresCustomConfiguration) View.VISIBLE else View.GONE }
            if (provider == CleanupProviderConfig.Provider.GROQ) {
                customModelDraft = model.text.toString()
                model.setText(groqModelDraft)
                model.hint = CleanupProviderConfig.GROQ_MODEL
            } else {
                groqModelDraft = model.text.toString()
                model.setText(customModelDraft)
                model.hint = CleanupProviderConfig.DEFAULT_MODEL
            }
        }
        val container = vertical(dp(24), dp(8)).apply {
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.choose_cleanup_provider)
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, 0, 0, dp(8))
            })
            addView(providerPicker)
            addView(baseUrlLabel)
            addView(baseUrl)
            addView(modelLabel)
            addView(model)
            addView(apiKeyLabel)
            addView(apiKey)
            addView(details)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cleanup_provider_title)
            .setView(scroll)
            .setPositiveButton(getString(R.string.save), null)
            .setNeutralButton(R.string.clear_cleanup_key, null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            fun updateNeutralButton() {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).visibility =
                    if (providers[providerPicker.selectedItemPosition].requiresCustomConfiguration) View.VISIBLE else View.GONE
            }
            updateCustomFields()
            updateNeutralButton()
            providerPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateCustomFields()
                    updateNeutralButton()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedProvider = providers[providerPicker.selectedItemPosition]
                val typedModel = model.text.toString().trim()
                val isGroq = selectedProvider == CleanupProviderConfig.Provider.GROQ
                val nextConfig = CleanupProviderConfig(
                    provider = selectedProvider,
                    baseUrl = baseUrl.text.toString().trim().ifBlank { CleanupProviderConfig.DEFAULT_BASE_URL },
                    model = if (isGroq) customModelDraft.trim() else typedModel,
                    groqModel = if (isGroq) typedModel else groqModelDraft.trim(),
                )
                if (selectedProvider.requiresCustomConfiguration) {
                    try {
                        nextConfig.chatCompletionsUrl()
                    } catch (_: IllegalArgumentException) {
                        toast(getString(R.string.invalid_cleanup_url))
                        return@setOnClickListener
                    }
                }
                prefs().edit()
                    .putString("cleanup_provider", selectedProvider.preferenceValue)
                    .putString("cleanup_base_url", nextConfig.baseUrl)
                    .putString("cleanup_model", nextConfig.model)
                    .putString("cleanup_groq_model", nextConfig.groqModel)
                    .apply()
                if (selectedProvider.requiresCustomConfiguration) {
                    apiKey.text.toString().trim().takeIf { it.isNotBlank() }
                        ?.let { SecureKeyStorage.saveCleanupApiKey(this, it) }
                }
                dialog.dismiss()
                refresh()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                SecureKeyStorage.saveCleanupApiKey(this, "")
                apiKey.text.clear()
                toast(getString(R.string.cleanup_api_key_cleared))
                refresh()
            }
        }
        dialog.show()
    }

    private fun promptCustomInstructions() {
        // The base cleanup prompt itself is fixed in PostProcessor and never
        // shown here -- this only lets the user append their own extra
        // refinements on top of it (see PostProcessor.effectivePrompt).
        val input = EditText(this).apply {
            hint = getString(R.string.custom_instructions_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP or Gravity.START
            setText(prefs().getString("custom_instructions", ""))
        }
        val content = vertical(0, 0).apply {
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.custom_instructions_help)
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, 0, 0, dp(8))
            })
            addView(input)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_custom_instructions))
            .setView(scrollableDialogContent(content))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                prefs().edit().putString("custom_instructions", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        showResizingDialog(dialog)
    }

    private fun promptTriggerPhrase() {
        val input = EditText(this).apply {
            hint = getString(R.string.default_trigger_phrase)
            setText(prefs().getString("command_trigger_phrase", getString(R.string.default_trigger_phrase)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.trigger_phrase)
            .setMessage(R.string.command_mode_help)
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val phrase = input.text.toString().trim()
                prefs().edit()
                    .putString("command_trigger_phrase", if (phrase.isBlank()) getString(R.string.default_trigger_phrase) else phrase)
                    .apply()
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCommandExamples() {
        val trigger = prefs().getString("command_trigger_phrase", getString(R.string.default_trigger_phrase))
            ?: getString(R.string.default_trigger_phrase)
        val message = getString(R.string.command_examples_body, trigger)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.command_examples))
            .setMessage(message)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    // Rows, panels, dots and dialog plumbing come from UiKit.kt.

    private fun cleanupProviderConfig() = CleanupProviderConfig.fromPreferences(
        prefs().getString("cleanup_provider", null),
        prefs().getString("cleanup_base_url", null),
        prefs().getString("cleanup_model", null),
        prefs().getString("cleanup_groq_model", null),
    )

    /** The cloud transcription model to use, stored or default. */
    private fun cloudModelId() =
        TranscriberClient.modelId(prefs().getString(TranscriberClient.MODEL_PREF_KEY, null))

    private fun cloudModelSummary(): String =
        TranscriberClient.modelById(cloudModelId()).let { "${it.label} · ${it.hint}" }

    /** Groq Whisper model used for cloud transcription. */
    private fun promptCloudModel() {
        val models = TranscriberClient.MODELS
        val labels = models.map { "${it.label} · ${it.hint}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cloud_model)
            .setSingleChoiceItems(labels, models.indexOfFirst { it.id == cloudModelId() }) { dialog, which ->
                prefs().edit().putString(TranscriberClient.MODEL_PREF_KEY, models[which].id).apply()
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cleanupProviderSummary(): String {
        val config = cleanupProviderConfig()
        val configured = when (config.provider) {
            CleanupProviderConfig.Provider.GROQ -> SecureKeyStorage.groqApiKey(this).isNotBlank()
            CleanupProviderConfig.Provider.OPENAI_COMPATIBLE -> SecureKeyStorage.cleanupApiKey(this).isNotBlank()
        }
        val summary = when (config.provider) {
            CleanupProviderConfig.Provider.GROQ ->
                if (configured) R.string.cleanup_service_groq_ready else R.string.cleanup_service_groq_setup
            CleanupProviderConfig.Provider.OPENAI_COMPATIBLE ->
                if (configured) R.string.cleanup_service_custom_ready else R.string.cleanup_service_custom_setup
        }
        return getString(summary)
    }

    private fun currentBubbleSizePercent(): Int {
        val storedValue = try {
            prefs().getInt(BubbleSize.PREFERENCE_KEY, BubbleSize.DEFAULT_PERCENT)
        } catch (_: ClassCastException) {
            null
        }
        return BubbleSize.preferencePercent(storedValue)
    }
    private fun localizedBubbleLabel(percent: Int) = BubbleSize.valueLabel(
        percent,
        standard = getString(R.string.bubble_standard),
        smaller = getString(R.string.bubble_smaller),
        larger = getString(R.string.bubble_larger),
    )
    private fun localizedBubbleDescription(percent: Int) = BubbleSize.accessibilityDescription(
        percent,
        standard = getString(R.string.bubble_standard_accessibility),
        smaller = getString(R.string.bubble_smaller_accessibility),
        larger = getString(R.string.bubble_larger_accessibility),
        percentUnit = getString(R.string.percent_unit),
    )

    private fun showWelcome() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setPositiveButton(R.string.welcome_restore) { _, _ ->
                prefs().edit().putBoolean("welcome_done", true).apply()
                backup.restore()
            }
            .setNegativeButton(R.string.welcome_start_fresh) { _, _ ->
                prefs().edit().putBoolean("welcome_done", true).apply()
            }
            .setCancelable(false)
            .show()
    }
}
