package com.willingtocode.voicepathfinder

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words the Calls screen uses.
 *
 * Small, but each one is a claim shown to the user: how old the list is, what the provider
 * decided, and how long the call lasted.
 */
class CallsTextTest {

    private val now = System.currentTimeMillis()
    private fun time(at: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    @Test
    fun `the list says how many and when it was read`() {
        assertEquals("3 calls, checked ${time(now)}", callsSummary(count = 3, checkedAt = now))
        assertEquals("1 call, checked ${time(now)}", callsSummary(count = 1, checkedAt = now))
    }

    @Test
    fun `a list never read says so rather than showing a time`() {
        assertEquals("2 calls, not read from the provider yet", callsSummary(count = 2, checkedAt = 0L))
    }

    @Test
    fun `the provider's dispositions become words`() {
        assertEquals("Answered", dispositionText("ANSWERED"))
        assertEquals("No answer", dispositionText("no answer"))
        assertEquals("Busy", dispositionText("BUSY"))
        assertEquals("Failed", dispositionText("FAILED"))
        assertEquals("Canceled", dispositionText("CANCELED"))
        assertEquals("Unknown", dispositionText(""))
    }

    @Test
    fun `a duration is seconds, or minutes and seconds`() {
        assertEquals("-", callLength(0))
        assertEquals("21 s", callLength(21))
        assertEquals("1 m 21 s", callLength(81))
        assertEquals("2 m 0 s", callLength(120))
    }
}
