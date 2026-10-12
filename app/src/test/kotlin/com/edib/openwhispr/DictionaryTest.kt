package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DictionaryTest {
    @Test fun parsesLegacyLinesAndDeduplicatesWithoutChangingCanonicalCase() {
        assertEquals(listOf("OpenAI", "sherpa-onnx;"), Dictionary.parse(" OpenAI\nopenai\nsherpa-onnx; "))
        assertEquals(listOf(Dictionary.Entry("OpenAI", emptyList())), Dictionary.parseEntries("OpenAI\nopenai"))
    }

    @Test fun parsesDesiredFormsAndDeduplicatesPronunciationVariantsCaseInsensitively() {
        assertEquals(
            listOf(Dictionary.Entry("OAPO", listOf("Open Whisper", "eau à peau"))),
            Dictionary.parseEntries("OAPO | Open Whisper, eau à peau, OAPO, open whisper"),
        )
        assertEquals(
            listOf("OAPO | Open Whisper, eau à peau"),
            Dictionary.parse("OAPO | Open Whisper, eau à peau"),
        )
    }

    @Test fun selectedTextIsAddedAsOnePunctuatedPhrase() {
        assertEquals(
            listOf("OpenWispr", "New York, NY"),
            Dictionary.withSelectedText(listOf("OpenWispr"), "  New York, NY  ")
        )
    }

    @Test fun selectedTextDuplicateIsNotAddedAgain() {
        assertEquals(listOf("OpenWispr"), Dictionary.withSelectedText(listOf("OpenWispr"), "openwispr"))
    }

    @Test fun selectedTextAddsCanonicalLineWithoutDroppingExistingAliases() {
        assertEquals(
            listOf("OpenWhispr | open whisper", "NASA"),
            Dictionary.withSelectedText(listOf("OpenWhispr | open whisper"), "NASA"),
        )
    }

    @Test fun sherpaHotwordFileIncludesCanonicalTermsAndAliasesAsSeparatePhrases() {
        val file = File.createTempFile("hotwords", ".txt")
        try {
            Dictionary.writeHotwords(file, listOf("OpenWhispr | open whisper, open wisper"))
            assertEquals("OpenWhispr\nopen whisper\nopen wisper", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test fun writesSherpaHotwordFile() {
        val file = File.createTempFile("hotwords", ".txt")
        try {
            val path = Dictionary.writeHotwords(file, listOf("OpenAI", "Kotlin"))
            assertEquals(file.absolutePath, path)
            assertEquals("OpenAI\nKotlin", file.readText())
        } finally {
            file.delete()
        }
    }
}