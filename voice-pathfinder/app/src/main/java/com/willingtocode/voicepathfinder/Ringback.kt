package com.willingtocode.voicepathfinder

import kotlin.math.PI
import kotlin.math.sin

/**
 * The ringback tone a caller hears, generated here because a `180 Ringing` carries no audio.
 *
 * The North American pattern, 440 Hz plus 480 Hz for 2 seconds and then 4 seconds of
 * silence. Generated rather than recorded so there is no asset and no sample rate to get
 * wrong, and so a test can check the shape without a speaker.
 */
internal object Ringback {

    const val SAMPLE_RATE = 8_000

    /** Two seconds of tone, four of silence, per the pattern. */
    const val ON_SAMPLES = 2 * SAMPLE_RATE
    const val PERIOD_SAMPLES = 6 * SAMPLE_RATE

    private const val AMPLITUDE = 3000.0

    /**
     * Fills [frame] with the tone at sample [offset] of the pattern.
     *
     * Stateless: the caller keeps the offset, so a paused or restarted tone resumes the
     * pattern rather than clicking back to the start mid-cycle.
     */
    fun fill(frame: ShortArray, offset: Int) {
        val base = ((offset % PERIOD_SAMPLES) + PERIOD_SAMPLES) % PERIOD_SAMPLES
        for (i in frame.indices) {
            val position = (base + i) % PERIOD_SAMPLES
            frame[i] = if (position < ON_SAMPLES) sample(position) else 0
        }
    }

    /** One sample of the two-tone pair, as the carrier's own ringback mixes them. */
    fun sample(position: Int): Short {
        val t = position.toDouble() / SAMPLE_RATE
        val value = (sin(2 * PI * 440 * t) + sin(2 * PI * 480 * t)) / 2 * AMPLITUDE
        return value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
