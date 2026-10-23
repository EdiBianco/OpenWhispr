package com.edib.openwhispr

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * The small pieces every screen is built from, as extensions on Context so the
 * activity, its dialogs and anything else that builds UI share one definition
 * of a row, a panel and a status dot.
 *
 * [prefs] is here for a different reason: it is the single place the
 * preferences file is named, so a new reader can find the storage layer's name
 * without grepping for it.
 */

internal const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Status dot colours: green for a satisfied step, red for one still missing. */
internal const val DOT_GREEN = 0xFF34C759.toInt()
internal const val DOT_RED = 0xFFEF4444.toInt()

/** The one preferences file. Everything else reads it through this. */
internal fun Context.prefs(): SharedPreferences =
    getSharedPreferences("openwhispr", Context.MODE_PRIVATE)

internal fun Context.toast(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

internal fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

internal fun Context.hasPerm(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** A colour from the current theme, so light and dark both read correctly. */
internal fun Context.attrColor(attr: Int): Int {
    val ta = obtainStyledAttributes(intArrayOf(attr))
    val color = ta.getColor(0, 0)
    ta.recycle()
    return color
}

internal fun Context.vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(padH, padV, padH, padV)
}

/** [ui] A section panel: rounded, in the theme's surface-container
 * colour (light/dark aware), clipping row ripples to its corners. */
internal fun Context.group() = vertical(0).apply {
    background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainer))
    }
    clipToOutline = true
    layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
        setMargins(dp(16), 0, dp(16), dp(8))
    }
}

internal fun Context.sectionHeader(title: String) = TextView(this).apply {
    text = title
    textSize = 14f
    setTypeface(typeface, android.graphics.Typeface.BOLD)
    setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary)) // Neutral Android-like blue
    // [ui] 40dp start = group() margin (16) + row padding (24), so the
    // header lines up with the row text inside the panel below it.
    setPadding(dp(40), dp(24), dp(24), dp(8))
}

/** A settings row: title, subtitle, and optionally a control on the right and
 * a leading view. The subtitle carries the "subtitle" tag, which is how the
 * status screen finds it again to update the text. */
internal fun Context.settingsRow(
    title: String,
    subtitle: String,
    widget: View? = null,
    leading: View? = null,
    onClick: (() -> Unit)? = null,
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
        layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
            // [ui] Keep wrapped text clear of the switch/button beside it.
            if (widget != null) marginEnd = dp(16)
        }
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

internal fun Context.scrollableDialogContent(content: View) = ScrollView(this).apply {
    isFillViewport = true
    setPadding(dp(24), dp(8), dp(24), dp(8))
    addView(content, FrameLayout.LayoutParams(LP_MATCH, LP_WRAP))
}

/** Shows a dialog sized for a soft keyboard, so a field being typed into is
 * never covered by it. */
internal fun showResizingDialog(dialog: AlertDialog) {
    dialog.show()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}

internal fun Context.dotDrawable(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

internal fun Context.statusDot(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
        marginEnd = dp(12)
    }
    background = dotDrawable(DOT_RED)
}
