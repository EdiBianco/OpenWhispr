package com.edib.openwhispr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Microphone capture for one dictation: 16 kHz mono 16-bit PCM until [stop].
 *
 * The caller owns the threading and preference policy: [onLevel] is delivered
 * on the recording thread, so the service hops to its own handler. Everything
 * here stays free of Android UI and preferences, and the sample math is a
 * companion function so it can be unit-tested without a device.
 *
 * The captured audio is handed back only through [stop]; nothing here writes
 * it anywhere, and nothing here logs it.
 */
internal class VoiceRecorder(private val onLevel: (Float) -> Unit) {

    private var record: AudioRecord? = null
    private var pcm: ByteArrayOutputStream? = null
    private var capturing = false

    /** True between a successful [start] and [stop]. */
    val isCapturing: Boolean get() = capturing

    /** Opens the microphone and reads until [stop]. False if it cannot open. */
    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val opened = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: SecurityException) {
            return false
        }

        record = opened
        pcm = ByteArrayOutputStream()
        capturing = true
        opened.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            while (capturing) {
                val read = record?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    pcm?.write(buffer, 0, read)
                    // Only read for the overlay's voice-reactive size.
                    onLevel(level(buffer, read))
                }
            }
        }.start()
        return true
    }

    /** Stops, releases the microphone, and returns everything captured. */
    fun stop(): ByteArray {
        capturing = false
        record?.stop()
        record?.release()
        record = null
        val captured = pcm?.toByteArray() ?: ByteArray(0)
        pcm = null
        return captured
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** Peak at which the voice-reactive bubble reaches full size. */
        private const val LEVEL_FULL_SCALE = 12000f

        /** Peak absolute 16-bit little-endian sample in the first [length] bytes. */
        fun peakAmplitude(bytes: ByteArray, length: Int = bytes.size): Int {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                peak = max(peak, abs(sampleAt(bytes, i)))
                i += 2
            }
            return peak
        }

        /** Peak of one captured buffer mapped to 0..1, for the overlay. */
        fun level(bytes: ByteArray, length: Int = bytes.size): Float =
            (peakAmplitude(bytes, length) / LEVEL_FULL_SCALE).coerceIn(0f, 1f)

        private fun sampleAt(bytes: ByteArray, index: Int): Int =
            ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort().toInt()
    }
}
