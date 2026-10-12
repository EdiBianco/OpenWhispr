package com.edib.openwhispr

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

/** Configuration for transcript cleanup; transcription itself always uses Groq. */
data class CleanupProviderConfig(
    val provider: Provider = Provider.GROQ,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    enum class Provider(val preferenceValue: String, val title: String) {
        GROQ("groq", "Groq"),
        OPENAI_COMPATIBLE("openai_compatible", "OpenAI-compatible");

        val requiresCustomConfiguration: Boolean
            get() = this == OPENAI_COMPATIBLE

        companion object {
            /** Missing/unknown persisted values keep existing installs on Groq. */
            fun fromPreference(value: String?): Provider =
                entries.firstOrNull { it.preferenceValue == value } ?: GROQ
        }
    }

    fun chatCompletionsUrl(): HttpUrl {
        val configuredUrl = if (provider == Provider.GROQ) GROQ_CHAT_COMPLETIONS_URL else baseUrl
        val url = configuredUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Cleanup base URL must be a valid HTTPS URL")
        require(url.isHttps) { "Cleanup base URL must use HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Cleanup base URL cannot include credentials, query parameters, or a fragment"
        }
        val path = url.encodedPath.trimEnd('/')
        val endpointPath = if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
        return url.newBuilder().encodedPath(endpointPath.ifBlank { "/chat/completions" }).build()
    }

    fun requestModel(): String = if (provider == Provider.GROQ) {
        GROQ_MODEL
    } else {
        model.trim().ifBlank { DEFAULT_MODEL }
    }

    companion object {
        const val GROQ_CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val GROQ_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"

        fun fromPreferences(provider: String?, baseUrl: String?, model: String?) = CleanupProviderConfig(
            provider = Provider.fromPreference(provider),
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            model = model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
        )
    }
}

internal fun cleanupRequest(
    text: String,
    prompt: String,
    apiKey: String,
    config: CleanupProviderConfig,
): Request {
    require(apiKey.isNotBlank()) { "Cleanup API key is required" }
    val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", prompt)
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })
    }
    val bodyJson = JSONObject().apply {
        put("model", config.requestModel())
        put("messages", messages)
        put("temperature", 0.0)
        if (config.provider == CleanupProviderConfig.Provider.GROQ) {
            put("reasoning_effort", "low")
            put("include_reasoning", false)
        }
    }

    return Request.Builder()
        .url(config.chatCompletionsUrl())
        .header("Authorization", "Bearer $apiKey")
        .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
        .build()
}
