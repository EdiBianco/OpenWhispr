package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val snapshot = SettingsBackup.Snapshot(
        prefs = mapOf(
            "custom_instructions" to "always spell out NASA",
            "bubble_size_percent" to 120,
            "overlay_position_percent" to 35,
            "overlay_on_left" to true,
            "voice_commands_enabled" to true,
            "transcription_languages" to setOf("tr", "en"),
            "cached_update_version" to "9.9.9",
            "api_key" to "gsk_legacy_value_should_not_export",
        ),
        secrets = mapOf(
            "groq_api_key" to "gsk_test_groq_key_1234567890",
            "cleanup_api_key" to "",
        ),
    )

    @Test
    fun `round trip restores settings and keys`() {
        val file = SettingsBackup.encrypt(snapshot, "correct horse battery")
        val restored = SettingsBackup.decrypt(file, "correct horse battery")
        assertEquals("always spell out NASA", restored.prefs["custom_instructions"])
        assertEquals(120, restored.prefs["bubble_size_percent"])
        assertEquals(35, restored.prefs["overlay_position_percent"])
        assertEquals(true, restored.prefs["overlay_on_left"])
        assertEquals(setOf("tr", "en"), restored.prefs["transcription_languages"])
        assertEquals("gsk_test_groq_key_1234567890", restored.secrets["groq_api_key"])
    }

    @Test
    fun `volatile caches and legacy keys are left out`() {
        val restored = SettingsBackup.decrypt(
            SettingsBackup.encrypt(snapshot, "correct horse battery"), "correct horse battery"
        )
        assertFalse(restored.prefs.containsKey("cached_update_version"))
        assertFalse(restored.prefs.containsKey("api_key"))
    }

    @Test
    fun `blank keys are not exported`() {
        val restored = SettingsBackup.decrypt(
            SettingsBackup.encrypt(snapshot, "correct horse battery"), "correct horse battery"
        )
        assertFalse(restored.secrets.containsKey("cleanup_api_key"))
    }

    @Test
    fun `file does not contain plain text keys`() {
        val file = SettingsBackup.encrypt(snapshot, "correct horse battery")
        assertFalse(file.contains("gsk_test_groq_key"))
        assertFalse(file.contains("always spell out NASA"))
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val file = SettingsBackup.encrypt(snapshot, "correct horse battery")
        assertThrows(SettingsBackup.BackupException::class.java) {
            SettingsBackup.decrypt(file, "wrong passphrase")
        }
    }

    @Test
    fun `tampered file is rejected`() {
        val file = SettingsBackup.encrypt(snapshot, "correct horse battery")
        val tampered = file.replace(Regex("\"data\" *: *\"[^\"]+\""), "\"data\": \"AAAAAAAAAAAAAAAAAAAA\"")
        assertTrue(tampered != file)
        assertThrows(SettingsBackup.BackupException::class.java) {
            SettingsBackup.decrypt(tampered, "correct horse battery")
        }
    }

    @Test
    fun `short passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.encrypt(snapshot, "short")
        }
    }

    @Test
    fun `each export uses a fresh salt`() {
        val a = SettingsBackup.encrypt(snapshot, "correct horse battery")
        val b = SettingsBackup.encrypt(snapshot, "correct horse battery")
        assertTrue(a != b)
    }
}
