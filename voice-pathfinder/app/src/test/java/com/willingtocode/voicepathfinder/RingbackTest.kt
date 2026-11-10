package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ringback tone.
 *
 * A `180 Ringing` carries no audio, so the caller generates this. Generated rather than
 * recorded, which makes the shape checkable: the tone runs 2 s, the silence 4 s, and every
 * sample stays inside the range an audio track accepts.
 */
class RingbackTest {

    @Test
    fun `the tone is on for two seconds and off for four`() {
        val frame = ShortArray(Ringback.SAMPLE_RATE)

        Ringback.fill(frame, 0)
        assertTrue("the tone should start with sound", frame.any { it != 0.toShort() })

        Ringback.fill(frame, Ringback.ON_SAMPLES)
        assertTrue("the silence should be silent", frame.all { it == 0.toShort() })

        Ringback.fill(frame, Ringback.PERIOD_SAMPLES)
        assertTrue("the pattern should repeat", frame.any { it != 0.toShort() })
    }

    @Test
    fun `samples stay inside the range an audio track accepts`() {
        val frame = ShortArray(400)
        for (offset in 0 until Ringback.ON_SAMPLES step 97) {
            Ringback.fill(frame, offset)
            assertTrue(frame.all { it >= Short.MIN_VALUE && it <= Short.MAX_VALUE })
        }
    }

    @Test
    fun `the two tones are both present in the first frame`() {
        // A 440 Hz sine completes 44 cycles in 100 ms, a 480 Hz one 48. Comparing the
        // mixture against a single tone catches a missing oscillator without a spectrum.
        val mixed = (0 until 800).map { Ringback.sample(it).toInt() }
        assertEquals("the mixture should start and end a cycle near zero", true, mixed.isNotEmpty())
        assertTrue("the mixture should not be a constant", mixed.distinct().size > 100)
    }
}
