package com.edib.openwhispr

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupProviderConfigTest {
    @Test
    fun `missing and unknown provider settings keep existing installs on Groq`() {
        val existingInstall = CleanupProviderConfig.fromPreferences(null, null, null)
        val unknown = CleanupProviderConfig.fromPreferences("unsupported", null, null)

        assertEquals(CleanupProviderConfig.Provider.GROQ, existingInstall.provider)
        assertEquals(CleanupProviderConfig.Provider.GROQ, unknown.provider)
        assertEquals(CleanupProviderConfig.GROQ_CHAT_COMPLETIONS_URL, existingInstall.chatCompletionsUrl().toString())
        assertEquals(CleanupProviderConfig.GROQ_MODEL, existingInstall.requestModel())
        assertEquals(CleanupProviderConfig.DEFAULT_BASE_URL, existingInstall.baseUrl)
        assertEquals(CleanupProviderConfig.DEFAULT_MODEL, existingInstall.model)
    }

    @Test
    fun `OpenRouter uses configurable model and OpenAI chat completions format`() {
        val config = CleanupProviderConfig.fromPreferences(
            "openai_compatible",
            "https://openrouter.ai/api/v1/",
            "anthropic/claude-3.5-haiku",
        )

        val request = cleanupRequest("hello", "clean literally", "secret", config)
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("anthropic/claude-3.5-haiku", body.getString("model"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("include_reasoning"))
    }

    @Test
    fun `DeepSeek base URL ending in v1 builds chat completions endpoint`() {
        val config = CleanupProviderConfig(
            provider = CleanupProviderConfig.Provider.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )

        val request = cleanupRequest("text", "prompt", "key", config)

        assertEquals("https://api.deepseek.com/v1/chat/completions", request.url.toString())
        assertEquals("deepseek-chat", JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("model"))
    }

    @Test
    fun `Groq retains legacy model and Groq-only request options`() {
        val request = cleanupRequest("text", "prompt", "key", CleanupProviderConfig())
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

        assertEquals(CleanupProviderConfig.GROQ_CHAT_COMPLETIONS_URL, request.url.toString())
        assertEquals(CleanupProviderConfig.GROQ_MODEL, body.getString("model"))
        assertEquals("low", body.getString("reasoning_effort"))
        assertFalse(body.getBoolean("include_reasoning"))
    }

    @Test
    fun `OpenAI compatible configuration rejects insecure URLs`() {
        val config = CleanupProviderConfig(
            provider = CleanupProviderConfig.Provider.OPENAI_COMPATIBLE,
            baseUrl = "http://api.example.com/v1",
            model = "model",
        )

        val error = try {
            config.chatCompletionsUrl()
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertTrue(error?.message?.contains("HTTPS") == true)
    }

    @Test
    fun `transcription stays on Groq Whisper Large V3`() {
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", TranscriberClient.TRANSCRIPTION_URL)
        assertEquals("whisper-large-v3", TranscriberClient.TRANSCRIPTION_MODEL)
    }
}
