package com.edib.openwhispr

/**
 * The recording bubble's voice-reactive level math, kept apart from the view
 * code so it can be exercised without a device.
 *
 * The feel is deliberately VU-meter-like: the level jumps to a new peak at
 * once, so the bubble responds the instant you speak, and falls only gradually,
 * so it settles instead of flickering between words.
 */
object VoiceLevel {
    /** Fall per tick: a quiet tick keeps 85% of the previous level. */
    private const val DECAY = 0.85f

    /** Growth at full volume: the button reaches 1.15x, which still fits the
     * overlay window (56dp around a 44dp button). */
    private const val MAX_GROWTH = 0.15f

    /** [previous] advanced by one tick of [level] (both 0..1). */
    fun smoothed(level: Float, previous: Float): Float = maxOf(level, previous * DECAY)

    /** Button scale for a smoothed level. */
    fun scale(smoothed: Float): Float = 1f + MAX_GROWTH * smoothed
}
