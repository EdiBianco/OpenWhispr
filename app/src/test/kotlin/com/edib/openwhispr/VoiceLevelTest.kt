package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {

    @Test fun `a louder buffer raises the level immediately`() {
        assertEquals(0.8f, VoiceLevel.smoothed(0.8f, 0f), 0.0001f)
        assertEquals(0.9f, VoiceLevel.smoothed(0.9f, 0.2f), 0.0001f)
    }

    @Test fun `a quiet buffer decays by fifteen percent per tick`() {
        assertEquals(0.85f, VoiceLevel.smoothed(0f, 1f), 0.0001f)
        assertEquals(0.7225f, VoiceLevel.smoothed(0f, 0.85f), 0.0001f)
    }

    @Test fun `the level settles towards silence instead of snapping to it`() {
        var level = 1f
        repeat(3) { level = VoiceLevel.smoothed(0f, level) }
        assertTrue("still audible after three ticks: $level", level > 0.5f)
        repeat(40) { level = VoiceLevel.smoothed(0f, level) }
        assertTrue("effectively silent by now: $level", level < 0.01f)
    }

    @Test fun `the level stays within its range for in-range input`() {
        var level = 0f
        listOf(0.2f, 1f, 1f, 0f, 0.6f, 0.6f, 0.6f).forEach {
            level = VoiceLevel.smoothed(it, level)
            assertTrue("level in 0..1: $level", level in 0f..1f)
        }
    }

    @Test fun `scale is one at silence and grows with the level`() {
        assertEquals(1f, VoiceLevel.scale(0f), 0.0001f)
        assertEquals(1.075f, VoiceLevel.scale(0.5f), 0.0001f)
        assertEquals(1.15f, VoiceLevel.scale(1f), 0.0001f)
    }
}
