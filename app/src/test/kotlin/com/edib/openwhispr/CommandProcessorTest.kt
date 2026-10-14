package com.edib.openwhispr

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
}
