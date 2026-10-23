package com.edib.openwhispr

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts text into whatever text field the user has focused, across apps.
 *
 * The accessibility node tree is the only way to do this, so this is the one
 * place in the app that touches node text. Node contents are never logged
 * (see [logNode]) because logcat is readable over adb and ends up in bug
 * reports.
 *
 * Both directions fall back to the clipboard: insertion pastes, so the text
 * goes on the clipboard first, and a failed full-field replacement leaves the
 * text there for the user to paste by hand.
 */
internal class TextInjector(
    private val service: AccessibilityService,
    private val feedback: (String, Long) -> Unit,
) {

    /** Whatever is in the focused field right now, or "" if there is none. */
    fun currentFieldText(): String {
        val candidates = findInjectionCandidates()
        return try {
            candidates.firstOrNull()?.text?.toString().orEmpty()
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    /** Like [inject], but replaces the focused field's entire content instead
     * of inserting at the cursor/selection -- used by voice commands, which
     * transform the whole field rather than append to it. */
    fun replaceField(text: String) {
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
        feedback(
            service.getString(if (replaced) R.string.command_applied else R.string.replace_failed_clipboard),
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

    /** Inserts [text] into the focused field. Returns true if it landed.
     *
     * [privacy] Insertion pastes, so the text goes on the clipboard first
     * (marked sensitive, see [copyToClipboard]); if nothing accepts it, it
     * stays there for the user to paste. [feedbackText] is shown only in that
     * case -- previously it was shown before insertion, even on success. */
    fun inject(
        text: String,
        feedbackText: String? = service.getString(R.string.copied_to_clipboard),
        feedbackDurationMs: Long = 2000,
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
        if (!injected) feedbackText?.let { feedback(it, feedbackDurationMs) }
        return injected
    }

    /** [privacy] Puts dictated text on the clipboard (needed: paste-based
     * injection and the manual fallback read it from there), marked as
     * sensitive. Android 13+ then hides it in the clipboard preview, and
     * keyboards that honour the flag keep it out of clipboard suggestions.
     * Pasting is unaffected. The extra's key is the literal value of
     * ClipDescription.EXTRA_IS_SENSITIVE (API 33), so this compiles and runs
     * on minSdk 30, where it is simply ignored. */
    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("openwhispr", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        service.rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        service.windows
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

    /** Ranks candidates so the field the user means wins: a node that exposes
     * its own paste action first, then terminal surfaces, then editable and
     * focused nodes, then anything that merely looks like an EditText. */
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
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} textLen=${node.text?.length ?: 0} hasDesc=${node.contentDescription != null} actions=[$actions]"
        )
    }

    private companion object {
        const val TAG = "OpenWhispr"
    }
}
