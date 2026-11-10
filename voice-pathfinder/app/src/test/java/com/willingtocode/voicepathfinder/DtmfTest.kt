package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DTMF event payload, RFC 4733.
 *
 * The digits a person presses are the one thing a provider has to interpret exactly, and the
 * framing is four bytes, so it is worth pinning rather than discovering against an IVR.
 */
class DtmfTest {

    @Test
    fun `keys map to their event codes`() {
        assertEquals(0, Dtmf.code('0'))
        assertEquals(9, Dtmf.code('9'))
        assertEquals(10, Dtmf.code('*'))
        assertEquals(11, Dtmf.code('#'))
        assertEquals(12, Dtmf.code('A'))
        assertEquals(15, Dtmf.code('d'))
        assertNull(Dtmf.code('x'))
    }

    @Test
    fun `a payload is the event, the volume, and the duration`() {
        val bytes = Dtmf.payload(event = 5, durationSamples = 800, end = false)
        assertEquals(4, bytes.size)
        assertEquals(5, bytes[0].toInt())
        assertEquals(Dtmf.VOLUME, bytes[1].toInt() and 0x3F)
        assertEquals(false, bytes[1].toInt() and 0x80 != 0)
        // 800 samples is 0x0320, high byte first.
        assertEquals(0x03, bytes[2].toInt())
        assertEquals(0x20, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `the end packets carry the end bit`() {
        val bytes = Dtmf.payload(event = 5, durationSamples = 800, end = true)
        assertTrue("the E bit should be set on the last packets", bytes[1].toInt() and 0x80 != 0)
    }

    @Test
    fun `a hundred milliseconds is five steps of twenty`() {
        assertEquals(800, Dtmf.STEP_SAMPLES * Dtmf.STEPS)
    }

    @Test
    fun `a key has two frequencies and an unknown key has none`() {
        assertEquals(697.0 to 1209.0, Dtmf.frequencies('1'))
        assertEquals(941.0 to 1477.0, Dtmf.frequencies('#'))
        assertNull(Dtmf.frequencies('x'))
    }

    @Test
    fun `the local tone lasts a hundred milliseconds and is audible`() {
        val tone = Dtmf.localTone('5') ?: throw AssertionError("no tone for a digit")
        assertEquals(800, tone.size)
        val peak = tone.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("a silent tone is no feedback", peak > 1000)
        // The edges ramp, so the first and last samples must be quieter than the middle.
        assertTrue(kotlin.math.abs(tone.first().toInt()) < peak)
        assertTrue(kotlin.math.abs(tone.last().toInt()) < peak)
    }
}
