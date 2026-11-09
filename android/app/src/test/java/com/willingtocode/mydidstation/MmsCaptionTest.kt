package com.willingtocode.mydidstation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption limit, which is 2000 because that is what arrived.
 *
 * The vendor says 2048. A 2048 character caption was sent on 2026-11-09, stored whole by
 * VoIP.ms, and delivered to a handset as 2000 characters with the tail silently dropped. The
 * app's own thread still shows 2048, so nothing inside the app would ever reveal this.
 */
class MmsCaptionTest {

    @Test
    fun `the documented length is too long because it does not arrive`() {
        assertFalse("2048 is the vendor's number and the handset cut it", MmsCaption.fits("x".repeat(2048)))
    }

    @Test
    fun `the measured length fits, at the boundary`() {
        assertTrue(MmsCaption.fits("x".repeat(2000)))
        assertTrue(MmsCaption.fits(""))
    }

    @Test
    fun `one character past the measured length does not fit`() {
        assertFalse(MmsCaption.fits("x".repeat(2001)))
    }

    @Test
    fun `the unit is UTF-16 and the strict side is deliberate`() {
        // Kotlin strings are UTF-16, so an emoji is two units. What the carrier counts for
        // non-ASCII is unmeasured, so counting in the app is stricter than it may need to be:
        // a caption can be refused that would have arrived, and never truncated silently.
        val emoji = "\uD83D\uDE00"
        assertTrue("1000 emoji is 2000 units", MmsCaption.fits(emoji.repeat(1000)))
        assertFalse("1001 emoji is 2002 units", MmsCaption.fits(emoji.repeat(1001)))
    }
}
