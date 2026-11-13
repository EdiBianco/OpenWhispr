package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `builds Groq Whisper multipart with dictionary-only prompt`() {
        val request = TranscriberClient.transcriptionRequest(
            byteArrayOf(1, 2, 3),
            "test-key",
            listOf("OpenWhispr | open whisper, open wisper", "NASA"),
        )
        val multipart = request.body as okhttp3.MultipartBody
        val parts = multipart.parts
        val model = partContent(parts.first { it.hasDispositionName("model") })
        val prompt = partContent(parts.first { it.hasDispositionName("prompt") })

        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url.toString())
        assertEquals("whisper-large-v3", model)
        assertTrue(prompt.contains("OpenWhispr"))
        assertTrue(prompt.contains("open whisper"))
        assertTrue(prompt.contains("open wisper"))
        assertTrue(prompt.contains("NASA"))
        assertFalse(prompt.contains("cleanup"))
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= TranscriberClient.MAX_DICTIONARY_PROMPT_BYTES)
    }

    @Test fun `omits Groq prompt multipart field when dictionary is empty`() {
        val request = TranscriberClient.transcriptionRequest(byteArrayOf(1, 2, 3), "test-key", emptyList())
        val multipart = request.body as okhttp3.MultipartBody

        assertEquals("whisper-large-v3", partContent(multipart.parts.first { it.hasDispositionName("model") }))
        assertFalse(multipart.parts.any { it.hasDispositionName("prompt") })
    }

    @Test fun `bounds UTF-8 dictionary prompt below Groq token limit`() {
        val prompt = TranscriberClient.dictionaryPrompt(listOf("Élodie", "界".repeat(100)))

        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= 128)
        assertTrue(prompt.contains("Élodie"))
    }

    @Test fun `missing or blank saved model falls back to the default`() {
        assertEquals("whisper-large-v3", TranscriberClient.modelId(null))
        assertEquals("whisper-large-v3", TranscriberClient.modelId("   "))
    }

    @Test fun `any saved model id is used, trimmed`() {
        assertEquals("whisper-large-v3-turbo", TranscriberClient.modelId("whisper-large-v3-turbo"))
        assertEquals("my-custom-whisper", TranscriberClient.modelId("  my-custom-whisper "))
    }

    @Test fun `model list keeps only whisper models in server order`() {
        val json = """{"object":"list","data":[
            {"id":"llama-3.3-70b-versatile"},
            {"id":"whisper-large-v3-turbo"},
            {"id":"whisper-large-v3"},
            {"id":"playai-tts"}
        ]}"""

        assertEquals(
            listOf("whisper-large-v3-turbo", "whisper-large-v3"),
            TranscriberClient.parseModelIds(json),
        )
    }

    @Test fun `unreadable model list gives no ids`() {
        assertTrue(TranscriberClient.parseModelIds("not json").isEmpty())
        assertTrue(TranscriberClient.parseModelIds("""{"error":"bad key"}""").isEmpty())
    }

    @Test fun `uses the chosen cloud model in the request`() {
        val request = TranscriberClient.transcriptionRequest(
            byteArrayOf(1, 2, 3),
            "test-key",
            emptyList(),
            emptySet(),
            "distil-whisper-large-v3-en",
        )
        val multipart = request.body as okhttp3.MultipartBody

        assertEquals(
            "distil-whisper-large-v3-en",
            partContent(multipart.parts.first { it.hasDispositionName("model") }),
        )
    }

    private fun partContent(part: okhttp3.MultipartBody.Part): String =
        okio.Buffer().also { part.body.writeTo(it) }.readUtf8()

    private fun okhttp3.MultipartBody.Part.hasDispositionName(name: String): Boolean =
        headers?.get("Content-Disposition")?.contains("name=\"$name\"") == true

}
