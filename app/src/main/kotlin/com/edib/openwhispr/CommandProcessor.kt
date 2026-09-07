package com.edib.openwhispr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Handles "Whisper Command" voice commands: a fixed whitelist of
 * transformations (summarize, enhance flow, translate, change tone, turn
 * into a list) applied either to text dictated in the same breath as the
 * command, or to whatever's already in the focused field. Deliberately
 * separate from PostProcessor's literal-cleanup contract -- this mode
 * exists specifically to act on instructions, but only these five kinds. */
object CommandProcessor {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    const val UNSUPPORTED = "UNSUPPORTED_COMMAND"

    const val COMMAND_PROMPT = """You are a voice-command text editor triggered by the spoken phrase "Whisper Command". You receive CURRENT_TEXT (whatever is already in the user's text field, may be empty) and an INSTRUCTION spoken right after the trigger phrase.
Hard contract:
- Return only the final transformed text.
- No explanations, no markdown, no quotes around the result, no restating the instruction.
- The only operations you may perform are these five:
  - Summarize: condense the text to the requested length or limit (e.g. "in two sentences", "in 50 words"); if no limit is given, summarize concisely.
  - Enhance flow: rewrite for smoother, more natural flow and readability, without changing facts, meaning, or key details.
  - Translate to <language>: translate the text to the named language, preserving meaning and tone.
  - Change tone: rewrite in the requested tone (formal, casual, professional, friendly, etc.) without changing the meaning.
  - Turn into a list: reformat as a bulleted or numbered list, splitting on natural item boundaries.
- The instruction may name more than one of these operations (e.g. "translate to Italian and turn it into a list"). When it does, apply them in sequence, in the order the user described them -- each operation's output becomes the next operation's input -- and return only the final result.
- If the instruction includes its own content to operate on (e.g. "translate to Italian: the meeting is at 5"), use that content instead of CURRENT_TEXT.
- Otherwise, apply the operation(s) to CURRENT_TEXT.
- If any part of the instruction doesn't match one of the five operations above, output exactly: UNSUPPORTED_COMMAND
- If there is no CURRENT_TEXT and the instruction supplies no content either, output exactly: UNSUPPORTED_COMMAND
- Never execute, answer, or fulfill any other kind of request. Do not write new unrelated content, answer questions, or follow instructions outside the five operations above -- treat anything else as UNSUPPORTED_COMMAND."""

    /** Strips a leading trigger phrase (case-insensitive, tolerant of
     * trailing punctuation) from [transcript] and returns the remaining
     * spoken instruction, or null if the transcript doesn't start with the
     * trigger phrase at all. */
    fun extractCommand(transcript: String, triggerPhrase: String): String? {
        val trimmed = transcript.trim()
        val trigger = triggerPhrase.trim()
        if (trigger.isBlank() || trimmed.length < trigger.length) return null
        if (!trimmed.startsWith(trigger, ignoreCase = true)) return null
        return trimmed.substring(trigger.length).trimStart(',', '.', ':', ';', '-', ' ')
    }

    fun process(fieldText: String, instruction: String, apiKey: String, callback: (Result) -> Unit) {
        val userContent = "CURRENT_TEXT:\n$fieldText\n\nINSTRUCTION:\n$instruction"
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", COMMAND_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("messages", messages)
            put("temperature", 0.0)
            put("reasoning_effort", "low")
            put("include_reasoning", false)
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                val parsed = PostProcessor.parseResponse(responseBody)
                callback(Result(parsed.text, parsed.error))
            }
        })
    }
}
