package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecorderTest {

    /** Little-endian 16-bit sample as two bytes. */
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    @Test fun `peak amplitude of empty audio is zero`() {
        assertEquals(0, VoiceRecorder.peakAmplitude(ByteArray(0)))
    }

    @Test fun `peak amplitude reads little-endian samples`() {
        assertEquals(256, VoiceRecorder.peakAmplitude(pcm(256)))
        assertEquals(1, VoiceRecorder.peakAmplitude(pcm(1)))
    }

    @Test fun `peak amplitude is the largest absolute sample, negative included`() {
        assertEquals(32768, VoiceRecorder.peakAmplitude(pcm(10, -32768, 1000)))
        assertEquals(1000, VoiceRecorder.peakAmplitude(pcm(10, -1000, 999)))
    }

    @Test fun `peak amplitude ignores a trailing half sample`() {
        val odd = pcm(500) + byteArrayOf(0x7F)
        assertEquals(500, VoiceRecorder.peakAmplitude(odd))
    }

    @Test fun `peak amplitude honours the length limit`() {
        val bytes = pcm(10, 3000)
        assertEquals(10, VoiceRecorder.peakAmplitude(bytes, 2))
        assertEquals(3000, VoiceRecorder.peakAmplitude(bytes))
    }

    @Test fun `level maps the peak to zero through one`() {
        assertEquals(0f, VoiceRecorder.level(pcm(0)), 0.0001f)
        assertEquals(0.5f, VoiceRecorder.level(pcm(6000)), 0.0001f)
        assertEquals(1f, VoiceRecorder.level(pcm(12000)), 0.0001f)
    }

    @Test fun `level never exceeds one and ignores a short buffer`() {
        assertEquals(1f, VoiceRecorder.level(pcm(32767)), 0.0001f)
        assertEquals(0f, VoiceRecorder.level(ByteArray(1)), 0.0001f)
    }
}
