package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Which dates the call list asks the provider about, and which dates it shows.
 *
 * `date_to` is a date, and a bare date is read as the start of that day. Asking for today
 * therefore returned nothing that happened today, which is the bug these check. The query
 * ends tomorrow and the window on screen ends today, so the person is told about the day
 * they are living in without being told about a day that has not happened.
 */
class CallWindowTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        GregorianCalendar(utc).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun the_query_ends_tomorrow_so_today_is_included() {
        val window = callWindow(at(2026, 11, 15, 12), utc, "-5")
        assertEquals("2026-11-16", window.to)
    }

    @Test
    fun the_query_starts_yesterday_and_the_window_ends_today() {
        val window = callWindow(at(2026, 11, 15, 12), utc, "-5")
        assertEquals("2026-11-14", window.from)
        assertEquals("2026-11-14 to 2026-11-15, UTC-5", window.shown)
    }

    @Test
    fun loading_more_reaches_back_from_the_oldest_call_held() {
        val window = olderWindow(at(2026, 11, 15, 12), utc, "2026-11-09 20:14:03")
        assertEquals("2026-11-02" to "2026-11-09", window)
    }

    @Test
    fun with_nothing_stored_it_asks_for_the_week_before_yesterday() {
        val window = olderWindow(at(2026, 11, 15, 12), utc, null)
        assertEquals("2026-11-07" to "2026-11-14", window)
    }

    @Test
    fun a_month_boundary_does_not_produce_an_impossible_date() {
        val window = callWindow(at(2026, 12, 1, 1), utc, "-5")
        assertEquals("2026-11-30", window.from)
        assertEquals("2026-12-02", window.to)
        assertEquals("2026-11-30 to 2026-12-01, UTC-5", window.shown)
    }
}
