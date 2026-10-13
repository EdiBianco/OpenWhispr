package com.edib.openwhispr

import android.content.SharedPreferences
import java.util.Locale

/** Languages Whisper can transcribe, as ISO 639-1 codes (plus "yue" for Cantonese). */
object TranscriptionLanguages {
    const val PREF_KEY = "transcription_languages"

    val CODES: List<String> = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it",
        "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur",
        "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn",
        "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si",
        "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
        "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha",
        "ba", "jw", "su", "yue",
    )

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(locale).ifBlank { code }

    /** Saved selection; empty means Auto-detect. Unknown codes are dropped. */
    fun saved(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(PREF_KEY, emptySet()).orEmpty().filter { it in CODES }.toSet()

    /** Selected languages first, then the rest; each group sorted by name. */
    fun ordered(selected: Set<String>): List<String> =
        CODES.sortedWith(compareBy<String> { it !in selected }.thenBy { displayName(it) })
}
