package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WhenTest {

    private val tz = TimeZone.getTimeZone("America/New_York")
    private val locale = Locale.US

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis / 1000

    // Friday 6 November 2026, 18:00 in New York.
    private val now = at(2026, 11, 6, 18, 0)

    @Test
    fun `a bubble shows the clock, not the date`() {
        assertEquals("18:29", When.clock(at(2026, 11, 6, 18, 29), tz, locale, use24Hour = true))
        assertEquals("6:29 PM", When.clock(at(2026, 11, 6, 18, 29), tz, locale, use24Hour = false))
    }

    @Test
    fun `the day separator names today and yesterday`() {
        assertEquals("Today", When.day(now, now, tz, locale))
        assertEquals("Yesterday", When.day(at(2026, 11, 5, 23, 59), now, tz, locale))
    }

    @Test
    fun `an older separator carries a date without the year`() {
        assertEquals("1 Nov", When.day(at(2026, 11, 1, 9, 0), now, tz, locale))
    }

    @Test
    fun `another year gets the year in the separator`() {
        assertEquals("6 Nov 2025", When.day(at(2025, 11, 6, 9, 0), now, tz, locale))
    }

    @Test
    fun `a list row shows the clock for today and a weekday inside the week`() {
        assertEquals("18:00", When.row(now, now, tz, locale, use24Hour = true))
        assertEquals("Yesterday", When.row(at(2026, 11, 5, 12, 0), now, tz, locale, true))
        // 3 November 2026 was a Tuesday.
        assertEquals("Tue", When.row(at(2026, 11, 3, 12, 0), now, tz, locale, true))
        assertEquals("6 Oct", When.row(at(2026, 10, 6, 12, 0), now, tz, locale, true))
    }

    @Test
    fun `a message stamped in the future still reads as today`() {
        val ahead = at(2026, 11, 6, 18, 2)
        assertEquals("Today", When.day(ahead, now, tz, locale))
        assertEquals("18:02", When.row(ahead, now, tz, locale, use24Hour = true))
    }

    @Test
    fun `a 25 hour day does not shift yesterday`() {
        // DST ended in the US on Sunday 1 November 2026, so that day ran long.
        val afterDst = at(2026, 11, 2, 0, 30)
        assertEquals("Yesterday", When.day(at(2026, 11, 1, 0, 30), afterDst, tz, locale))
        assertEquals("Yesterday", When.day(at(2026, 11, 1, 23, 30), afterDst, tz, locale))
    }
}
