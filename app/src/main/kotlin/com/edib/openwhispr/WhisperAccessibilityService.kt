package com.edib.openwhispr

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "OpenWhispr"

        // How often we re-check the focused node as a failsafe, in case an
        // app never fires a focus-related accessibility event at all.
        private const val FOCUS_POLL_MS = 500L

        // A focus/keyboard change must hold this long before the overlay shows or hides.
        private const val OVERLAY_DEBOUNCE_MS = 300L

        // Peak 16-bit amplitude below which a recording is treated as silence.
        // Whisper invents text such as "Thank you." for silent audio.
        private const val SILENCE_PEAK = 500

        private const val NOTIF_CHANNEL_ID = "openwhispr_service"
        private const val NOTIF_ID = 1
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayShown = false

    // Two independent signals feed overlay visibility (OR'd together): an
    // accessibility-tree focus check (event-driven AND polled as a failsafe,
    // since some apps -- notably WhatsApp/Telegram -- don't reliably fire
    // focus events for their custom message composers) and the system
    // keyboard's own visibility (window-manager-level, doesn't depend on the
    // foreground app cooperating with accessibility at all).
    private var accessibilityFocusSignal = false
    private var imeVisibleSignal = false
    private var stopTone: ToneGenerator? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Microphone capture; levels come back on its recording thread. */
    private val recorder = VoiceRecorder { level -> handler.post { onAudioLevel(level) } }

    /** All overlay rendering lives in OverlayWindow; the service owns when it
     * shows and what state it is in. */
    private val overlay = OverlayWindow(
        context = this,
        handler = handler,
        onTap = { onTap() },
        onImeVisibilityChanged = { visible -> onKeyboardVisibilityChanged(visible) },
        bubbleSizePercent = { currentBubbleSizePercent() },
        homePosition = { ringSize -> savedHomePosition(ringSize) },
        isIdle = { state == State.IDLE },
    )

    /** All accessibility-node text access lives in TextInjector. */
    private val injector by lazy { TextInjector(this) { text, durationMs -> overlay.showFeedback(text, durationMs) } }

    private val focusPoller = object : Runnable {
        override fun run() {
            refreshAccessibilityFocusSignal()
            handler.postDelayed(this, FOCUS_POLL_MS)
        }
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null
    // [privacy] Set by initLocalModel (background thread), read on tap.
    @Volatile private var localModelLoading = false
    @Volatile private var localModelError: String? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        try {
            stopTone?.release()
            stopTone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        } catch (e: RuntimeException) {
            stopTone = null
            Log.w(TAG, "Recording stop tone unavailable", e)
        }
        overlay.show()
        startForegroundNotification()
        updateOverlayVisibility()
        handler.post(focusPoller)
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never let a bad event (or a bug in our own handling of it) crash
        // the whole app process -- an uncaught exception here previously
        // could take the service down entirely, requiring the user to clear
        // app storage and re-grant the accessibility permission.
        try {
            refreshAccessibilityFocusSignal()
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent handling failed", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        overlay.removePositionPreview()
        stopTone?.release()
        stopTone = null
        handler.removeCallbacks(overlayVisibilityDebounce)
        handler.removeCallbacks(focusPoller)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
        overlay.remove()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        // Promotes the service's process priority and gives it a persistent
        // (silent, minimum-importance) notification. This is what keeps the
        // background service running -- both against being swiped away in
        // Recents and against routine memory-pressure kills. It's a
        // best-effort measure: some OEM battery managers (MIUI, ColorOS,
        // etc.) still require the user to manually whitelist the app.
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_content_title))
                .setContentText(getString(R.string.notification_content_text))
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Foreground promotion is a resilience nice-to-have, not a
            // functional requirement -- dictation still works without it.
            Log.e(TAG, "Failed to start foreground notification", e)
        }
    }

    private fun initLocalModel() {
        // A corrupted/incompatible model file or a native (sherpa-onnx)
        // load failure here must not be allowed to crash the process --
        // that takes the whole accessibility service down with it.
        // [privacy] Track loading/failure so local mode can tell the user
        // why it isn't ready instead of silently using the cloud.
        localModelLoading = true
        localModelError = null
        try {
            // [privacy] A build without the native engine can never transcribe
            // locally; say so rather than failing (or crashing) on every load.
            if (!LocalTranscriber.nativeEngineAvailable) {
                localTranscriber = null
                localModelError = "This build doesn't include the on-device speech engine"
                return
            }
            var attempted = false
            val modelName = prefs().getString("model_name", "") ?: ""
            if (modelName.isBlank()) {
                // Auto-detect first available model
                val models = LocalTranscriber.availableModels(this)
                if (models.isNotEmpty()) {
                    Log.i(TAG, "Auto-detected model: ${models.first()}")
                    attempted = true
                    localTranscriber = LocalTranscriber.create(this, models.first(), TranscriptionLanguages.localWhisperLanguage(prefs()))
                }
            } else {
                attempted = true
                localTranscriber = LocalTranscriber.create(this, modelName, TranscriptionLanguages.localWhisperLanguage(prefs()))
            }
            if (localTranscriber != null) {
                Log.i(TAG, "Local transcription ready")
            } else {
                Log.i(TAG, "No local model loaded")
                if (attempted) localModelError = "Couldn't load the local model. Try selecting or re-downloading it"
            }
        } catch (e: Throwable) {
            // [security] Throwable, not Exception: native/linkage failures are
            // Errors and previously escaped this thread, crashing the service.
            Log.e(TAG, "Local model init failed", e)
            localTranscriber = null
            localModelError = "Couldn't load the local model. Try selecting or re-downloading it"
        } finally {
            localModelLoading = false
        }
    }

    /** [privacy] Why local mode can't transcribe right now. Shown instead of
     * sending audio to the cloud. */
    private fun localUnavailableMessage(): String =
        if (localModelLoading) "Local model is still loading, try again in a moment"
        else localModelError
            ?: "No local model. Download one in OpenWispr, or turn on cloud transcription"

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay visibility (multi-signal, OR'd together) ---

    private fun refreshAccessibilityFocusSignal() {
        try {
            val root = rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            // The foreground app's own tree is only one witness: web-based
            // composers (the Claude app's Code tab) often don't report an
            // editable focus at all. The other two come from the system.
            accessibilityFocusSignal = (focused != null && isEditableTextField(focused)) ||
                hasActiveTextInput() || isKeyboardWindowShown()
            focused?.recycle()
            root?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "refreshAccessibilityFocusSignal failed", e)
        } finally {
            updateOverlayVisibility()
        }
    }

    private fun isEditableTextField(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable || className.contains("EditText")
    }

    /** Android 13+: a text field has an open input connection to this
     * service (flagInputMethodEditor), the same one injectText types
     * through. True whatever the app's accessibility tree says. */
    private fun hasActiveTextInput(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val method = inputMethod ?: return false
        val inputType = method.currentInputEditorInfo?.inputType ?: return false
        return method.currentInputStarted && inputType != InputType.TYPE_NULL
    }

    /** The keyboard's own window is on screen. Needs
     * flagRetrieveInteractiveWindows; without it the list is empty. */
    private fun isKeyboardWindowShown(): Boolean =
        try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (e: Exception) {
            false
        }

    /** Fed by the overlay view's WindowInsets listener -- catches apps whose
     * custom composers (WhatsApp, Telegram, ...) never fire accessibility
     * focus events at all, since this signal comes from the window manager
     * rather than the foreground app's own accessibility tree. */
    private fun onKeyboardVisibilityChanged(visible: Boolean) {
        imeVisibleSignal = visible
        updateOverlayVisibility()
    }

    private fun wantsOverlay(): Boolean =
        masterEnabled() && (accessibilityFocusSignal || imeVisibleSignal || state != State.IDLE)

    private fun updateOverlayVisibility() {
        val shouldShow = wantsOverlay()
        handler.removeCallbacks(overlayVisibilityDebounce)
        if (shouldShow == overlayShown) {
            // Self-heal: the view can drift from overlayShown (e.g. after an app
            // switch), leaving it hidden while it should show. Re-show it here.
            if (shouldShow && !overlay.isVisible) overlay.animateIn()
            return
        }
        // Only act once the new state has held for OVERLAY_DEBOUNCE_MS, so rapid
        // focus flapping between apps doesn't restart the fades.
        handler.postDelayed(overlayVisibilityDebounce, OVERLAY_DEBOUNCE_MS)
    }

    private val overlayVisibilityDebounce = Runnable { applyOverlayVisibility() }

    private fun applyOverlayVisibility() {
        val shouldShow = wantsOverlay()
        if (shouldShow == overlayShown) return
        overlayShown = shouldShow
        if (shouldShow) overlay.animateIn() else overlay.animateOut()
    }

    private fun masterEnabled() = prefs().getBoolean("service_master_enabled", true)

    /** Called from MainActivity when the "Background service" switch is
     * toggled, so an already-idle overlay hides/shows immediately instead
     * of waiting for the next focus event or poll tick. */
    fun refreshMasterEnabled() {
        handler.post { updateOverlayVisibility() }
    }

    /** Re-lays out the bubble after the user changed its size in Settings. */
    fun refreshBubbleSize() {
        overlay.refreshBubbleSize()
    }

    /** The bubble's home position from the Settings slider and side switch. */
    private fun savedHomePosition(ringSize: Int): BubbleSize.Position = BubbleSize.positionFor(
        prefs().getInt(BubbleSize.POSITION_PERCENT_KEY, BubbleSize.DEFAULT_POSITION_PERCENT),
        prefs().getBoolean(BubbleSize.ON_LEFT_KEY, false),
        screenW, screenH, ringSize, (BubbleSize.MARGIN_DP * dp).toInt()
    )

    /** Puts the bubble back at its saved home position and drops the preview. */
    fun refreshPosition() {
        overlay.refreshPosition()
    }

    /** Shows a placeholder circle where the bubble would sit for [percent] and
     * [onLeft], so Settings can preview a position before it is applied. It
     * disappears on its own a moment after the last call. */
    fun previewPosition(percent: Int, onLeft: Boolean) {
        overlay.previewPosition(percent, onLeft)
    }

    private fun currentBubbleSizePercent(): Int = BubbleSize.preferencePercent(
        try {
            prefs().getInt(BubbleSize.PREFERENCE_KEY, BubbleSize.DEFAULT_PERCENT)
        } catch (_: ClassCastException) {
            null
        }
    )

    // --- Overlay state (all rendering lives in OverlayWindow) ---

    private fun setRecordingLook(recording: Boolean) = overlay.setRecordingLook(recording)
    private fun setIcon(res: Int) = overlay.setIcon(res)
    private fun setBusy(visible: Boolean) = overlay.setBusy(visible)
    private fun setOpacity(active: Boolean) = overlay.setOpacity(active)

    /** [ui] Voice-reactive recording: the latest buffer's peak drives the
     * button's size, and only while a recording is actually in progress. */
    private fun onAudioLevel(level: Float) {
        if (state != State.RECORDING) return
        overlay.applyLevel(level)
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.grant_audio_permission)); return
        }

        // [privacy] In local mode, don't start recording if there's no local
        // model to transcribe with -- tell the user before they speak.
        if (prefs().getBoolean("use_local", true) && localTranscriber == null) {
            toast(localUnavailableMessage()); return
        }

        if (!recorder.start()) {
            toast(getString(R.string.audio_permission_denied)); return
        }

        state = State.RECORDING
        setBusy(false)
        setRecordingLook(true) // [ui]
        setIcon(R.drawable.ic_mic)
        setOpacity(active = true)
        updateOverlayVisibility()
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        setRecordingLook(false) // [ui] white bars + the existing spinner ring
        setIcon(R.drawable.ic_mic)
        setBusy(true)
        updateOverlayVisibility()

        val pcm = recorder.stop()

        if (pcm.isEmpty() || VoiceRecorder.peakAmplitude(pcm) < SILENCE_PEAK) {
            reset(getString(R.string.no_audio_captured)); return
        }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        // [privacy] Local mode never falls back to the cloud: previously a
        // missing/unloaded model sent the recording to Groq without notice.
        // Now the audio is discarded and the user is told why.
        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else if (useLocal) {
            reset(localUnavailableMessage())
        } else {
            transcribeApi(pcm)
        }
    }

    /** Peak absolute 16-bit little-endian sample in [pcm]. */
    private fun peakAmplitude(pcm: ByteArray): Int = VoiceRecorder.peakAmplitude(pcm)

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        playRecordingStoppedTone()
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, VoiceRecorder.SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / VoiceRecorder.SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    toast(getString(R.string.local_transcription_error, e.message ?: getString(R.string.unknown_error)))
                    goIdle()
                }
            }
        }
    }

    private fun playRecordingStoppedTone() {
        try {
            if (stopTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) == false) {
                Log.w(TAG, "Recording stop tone did not start")
            }
        } catch (e: RuntimeException) {
            // Audio feedback must never prevent the captured speech from being transcribed.
            Log.w(TAG, "Recording stop tone unavailable", e)
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = SecureKeyStorage.groqApiKey(this)
        if (apiKey.isBlank()) { reset(getString(R.string.set_groq_key)); return }

        playRecordingStoppedTone()
        val model = TranscriberClient.modelId(prefs().getString(TranscriberClient.MODEL_PREF_KEY, null))
        TranscriberClient.transcribe(wav, apiKey, Dictionary.load(prefs()), TranscriptionLanguages.saved(prefs()), model) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast(getString(R.string.transcription_error, result.error ?: getString(R.string.empty_transcript)))
                    goIdle()
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast(getString(R.string.no_speech_detected))
                goIdle()
            }
            return
        }

        val voiceCommandsEnabled = prefs().getBoolean("voice_commands_enabled", false)
        if (voiceCommandsEnabled) {
            val trigger = prefs().getString("command_trigger_phrase", getString(R.string.default_trigger_phrase))
                ?: getString(R.string.default_trigger_phrase)
            val instruction = CommandProcessor.extractCommand(text, trigger)
            if (instruction != null) {
                handleVoiceCommand(instruction)
                return
            }
            val trailing = CommandProcessor.extractTrailingCommand(text, trigger)
            if (trailing != null) {
                handleVoiceCommand(trailing.instruction, trailing.content)
                return
            }
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val cleanupConfig = cleanupConfig()
        val apiKey = cleanupApiKey(cleanupConfig)

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast(getString(R.string.cleanup_needs_key))
                    injector.inject(text)
                    goIdle()
                }
                return
            }

            val customInstructions = prefs().getString("custom_instructions", "") ?: ""
            val vocabulary = Dictionary.load(prefs())
            val prompt = PostProcessor.effectivePrompt(customInstructions, vocabulary)

            PostProcessor.process(text, prompt, apiKey, cleanupConfig) { result ->
                handler.post {
                    val cleaned = result.text?.trim()
                    if (cleaned == "EMPTY") {
                        // Model correctly identified filler-only/no-speech audio;
                        // don't literally type the word "EMPTY" into the field.
                        toast(getString(R.string.no_speech_detected))
                    } else if (!cleaned.isNullOrBlank() && PostProcessor.looksGenerated(text, cleaned)) {
                        // [cleanup] The model wrote or rewrote content instead of
                        // cleaning (e.g. drafted the email you described). Never
                        // insert that; insert your own words instead.
                        Log.i(TAG, "Cleanup output rejected (not a cleanup of the transcript); using raw text")
                        if (injector.inject(text)) overlay.showFeedback(getString(R.string.cleanup_skipped_kept_words), 3000)
                    } else if (!cleaned.isNullOrBlank()) {
                        injector.inject(cleaned)
                    } else {
                        // [privacy] injectText only shows its feedback when it falls back
                        // to the clipboard; if the raw text was inserted, still say
                        // that cleanup failed.
                        if (injector.inject(text, feedbackText = getString(R.string.cleanup_failed_clipboard), feedbackDurationMs = 3000)) {
                            overlay.showFeedback(getString(R.string.cleanup_failed_inserted_raw), 3000)
                        }
                    }
                    goIdle()
                }
            }
        } else {
            handler.post {
                injector.inject(text)
                goIdle()
            }
        }
    }

    /** Handles a "Whisper Command" voice command: reads whatever's in the
     * focused field (if anything), sends it plus the spoken instruction to
     * CommandProcessor's whitelisted-transformation prompt, and replaces the
     * field's entire content with the result. With [dictated] (text spoken
     * before the trigger phrase) the command runs on that text instead, and
     * the result is inserted like normal dictation. */
    private fun handleVoiceCommand(instruction: String, dictated: String? = null) {
        // Commands run on the same service as cleanup, so a custom
        // OpenAI-compatible service runs them too, with its own key.
        val config = cleanupConfig()
        val apiKey = cleanupApiKey(config)
        if (apiKey.isBlank()) {
            handler.post {
                toast(getString(R.string.voice_commands_need_key))
                goIdle()
            }
            return
        }
        if (instruction.isBlank()) {
            handler.post {
                toast(getString(R.string.no_command_after_trigger))
                goIdle()
            }
            return
        }

        val fieldText = dictated ?: injector.currentFieldText()

        CommandProcessor.process(fieldText, instruction, apiKey, config) { result ->
            handler.post {
                val out = result.text?.trim()
                when {
                    out.isNullOrBlank() ->
                        toast(getString(R.string.command_failed, result.error ?: getString(R.string.empty_response)))
                    out == CommandProcessor.UNSUPPORTED ->
                        toast(getString(R.string.command_not_recognized))
                    dictated != null ->
                        if (injector.inject(out)) overlay.showFeedback(getString(R.string.command_applied), 2000)
                    else -> injector.replaceField(out)
                }
                goIdle()
            }
        }
    }

    /** Best-effort read of whatever text is already in the focused field,
     * for voice commands that operate on existing content ("summarize
     * this") rather than freshly dictated content. */
    private fun reset(msg: String) {
        toast(msg)
        goIdle()
    }

    private fun goIdle() {
        state = State.IDLE
        setBusy(false)
        setRecordingLook(false) // [ui]
        setIcon(R.drawable.ic_app_logo)
        setOpacity(active = false)
        updateOverlayVisibility()
    }

    private fun prefs() = getSharedPreferences("openwhispr", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }

    /** Where transcript cleanup and voice commands go. */
    private fun cleanupConfig() = CleanupProviderConfig.fromPreferences(
        prefs().getString("cleanup_provider", null),
        prefs().getString("cleanup_base_url", null),
        prefs().getString("cleanup_model", null),
        prefs().getString("cleanup_groq_model", null),
    )

    /** The key for whichever service [config] selects. */
    private fun cleanupApiKey(config: CleanupProviderConfig) =
        if (config.provider == CleanupProviderConfig.Provider.GROQ) {
            SecureKeyStorage.groqApiKey(this)
        } else {
            SecureKeyStorage.cleanupApiKey(this)
        }
}
