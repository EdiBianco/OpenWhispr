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

    private fun partContent(part: okhttp3.MultipartBody.Part): String =
        okio.Buffer().also { part.body.writeTo(it) }.readUtf8()

    private fun okhttp3.MultipartBody.Part.hasDispositionName(name: String): Boolean =
        headers?.get("Content-Disposition")?.contains("name=\"$name\"") == true

}
