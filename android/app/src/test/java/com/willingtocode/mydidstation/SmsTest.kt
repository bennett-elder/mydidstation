package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTest {

    private fun gsm(n: Int) = "a".repeat(n)

    @Test
    fun `single part up to 160 gsm units`() {
        val analysis = Sms.analyze(gsm(160))
        assertEquals(1, analysis.segments)
        assertEquals(160, analysis.budget)
        assertEquals(0, analysis.remaining)
    }

    @Test
    fun `one unit over reflows the whole message to 153`() {
        val analysis = Sms.analyze(gsm(161))
        assertEquals(2, analysis.segments)
        assertEquals(306, analysis.budget)
        assertEquals(SmsEncoding.GSM7, analysis.encoding)
    }

    @Test
    fun `gsm extension characters cost two units`() {
        // 80 euro signs = 160 units -> still one part; 81 -> two parts.
        assertEquals(1, Sms.analyze("\u20AC".repeat(80)).segments)
        assertEquals(2, Sms.analyze("\u20AC".repeat(81)).segments)
        assertEquals(2, Sms.analyze("\u20AC").units)
    }

    @Test
    fun `non gsm text drops to 70 and 67`() {
        val one = Sms.analyze("\u201C".repeat(70)) // curly quote is not GSM
        assertEquals(SmsEncoding.UCS2, one.encoding)
        assertEquals(1, one.segments)
        assertEquals(70, one.budget)

        val two = Sms.analyze("\u201C".repeat(71))
        assertEquals(2, two.segments)
        assertEquals(134, two.budget)
    }

    @Test
    fun `emoji costs two ucs2 units`() {
        assertEquals(2, Sms.analyze("\uD83D\uDE00").units)
    }

    @Test
    fun `split lands on a word boundary`() {
        // 150 chars, then a long word that crosses the 153 boundary.
        val text = "a".repeat(150) + " " + "b".repeat(40)
        val analysis = Sms.analyze(text)
        assertEquals(2, analysis.segments)
        assertFalse("should not need a hard break", analysis.hardBreak)
        val parts = Sms.parts(text, analysis)
        assertEquals(text, parts.joinToString(""))
        assertTrue("part 1 ends at the space", parts[0].endsWith(" "))
        assertTrue("part 2 is the whole word", parts[1].all { it == 'b' })
    }

    @Test
    fun `single word longer than a part is a hard break`() {
        val analysis = Sms.analyze("b".repeat(200))
        assertEquals(2, analysis.segments)
        assertTrue(analysis.hardBreak)
    }

    @Test
    fun `parts always reassemble to the original`() {
        val text = (1..40).joinToString(" ") { "word$it" }
        val analysis = Sms.analyze(text)
        assertEquals(text, Sms.parts(text, analysis).joinToString(""))
    }
}
