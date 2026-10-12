package com.edib.openwhispr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.tabs.TabLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
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
        val dlBtn: MaterialButton
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


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

        checkForUpdate()

        val outer = vertical(0, 0)

        // Top large header (like "Connected devices"), with the app icon
        // alongside the name.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(24))
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

        statusContainer = vertical(0)
        dictationContainer = vertical(0)
        settingsContainer = vertical(0)

        // ================= Status tab =================

        val statusRow = settingsRow(getString(R.string.tab_status), getString(R.string.checking))
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        statusContainer.addView(statusRow)

        // --- Setup checklist card ---
        setupCollapsedRow = settingsRow(getString(R.string.setup), getString(R.string.checking)) {
            setupExpanded = !setupExpanded
            refresh()
        }
        setupCollapsedRowSub = setupCollapsedRow.findViewWithTag("subtitle")
        statusContainer.addView(setupCollapsedRow)

        setupDoneSummary = TextView(this).apply {
            textSize = 14f
            setTextColor(DOT_GREEN)
            setPadding(dp(24), 0, dp(24), dp(8))
        }
        statusContainer.addView(setupDoneSummary)

        audioDot = statusDot()
        audioRow = settingsRow(getString(R.string.audio_permission), getString(R.string.checking), leading = audioDot) {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        statusContainer.addView(audioRow)

        accDot = statusDot()
        accRow = settingsRow(getString(R.string.accessibility_service), getString(R.string.checking), leading = accDot) {
            val alreadyEnabled = WhisperAccessibilityService.instance != null
            if (!alreadyEnabled && android.os.Build.VERSION.SDK_INT >= 33) {
                showRestrictedSettingsHelp()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        statusContainer.addView(accRow)

        accCaption = TextView(this).apply {
            text = getString(R.string.accessibility_help)
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            alpha = 0.8f
            setPadding(dp(24), 0, dp(24), dp(12))
        }
        statusContainer.addView(accCaption)

        batteryDot = statusDot()
        batteryRow = settingsRow(getString(R.string.battery_optimization), getString(R.string.checking), leading = batteryDot) {
            requestBatteryExemption()
        }
        batteryRowSub = batteryRow.findViewWithTag("subtitle")
        statusContainer.addView(batteryRow)

        // --- Background service ---
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
        statusContainer.addView(serviceRow)

        // ================= Dictation tab =================

        dictationContainer.addView(sectionHeader(getString(R.string.engine)))

        val isCloud = !prefs().getBoolean("use_local", true)
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow(getString(R.string.cloud_transcription), getString(R.string.requires_groq_key), cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        dictationContainer.addView(cloudRow)

        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader(getString(R.string.local_models)))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        dictationContainer.addView(modelContainer)

        dictationContainer.addView(sectionHeader(getString(R.string.text_enhancement)))

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
        dictationContainer.addView(postProcessRow)

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
        dictationContainer.addView(customInstructionsRow)

        dictationContainer.addView(sectionHeader(getString(R.string.voice_commands)))

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
        dictationContainer.addView(voiceCommandsRow)

        voiceCommandsDetailContainer = vertical(0)

        val triggerPhraseRow = settingsRow(getString(R.string.trigger_phrase), getString(R.string.tap_to_change)) { promptTriggerPhrase() }
        triggerPhraseRowSub = triggerPhraseRow.findViewWithTag("subtitle")
        voiceCommandsDetailContainer.addView(triggerPhraseRow)

        val examplesRow = settingsRow(getString(R.string.command_examples), getString(R.string.see_what_you_can_say)) { showCommandExamples() }
        voiceCommandsDetailContainer.addView(examplesRow)

        dictationContainer.addView(voiceCommandsDetailContainer)

        // ================= Settings tab =================

        settingsContainer.addView(sectionHeader(getString(R.string.tab_settings)))

        val keyRow = settingsRow(getString(R.string.groq_api_key), getString(R.string.tap_to_set)) { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        settingsContainer.addView(keyRow)

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

        settingsContainer.addView(sectionHeader(getString(R.string.about)))
        settingsContainer.addView(settingsRow(getString(R.string.language), getString(R.string.language_summary)) {
            val languages = arrayOf(
                getString(R.string.system_default),
                getString(R.string.english),
                getString(R.string.french)
            )
            val appLocales = AppCompatDelegate.getApplicationLocales()
            val selected = when {
                appLocales.isEmpty -> 0
                appLocales.get(0)?.language == "fr" -> 2
                else -> 1
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(languages, selected) { dialog, which ->
                    val locales = when (which) {
                        0 -> LocaleListCompat.getEmptyLocaleList()
                        1 -> LocaleListCompat.forLanguageTags("en")
                        else -> LocaleListCompat.forLanguageTags("fr")
                    }
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
        settingsContainer.addView(settingsRow(getString(R.string.version), versionName))

        settingsContainer.addView(settingsRow(getString(R.string.github), getString(R.string.github_summary)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EdiBianco/OpenWhispr")))
            } catch (e: Exception) {
                toast(getString(R.string.open_browser_failed, e.message ?: getString(R.string.unknown_error)))
            }
        })

        settingsContainer.addView(settingsRow(getString(R.string.check_updates), getString(R.string.tap_check_now)) {
            checkForUpdate(force = true)
        })

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

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn
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
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast(getString(R.string.model_ready, model.name))
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

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = getString(R.string.model_specs, getString(model.qualityResId), model.sizeMb)
        }
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
        accRowSub.text = if (acc) getString(R.string.enabled) else getString(R.string.tap_enable_settings)
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

        keyRowSub.text = if (hasGroqKey) getString(R.string.saved_securely) else getString(R.string.tap_to_set)

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

        statusSubtitle.text = if (ready) getString(R.string.status_ready) else getString(R.string.setup_required)
        statusSubtitle.setTextColor(if (ready) attrColor(androidx.appcompat.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))

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

    /** Checks this repo's GitHub Releases. No backend involved. Shows a
     * dialog linking to the release page when a newer version is
     * available. Runs automatically (and silently, when nothing's new)
     * once per app-open; [force] bypasses the cache interval and always
     * gives feedback, for the manual getString(R.string.check_updates) row. */
    private fun checkForUpdate(force: Boolean = false) {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: return

        UpdateChecker.checkForUpdate(prefs(), currentVersion, force) { info ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.update_available)
                        .setMessage(
                            buildString {
                                append(getString(R.string.update_available_message, info.version, currentVersion))
                                if (!info.notes.isNullOrBlank()) {
                                    append("\n\n${getString(R.string.whats_new)}:\n")
                                    append(info.notes)
                                }
                            }
                        )
                        .setPositiveButton(getString(R.string.update)) { _, _ -> downloadAndInstallUpdate(info) }
                        .setNegativeButton(getString(R.string.later), null)
                        .show()
                } else if (force) {
                    toast(getString(R.string.up_to_date, currentVersion))
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
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
            } catch (e: Exception) {
                toast(getString(R.string.open_browser_failed, e.message ?: getString(R.string.unknown_error)))
            }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.allow_install_updates)
                .setMessage(R.string.allow_install_updates_message)
                .setPositiveButton(R.string.continue_button) { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        toast(getString(R.string.open_settings_failed, e.message ?: getString(R.string.unknown_error)))
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }

        toast(getString(R.string.downloading_update))
        UpdateChecker.downloadApk(this, apkUrl) { file, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    toast(getString(R.string.download_failed, error ?: getString(R.string.unknown_error)))
                    return@runOnUiThread
                }
                installApk(file)
            }
        }
    }

    private fun installApk(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.installer_start_failed, e.message ?: getString(R.string.unknown_error)))
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
        val model = EditText(this).apply {
            hint = CleanupProviderConfig.DEFAULT_MODEL
            setText(config.model)
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
        val customFields = listOf<View>(baseUrlLabel, baseUrl, modelLabel, model, apiKeyLabel, apiKey, details)
        fun updateCustomFields() {
            val show = providers[providerPicker.selectedItemPosition].requiresCustomConfiguration
            customFields.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
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
                val nextConfig = CleanupProviderConfig(
                    provider = selectedProvider,
                    baseUrl = baseUrl.text.toString().trim().ifBlank { CleanupProviderConfig.DEFAULT_BASE_URL },
                    model = model.text.toString().trim().ifBlank { CleanupProviderConfig.DEFAULT_MODEL },
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

    // --- UI Helpers ---

    private fun settingsRow(
        title: String,
        subtitle: String,
        widget: View? = null,
        leading: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        if (leading != null) row.addView(leading)

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }

        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun scrollableDialogContent(content: View) = ScrollView(this).apply {
        isFillViewport = true
        setPadding(dp(24), dp(8), dp(24), dp(8))
        addView(content, FrameLayout.LayoutParams(LP_MATCH, LP_WRAP))
    }

    private fun showResizingDialog(dialog: android.app.AlertDialog) {
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun dotDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun statusDot(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(12)
        }
        background = dotDrawable(DOT_RED)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun cleanupProviderConfig() = CleanupProviderConfig.fromPreferences(
        prefs().getString("cleanup_provider", null),
        prefs().getString("cleanup_base_url", null),
        prefs().getString("cleanup_model", null),
    )

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
    private fun prefs() = getSharedPreferences("openwhispr", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val DOT_GREEN = 0xFF34C759.toInt()
        private const val DOT_RED = 0xFFEF4444.toInt()
    }
}
