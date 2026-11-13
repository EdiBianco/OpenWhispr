package com.edib.openwhispr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    const val TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val MODELS_URL = "https://api.groq.com/openai/v1/models"

    /** Preference holding the chosen cloud model id; unset means [DEFAULT_MODEL]. */
    const val MODEL_PREF_KEY = "cloud_transcription_model"

    const val DEFAULT_MODEL = "whisper-large-v3"

    /** Whether the user turned on Custom model (the list from Groq); off uses the default. */
    const val CUSTOM_MODEL_PREF_KEY = "cloud_custom_model_enabled"

    /** Kept as the default model constant for callers that don't want a choice. */
    const val TRANSCRIPTION_MODEL = DEFAULT_MODEL

    /** The saved id, or [DEFAULT_MODEL] when nothing usable is saved. Any id is accepted:
     * the picker offers the account's Whisper models, and a custom id can be typed. */
    fun modelId(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    /** The model to send: the default unless custom is on, then the saved id. */
    fun activeModel(customEnabled: Boolean, saved: String?): String =
        if (customEnabled) modelId(saved) else DEFAULT_MODEL

    /** Whisper model ids from a Groq `/models` response body, in server order. */
    fun parseModelIds(json: String): List<String> = try {
        val data = JSONObject(json).getJSONArray("data")
        (0 until data.length())
            .map { data.getJSONObject(it).getString("id") }
            .filter { "whisper" in it }
    } catch (e: Exception) {
        emptyList()
    }

    /** Fetches the Whisper models this key can use. The callback gets the ids (empty on
     * failure) and an error message, or null on success. */
    fun listModels(apiKey: String, callback: (ids: List<String>, error: String?) -> Unit) {
        val request = Request.Builder()
            .url(MODELS_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(emptyList(), e.message)
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) callback(emptyList(), "HTTP ${response.code}")
                else callback(parseModelIds(body), null)
            }
        })
    }

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    const val MAX_DICTIONARY_PROMPT_BYTES = 128
    private const val DICTIONARY_PROMPT_PREFIX = "Dictionary hints: "

    fun dictionaryPrompt(vocabulary: List<String>): String {
        val selected = mutableListOf<String>()
        var prompt = DICTIONARY_PROMPT_PREFIX
        for (entry in Dictionary.parseEntries(vocabulary.joinToString("\n"))) {
            val canonical = entry.canonical.trim().split(Regex("\\s+")).joinToString(" ")
            val aliases = entry.aliases.map { it.trim().split(Regex("\\s+")).joinToString(" ") }
            val hint = if (aliases.isEmpty()) canonical else "$canonical (pronounced as ${aliases.joinToString(", ")})"
            if (hint.isEmpty()) continue
            val candidate = DICTIONARY_PROMPT_PREFIX + (selected + hint).joinToString("; ")
            if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_DICTIONARY_PROMPT_BYTES) {
                selected += hint
                prompt = candidate
            }
        }
        return if (selected.isEmpty()) "" else prompt
    }

    /** Whisper takes one language per request, so one selected language is
     * forced; several are only described in the prompt; none means auto-detect. */
    internal fun languageHint(languages: Set<String>): String? {
        if (languages.size < 2) return null
        val names = TranscriptionLanguages.ordered(languages)
            .filter { it in languages }
            .map { TranscriptionLanguages.displayName(it, java.util.Locale.ENGLISH) }
        return "The speaker uses these languages: ${names.joinToString(", ")}."
    }

    internal fun transcriptionRequest(
        wavData: ByteArray,
        apiKey: String,
        vocabulary: List<String>,
        languages: Set<String> = emptySet(),
        model: String = DEFAULT_MODEL,
    ): Request {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
        languages.singleOrNull()?.let { multipart.addFormDataPart("language", it) }
        listOfNotNull(languageHint(languages), dictionaryPrompt(vocabulary).takeIf(String::isNotEmpty))
            .takeIf { it.isNotEmpty() }
            ?.let { multipart.addFormDataPart("prompt", it.joinToString(" ")) }
        return Request.Builder()
            .url(TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(multipart.build())
            .build()
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        vocabulary: List<String> = emptyList(),
        languages: Set<String> = emptySet(),
        model: String = DEFAULT_MODEL,
        callback: (Result) -> Unit,
    ) {
        client.newCall(transcriptionRequest(wavData, apiKey, vocabulary, languages, model)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }
}
