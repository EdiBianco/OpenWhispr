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
    const val TRANSCRIPTION_MODEL = "whisper-large-v3"

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

    internal fun transcriptionRequest(wavData: ByteArray, apiKey: String, vocabulary: List<String>): Request {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", TRANSCRIPTION_MODEL)
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
        dictionaryPrompt(vocabulary).takeIf(String::isNotEmpty)?.let { multipart.addFormDataPart("prompt", it) }
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
        callback: (Result) -> Unit,
    ) {
        client.newCall(transcriptionRequest(wavData, apiKey, vocabulary)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }
}
