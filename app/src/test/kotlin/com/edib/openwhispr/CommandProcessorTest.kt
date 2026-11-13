package com.edib.openwhispr

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandProcessorTest {

    private val trigger = "Whisper Command"

    @Test fun `trailing command splits content and instruction`() {
        val r = CommandProcessor.extractTrailingCommand(
            "Ciao, voglio provare questo comando. Whisper command, traducilo in inglese.", trigger
        )
        assertEquals("Ciao, voglio provare questo comando.", r?.content)
        assertEquals("traducilo in inglese.", r?.instruction)
    }

    @Test fun `trailing command works without punctuation before the trigger`() {
        val r = CommandProcessor.extractTrailingCommand("Ciao come stai whisper command traduci", trigger)
        assertEquals("Ciao come stai", r?.content)
        assertEquals("traduci", r?.instruction)
    }

    @Test fun `trailing command ignores the trigger inside a longer word`() {
        assertNull(CommandProcessor.extractTrailingCommand("Ciao whisper commander traduci", trigger))
    }

    @Test fun `trailing command ignores a leading trigger`() {
        assertNull(CommandProcessor.extractTrailingCommand("Whisper command, translate to English.", trigger))
    }

    @Test fun `trailing command needs an instruction after the trigger`() {
        assertNull(CommandProcessor.extractTrailingCommand("Hello there. Whisper command.", trigger))
    }

    @Test fun `trailing command uses the last trigger`() {
        val r = CommandProcessor.extractTrailingCommand(
            "Uno. Whisper command, riassumi. Due. Whisper command, traduci in inglese", trigger
        )
        assertEquals("Uno. Whisper command, riassumi. Due.", r?.content)
        assertEquals("traduci in inglese", r?.instruction)
    }

    @Test fun `commands follow the configured cleanup provider`() {
        val config = CleanupProviderConfig.fromPreferences(
            "openai_compatible",
            "https://api.deepseek.com/v1",
            "deepseek-chat",
        )

        val request = chatRequest(
            CommandProcessor.commandMessages("Ciao", "traduci in inglese"),
            "secret",
            config,
        )
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

        assertEquals("https://api.deepseek.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("deepseek-chat", body.getString("model"))
        assertFalse(body.has("reasoning_effort"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("Ciao"))
    }
}
