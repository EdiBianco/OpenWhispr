package com.edib.openwhispr

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "OpenWhispr"
        private const val SAMPLE_RATE = 16000
        private const val MARGIN_DP = 8

        private const val ALPHA_IDLE = 0.7f
        private const val ALPHA_ACTIVE = 1.0f
        private const val ALPHA_FADE_MS = 150L
        private const val FADE_OUT_MS = 140L

        // How often we re-check the focused node as a failsafe, in case an
        // app never fires a focus-related accessibility event at all.
        private const val FOCUS_POLL_MS = 250L

        // A focus/keyboard change must hold this long before the overlay shows or hides.
        private const val OVERLAY_DEBOUNCE_MS = 100L

        // Placeholder circle shown by Settings while choosing the bubble position.
        private const val PREVIEW_FILL = 0x552F80ED
        private const val PREVIEW_STROKE = 0xFF2F80ED.toInt()
        private const val PREVIEW_HIDE_MS = 1500L

        // Peak 16-bit amplitude below which a recording is treated as silence.
        // Whisper invents text such as "Thank you." for silent audio.
        private const val SILENCE_PEAK = 500

        private const val NOTIF_CHANNEL_ID = "openwhispr_service"
        private const val NOTIF_ID = 1

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        // [ui] Soft red for the recording state (logo tint + outline);
        // replaces the old solid red / grey button colours.
        private const val COLOR_REC_ACCENT = 0xFFFF6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private class BubbleOverlay(context: Context) : FrameLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var overlayShown = false

    // Two independent signals feed overlay visibility (OR'd together): an
    // accessibility-tree focus check (event-driven AND polled as a failsafe,
    // since some apps -- notably WhatsApp/Telegram -- don't reliably fire
    // focus events for their custom message composers) and the system
    // keyboard's own visibility (window-manager-level, doesn't depend on the
    // foreground app cooperating with accessibility at all).
    private var accessibilityFocusSignal = false
    private var imeVisibleSignal = false
    private var button: ImageView? = null
    // [ui] Smoothed recording level for the voice-reactive overlay (main thread).
    private var audioLevel = 0f
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var stopTone: ToneGenerator? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }
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
        showOverlay()
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
        removePositionPreview()
        stopTone?.release()
        stopTone = null
        handler.removeCallbacks(overlayVisibilityDebounce)
        handler.removeCallbacks(focusPoller)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
        removeOverlay()
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
            if (shouldShow && overlayView?.visibility != View.VISIBLE) animateOverlayIn()
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
        if (shouldShow) animateOverlayIn() else animateOverlayOut()
    }

    private fun masterEnabled() = prefs().getBoolean("service_master_enabled", true)

    /** Called from MainActivity when the "Background service" switch is
     * toggled, so an already-idle overlay hides/shows immediately instead
     * of waiting for the next focus event or poll tick. */
    fun refreshMasterEnabled() {
        handler.post { updateOverlayVisibility() }
    }

    /** Shows the overlay at its target alpha immediately. No fade-in: an
     * animation on a window the system hasn't drawn yet can stall at alpha 0. */
    private fun animateOverlayIn() {
        handler.post {
            val view = overlayView ?: return@post
            view.animate().cancel()
            view.visibility = View.VISIBLE
            setTouchable(true)
            view.alpha = if (state == State.IDLE) ALPHA_IDLE else ALPHA_ACTIVE
        }
    }

    private fun animateOverlayOut() {
        handler.post {
            val view = overlayView ?: return@post
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    view.visibility = View.INVISIBLE
                    setTouchable(false)
                }
                .start()
        }
    }

    private fun setTouchable(touchable: Boolean) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val lp = layoutParams ?: return
            val view = overlayView ?: return
            val hadFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            val wantFlag = !touchable
            if (hadFlag == wantFlag) return
            lp.flags = if (wantFlag) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "setTouchable failed", e)
        }
    }

    fun refreshBubbleSize() {
        handler.post {
            try {
                val view = overlayView ?: return@post
                val lp = layoutParams ?: return@post
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dimensions = BubbleSize.dimensions(currentBubbleSizePercent())
                val ringSize = (dimensions.ringDp * dp).toInt()
                val margin = (MARGIN_DP * dp).toInt()
                val position = BubbleSize.clampPosition(
                    lp.x, lp.y, screenW, screenH, ringSize, margin
                )

                lp.width = ringSize
                lp.height = ringSize
                lp.x = position.x
                lp.y = position.y
                (spinner?.layoutParams as? FrameLayout.LayoutParams)?.let { ringParams ->
                    ringParams.width = ringSize
                    ringParams.height = ringSize
                    spinner?.layoutParams = ringParams
                }
                (button?.layoutParams as? FrameLayout.LayoutParams)?.let { buttonParams ->
                    val buttonSize = (dimensions.buttonDp * dp).toInt()
                    buttonParams.width = buttonSize
                    buttonParams.height = buttonSize
                    button?.layoutParams = buttonParams
                }
                button?.setPadding(
                    (dimensions.paddingDp * dp).toInt(),
                    (dimensions.paddingDp * dp).toInt(),
                    (dimensions.paddingDp * dp).toInt(),
                    (dimensions.paddingDp * dp).toInt()
                )
                wm.updateViewLayout(view, lp)

                feedbackLayoutParams?.let { feedbackParams ->
                    positionFeedback(feedbackParams, lp)
                    feedbackView?.let { wm.updateViewLayout(it, feedbackParams) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshBubbleSize failed", e)
            }
        }
    }

    /** The bubble's home position from the Settings slider and side switch. */
    private fun savedHomePosition(ringSize: Int): BubbleSize.Position = BubbleSize.positionFor(
        prefs().getInt(BubbleSize.POSITION_PERCENT_KEY, BubbleSize.DEFAULT_POSITION_PERCENT),
        prefs().getBoolean(BubbleSize.ON_LEFT_KEY, false),
        screenW, screenH, ringSize, (MARGIN_DP * dp).toInt()
    )

    /** Moves the bubble to its saved home position and drops the preview. */
    fun refreshPosition() {
        handler.post {
            try {
                val view = overlayView ?: return@post
                val lp = layoutParams ?: return@post
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val position = savedHomePosition(lp.width)
                lp.x = position.x
                lp.y = position.y
                wm.updateViewLayout(view, lp)
                feedbackLayoutParams?.let { feedbackParams ->
                    positionFeedback(feedbackParams, lp)
                    feedbackView?.let { wm.updateViewLayout(it, feedbackParams) }
                }
                removePositionPreview()
            } catch (e: Exception) {
                Log.e(TAG, "refreshPosition failed", e)
            }
        }
    }

    private var previewView: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private val hidePositionPreview = Runnable { removePositionPreview() }

    /** Shows a placeholder circle where the bubble would sit for [percent] and
     * [onLeft], so Settings can preview a position before it is applied. It
     * disappears on its own a moment after the last call. */
    fun previewPosition(percent: Int, onLeft: Boolean) {
        handler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val size = layoutParams?.width
                    ?: (BubbleSize.dimensions(currentBubbleSizePercent()).ringDp * dp).toInt()
                val position = BubbleSize.positionFor(
                    percent, onLeft, screenW, screenH, size, (MARGIN_DP * dp).toInt()
                )
                val existing = previewView
                val params = previewParams ?: WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                params.width = size
                params.height = size
                params.x = position.x
                params.y = position.y
                if (existing == null) {
                    val circle = View(this).apply {
                        background = circle(PREVIEW_FILL, PREVIEW_STROKE, (2 * dp).toInt())
                    }
                    wm.addView(circle, params)
                    previewView = circle
                    previewParams = params
                } else {
                    wm.updateViewLayout(existing, params)
                }
                handler.removeCallbacks(hidePositionPreview)
                handler.postDelayed(hidePositionPreview, PREVIEW_HIDE_MS)
            } catch (e: Exception) {
                Log.e(TAG, "previewPosition failed", e)
            }
        }
    }

    private fun removePositionPreview() {
        handler.removeCallbacks(hidePositionPreview)
        val view = previewView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "removePositionPreview failed", e)
        }
        previewView = null
        previewParams = null
    }

    private fun currentBubbleSizePercent(): Int = BubbleSize.preferencePercent(
        try {
            prefs().getInt(BubbleSize.PREFERENCE_KEY, BubbleSize.DEFAULT_PERCENT)
        } catch (_: ClassCastException) {
            null
        }
    )

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dimensions = BubbleSize.dimensions(currentBubbleSizePercent())
        val buttonSize = (dimensions.buttonDp * dp).toInt()
        val ringSize = (dimensions.ringDp * dp).toInt()
        val pad = (dimensions.paddingDp * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = BubbleOverlay(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            contentDescription = getString(R.string.overlay_content_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            setOnClickListener { onTap() }
            alpha = 0f
            visibility = View.INVISIBLE
            setOnApplyWindowInsetsListener { _, insets ->
                try {
                    onKeyboardVisibilityChanged(insets.isVisible(WindowInsets.Type.ime()))
                } catch (e: Exception) {
                    Log.e(TAG, "IME insets check failed", e)
                }
                insets
            }
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val home = savedHomePosition(ringSize)
            x = home.x
            y = home.y
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val position = BubbleSize.clampPosition(
                        startX + (ev.rawX - touchX).toInt(),
                        startY + (ev.rawY - touchY).toInt(),
                        screenW,
                        screenH,
                        params.width,
                        margin
                    )
                    params.x = position.x
                    params.y = position.y
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < BubbleSize.dimensions(currentBubbleSizePercent()).tapThresholdDp * dp) {
                        v.performClick()
                    } else {
                        params.x = BubbleSize.snappedX(params.x, params.width, screenW, margin)
                        params.y = BubbleSize.clampPosition(
                            params.x, params.y, screenW, screenH, params.width, margin
                        ).y
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    // [ui] Outline colour/width are parameters now (recording uses a thin
    // soft-red outline); the defaults keep the original white hairline.
    private fun circle(color: Int, stroke: Int = Color.WHITE, strokePx: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        // 1 physical pixel, not 1dp -- a true hairline outline so the button
        // stays visible against any surface behind it, in every state.
        setStroke(strokePx, stroke)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    /** [ui] Recording tints the button's icon soft red with a thin red
     * outline (and onAudioLevel makes the button follow your voice);
     * otherwise the original hairline. The icon itself is set by setIcon. */
    private fun setRecordingLook(recording: Boolean) {
        handler.post {
            val b = button ?: return@post
            b.background = if (recording) circle(COLOR_IDLE, COLOR_REC_ACCENT, (1.5f * dp).toInt())
                           else circle(COLOR_IDLE)
            b.imageTintList = if (recording) ColorStateList.valueOf(COLOR_REC_ACCENT) else null
            audioLevel = 0f
            b.animate().cancel()
            b.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }

    /** Swaps the overlay's icon: the app logo while idle, the mic glyph
     * while recording/transcribing. */
    private fun setIcon(res: Int) {
        handler.post { button?.setImageResource(res) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun setOpacity(active: Boolean) {
        handler.post {
            overlayView?.animate()?.cancel()
            overlayView?.animate()
                ?.alpha(if (active) ALPHA_ACTIVE else ALPHA_IDLE)
                ?.setDuration(ALPHA_FADE_MS)
                ?.start()
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (BubbleSize.dimensions(currentBubbleSizePercent()).feedbackOffsetDp * dp).toInt()
        // Bubble on the left half: the note goes to its right instead of off-screen.
        val onLeftHalf = bubbleParams.x + bubbleParams.width / 2 < screenW / 2
        feedbackParams.x = if (onLeftHalf) bubbleParams.x + bubbleParams.width + margin
            else maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    /** [ui] Voice-reactive recording: [level] (0..1, the latest audio
     * buffer's peak) drives the button's size. VU-style smoothing -- rises
     * at once, falls slowly -- so it swells as you speak and settles when
     * you're quiet. Max 1.15x fits inside the overlay window (56dp around a
     * 44dp button). Runs on the main thread. */
    private fun onAudioLevel(level: Float) {
        if (state != State.RECORDING) return
        audioLevel = maxOf(level, audioLevel * 0.85f)
        val scale = 1f + 0.15f * audioLevel
        button?.scaleX = scale
        button?.scaleY = scale
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

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast(getString(R.string.audio_permission_denied)); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        state = State.RECORDING
        setBusy(false)
        setRecordingLook(true) // [ui]
        setIcon(R.drawable.ic_mic)
        setOpacity(active = true)
        updateOverlayVisibility()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    // [ui] Peak of this buffer (16-bit little-endian PCM),
                    // only read for the overlay's voice-reactive size.
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        peak = maxOf(peak, abs(sample))
                        i += 2
                    }
                    val level = (peak / 12000f).coerceIn(0f, 1f)
                    handler.post { onAudioLevel(level) }
                }
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        setRecordingLook(false) // [ui] white bars + the existing spinner ring
        setIcon(R.drawable.ic_mic)
        setBusy(true)
        updateOverlayVisibility()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty() || peakAmplitude(pcm) < SILENCE_PEAK) { reset(getString(R.string.no_audio_captured)); return }

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
    private fun peakAmplitude(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        return peak
    }

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
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

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
        TranscriberClient.transcribe(wav, apiKey, Dictionary.load(prefs()), TranscriptionLanguages.saved(prefs())) { result ->
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
        val cleanupConfig = CleanupProviderConfig.fromPreferences(
            prefs().getString("cleanup_provider", null),
            prefs().getString("cleanup_base_url", null),
            prefs().getString("cleanup_model", null),
        )
        val apiKey = if (cleanupConfig.provider == CleanupProviderConfig.Provider.GROQ)
            SecureKeyStorage.groqApiKey(this) else SecureKeyStorage.cleanupApiKey(this)

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast(getString(R.string.cleanup_needs_key))
                    injectText(text)
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
                        if (injectText(text)) showFeedback(getString(R.string.cleanup_skipped_kept_words), 3000)
                    } else if (!cleaned.isNullOrBlank()) {
                        injectText(cleaned)
                    } else {
                        // [privacy] injectText only shows its feedback when it falls back
                        // to the clipboard; if the raw text was inserted, still say
                        // that cleanup failed.
                        if (injectText(text, feedback = getString(R.string.cleanup_failed_clipboard), feedbackDurationMs = 3000)) {
                            showFeedback(getString(R.string.cleanup_failed_inserted_raw), 3000)
                        }
                    }
                    goIdle()
                }
            }
        } else {
            handler.post {
                injectText(text)
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
        val apiKey = SecureKeyStorage.groqApiKey(this)
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

        val fieldText = dictated ?: currentFieldText()

        CommandProcessor.process(fieldText, instruction, apiKey) { result ->
            handler.post {
                val out = result.text?.trim()
                when {
                    out.isNullOrBlank() ->
                        toast(getString(R.string.command_failed, result.error ?: getString(R.string.empty_response)))
                    out == CommandProcessor.UNSUPPORTED ->
                        toast(getString(R.string.command_not_recognized))
                    dictated != null ->
                        if (injectText(out)) showFeedback(getString(R.string.command_applied), 2000)
                    else -> replaceFieldText(out)
                }
                goIdle()
            }
        }
    }

    /** Best-effort read of whatever text is already in the focused field,
     * for voice commands that operate on existing content ("summarize
     * this") rather than freshly dictated content. */
    private fun currentFieldText(): String {
        val candidates = findInjectionCandidates()
        return try {
            candidates.firstOrNull()?.text?.toString().orEmpty()
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    /** Like injectText, but replaces the focused field's entire content
     * instead of inserting at the cursor/selection -- used by voice
     * commands, which transform the whole field rather than append to it. */
    private fun replaceFieldText(text: String) {
        val candidates = findInjectionCandidates()
        var replaced = false
        try {
            for (candidate in candidates) {
                if (tryReplaceEntireNode(candidate, text)) {
                    replaced = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        // [privacy] Replacement uses ACTION_SET_TEXT (no clipboard), so the
        // clipboard is only used as the fallback when it fails.
        if (!replaced) copyToClipboard(text)
        Log.i(TAG, if (replaced) "Command replace succeeded" else "Command replace failed; clipboard fallback only")
        showFeedback(
            getString(if (replaced) R.string.command_applied else R.string.replace_failed_clipboard),
            if (replaced) 2000 else 3000
        )
    }

    private fun tryReplaceEntireNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying full replace on node", node)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "Full-replace ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }
        return false
    }

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

    // --- Text injection ---

    /** Inserts [text] into the focused field. Returns true if it landed.
     *
     * [privacy] Insertion pastes, so the text goes on the clipboard first
     * (marked sensitive, see copyToClipboard); if nothing accepts it, it
     * stays there for the user to paste. [feedback] is shown only in that
     * case -- previously it was shown before insertion, even on success. */
    private fun injectText(
        text: String,
        feedback: String? = getString(R.string.copied_to_clipboard),
        feedbackDurationMs: Long = 2000
    ): Boolean {
        copyToClipboard(text)

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
        if (!injected) feedback?.let { showFeedback(it, feedbackDurationMs) }
        return injected
    }

    /** [privacy] Puts dictated text on the clipboard (needed: paste-based
     * injection and the manual fallback read it from there), marked as
     * sensitive. Android 13+ then hides it in the clipboard preview, and
     * keyboards that honour the flag keep it out of clipboard suggestions.
     * Pasting is unaffected. The extra's key is the literal value of
     * ClipDescription.EXTRA_IS_SENSITIVE (API 33), so this compiles and runs
     * on minSdk 30, where it is simply ignored. */
    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("openwhispr", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        // [privacy] Never log a field's contents or accessibility label (it can
        // be the user's message, email, etc.) -- only its length/presence.
        // Logcat is readable over adb and is bundled into bug reports, and the
        // app can't control how long it's kept.
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} textLen=${node.text?.length ?: 0} hasDesc=${node.contentDescription != null} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("openwhispr", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
