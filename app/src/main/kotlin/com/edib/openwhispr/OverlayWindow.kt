package com.edib.openwhispr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

/**
 * Everything the dictation bubble is and does on screen: the two overlay
 * windows (the bubble and the feedback note), their geometry and animation,
 * the placeholder circle Settings previews a position with, and the
 * voice-reactive scaling of the button.
 *
 * It deliberately knows nothing about recording, transcription, focus or
 * preferences -- the accessibility service owns that policy and passes what it
 * needs through the constructor callbacks. The overlay's own visibility
 * decision (when to fade in at all) also stays in the service; this class only
 * renders a state it is told to have.
 *
 * Contract kept from the service: the overlay must never take input away from
 * the user's field. The bubble window is created FLAG_NOT_TOUCHABLE and only
 * becomes touchable while it is actually shown, and every window update is
 * wrapped so a WindowManager refusal degrades to "no bubble" instead of
 * crashing the service.
 */
internal class OverlayWindow(
    private val context: Context,
    private val handler: Handler,
    /** The bubble was tapped: start or stop dictation. */
    private val onTap: () -> Unit,
    /** The keyboard's own window appeared or disappeared (fed by the bubble's
     * WindowInsets listener, so it works even for apps that never report focus
     * through the accessibility tree). */
    private val onImeVisibilityChanged: (Boolean) -> Unit,
    /** The user's bubble size preference, already validated. */
    private val bubbleSizePercent: () -> Int,
    /** Where the bubble sits when nothing is dragging it, from the saved
     * position preference. */
    private val homePosition: (ringSize: Int) -> BubbleSize.Position,
    /** True while dictation is idle: the bubble is dimmed then, full opacity
     * while recording or transcribing. */
    private val isIdle: () -> Boolean,
) {
    private companion object {
        const val TAG = "OpenWhispr"

        const val ALPHA_IDLE = 0.7f
        const val ALPHA_ACTIVE = 1.0f
        const val ALPHA_FADE_MS = 150L
        const val FADE_OUT_MS = 140L

        // Placeholder circle shown by Settings while choosing the position.
        const val PREVIEW_FILL = 0x552F80ED
        const val PREVIEW_STROKE = 0xFF2F80ED.toInt()
        const val PREVIEW_HIDE_MS = 1500L

        const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        // [ui] Soft red for the recording state (logo tint + outline);
        // replaces the old solid red / grey button colours.
        const val COLOR_REC_ACCENT = 0xFFFF6B6B.toInt()
        const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private class BubbleOverlay(context: Context) : FrameLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp get() = context.resources.displayMetrics.density
    private val screenW get() = context.resources.displayMetrics.widthPixels
    private val screenH get() = context.resources.displayMetrics.heightPixels

    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var previewView: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    // [ui] Smoothed recording level for the voice-reactive overlay (main thread).
    private var audioLevel = 0f

    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }
    private val hidePositionPreview = Runnable { removePositionPreview() }

    /** Whether the bubble window is currently visible on screen. Lets the
     * service self-heal when the view has drifted from its own idea of it. */
    val isVisible: Boolean get() = overlayView?.visibility == View.VISIBLE

    /** Builds and adds both windows. Called once, when the service connects. */
    fun show() {
        val wm = windowManager
        val dimensions = BubbleSize.dimensions(bubbleSizePercent())
        val buttonSize = (dimensions.buttonDp * dp).toInt()
        val ringSize = (dimensions.ringDp * dp).toInt()
        val pad = (dimensions.paddingDp * dp).toInt()
        val margin = (BubbleSize.MARGIN_DP * dp).toInt()

        val ring = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(context).apply {
            setImageResource(R.drawable.ic_app_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = BubbleOverlay(context).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            contentDescription = context.getString(R.string.overlay_content_description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            setOnClickListener { onTap() }
            alpha = 0f
            visibility = View.INVISIBLE
            setOnApplyWindowInsetsListener { _, insets ->
                try {
                    onImeVisibilityChanged(insets.isVisible(WindowInsets.Type.ime()))
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
            val home = homePosition(ringSize)
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
                    if (moved < BubbleSize.dimensions(bubbleSizePercent()).tapThresholdDp * dp) {
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

        val feedback = TextView(context).apply {
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

    /** Removes both windows. Called when the service is destroyed. */
    fun remove() {
        val wm = windowManager
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

    // --- Visibility animation ---

    /** Shows the overlay at its target alpha immediately. No fade-in: an
     * animation on a window the system hasn't drawn yet can stall at alpha 0. */
    fun animateIn() {
        handler.post {
            val view = overlayView ?: return@post
            view.animate().cancel()
            view.visibility = View.VISIBLE
            setTouchable(true)
            view.alpha = if (isIdle()) ALPHA_IDLE else ALPHA_ACTIVE
        }
    }

    fun animateOut() {
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

    /** The bubble is only touchable while it's actually shown, so a hidden
     * bubble can never swallow a tap meant for the field underneath. */
    private fun setTouchable(touchable: Boolean) {
        try {
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
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "setTouchable failed", e)
        }
    }

    // --- Geometry ---

    /** Re-lays out the bubble (and the feedback note beside it) after the size
     * setting changed, keeping it on screen. */
    fun refreshBubbleSize() {
        handler.post {
            try {
                val view = overlayView ?: return@post
                val lp = layoutParams ?: return@post
                val wm = windowManager
                val dimensions = BubbleSize.dimensions(bubbleSizePercent())
                val ringSize = (dimensions.ringDp * dp).toInt()
                val margin = (BubbleSize.MARGIN_DP * dp).toInt()
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

    /** Moves the bubble to its saved home position and drops the preview. */
    fun refreshPosition() {
        handler.post {
            try {
                val view = overlayView ?: return@post
                val lp = layoutParams ?: return@post
                val wm = windowManager
                val position = homePosition(lp.width)
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

    /** Shows a placeholder circle where the bubble would sit for [percent] and
     * [onLeft], so Settings can preview a position before it is applied. It
     * disappears on its own a moment after the last call. */
    fun previewPosition(percent: Int, onLeft: Boolean) {
        handler.post {
            try {
                val wm = windowManager
                val size = layoutParams?.width
                    ?: (BubbleSize.dimensions(bubbleSizePercent()).ringDp * dp).toInt()
                val position = BubbleSize.positionFor(
                    percent, onLeft, screenW, screenH, size, (BubbleSize.MARGIN_DP * dp).toInt()
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
                    val circle = View(context).apply {
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

    fun removePositionPreview() {
        handler.removeCallbacks(hidePositionPreview)
        val view = previewView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "removePositionPreview failed", e)
        }
        previewView = null
        previewParams = null
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (BubbleSize.MARGIN_DP * dp).toInt()
        val offset = (BubbleSize.dimensions(bubbleSizePercent()).feedbackOffsetDp * dp).toInt()
        // Bubble on the left half: the note goes to its right instead of off-screen.
        val onLeftHalf = bubbleParams.x + bubbleParams.width / 2 < screenW / 2
        feedbackParams.x = if (onLeftHalf) bubbleParams.x + bubbleParams.width + margin
            else maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    // --- State looks ---

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
     * outline (and [applyLevel] makes the button follow your voice);
     * otherwise the original hairline. The icon itself is set by [setIcon]. */
    fun setRecordingLook(recording: Boolean) {
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
    fun setIcon(res: Int) {
        handler.post { button?.setImageResource(res) }
    }

    fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun setOpacity(active: Boolean) {
        handler.post {
            overlayView?.animate()?.cancel()
            overlayView?.animate()
                ?.alpha(if (active) ALPHA_ACTIVE else ALPHA_IDLE)
                ?.setDuration(ALPHA_FADE_MS)
                ?.start()
        }
    }

    /** Shows [text] in the note beside the bubble for [durationMs]. */
    fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = windowManager

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
     * buffer's peak) drives the button's size through [VoiceLevel], which
     * rises at once and falls slowly, so the bubble swells as you speak and
     * settles when you're quiet. Runs on the main thread; the service only
     * calls it while a recording is in progress. */
    fun applyLevel(level: Float) {
        audioLevel = VoiceLevel.smoothed(level, audioLevel)
        val scale = VoiceLevel.scale(audioLevel)
        button?.scaleX = scale
        button?.scaleY = scale
    }
}
