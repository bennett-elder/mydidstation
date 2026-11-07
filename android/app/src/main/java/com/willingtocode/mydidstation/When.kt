package com.willingtocode.mydidstation

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Timestamps, in the three places they appear.
 *
 * A thread where every bubble says "Nov 6" says nothing useful, so a bubble
 * carries a clock and the day is stated once, by a separator. The zone, the locale
 * and the 24-hour preference are arguments rather than ambient state, so these
 * rules can be tested without an emulator set to a particular time zone.
 */
object When {

    /** Clock time for a bubble: "18:29", or "6:29 PM" where that is the habit. */
    fun clock(epochSeconds: Long, tz: TimeZone, locale: Locale, use24Hour: Boolean): String =
        SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", locale)
            .apply { timeZone = tz }
            .format(Date(epochSeconds * 1000))

    /** "Today", "Yesterday", or a date. Used for the separator between messages. */
    fun day(epochSeconds: Long, nowSeconds: Long, tz: TimeZone, locale: Locale): String =
        when (dayGap(epochSeconds, nowSeconds, tz)) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> date(epochSeconds, nowSeconds, tz, locale)
        }

    /** For a list row: the clock today, "Yesterday", a weekday within a week, else a date. */
    fun row(
        epochSeconds: Long,
        nowSeconds: Long,
        tz: TimeZone,
        locale: Locale,
        use24Hour: Boolean,
    ): String {
        val gap = dayGap(epochSeconds, nowSeconds, tz)
        return when {
            // A message stamped slightly in the future is still "now".
            gap <= 0 -> clock(epochSeconds, tz, locale, use24Hour)
            gap == 1 -> "Yesterday"
            gap < 7 -> SimpleDateFormat("EEE", locale).apply { timeZone = tz }
                .format(Date(epochSeconds * 1000))
            else -> date(epochSeconds, nowSeconds, tz, locale)
        }
    }

    /** "4 Nov", with the year only when it is not the current one. */
    private fun date(epochSeconds: Long, nowSeconds: Long, tz: TimeZone, locale: Locale): String {
        val pattern = if (year(epochSeconds, tz) == year(nowSeconds, tz)) "d MMM" else "d MMM yyyy"
        return SimpleDateFormat(pattern, locale).apply { timeZone = tz }
            .format(Date(epochSeconds * 1000))
    }

    private fun year(epochSeconds: Long, tz: TimeZone): Int =
        calendar(epochSeconds, tz).get(Calendar.YEAR)

    /**
     * Whole days between the two instants. Counted from calendar fields rather than
     * by dividing milliseconds, because a day is 23 or 25 hours twice a year and
     * that would move yesterday's messages to the wrong side of the line.
     */
    private fun dayGap(epochSeconds: Long, nowSeconds: Long, tz: TimeZone): Int =
        dayNumber(nowSeconds, tz) - dayNumber(epochSeconds, tz)

    private fun dayNumber(epochSeconds: Long, tz: TimeZone): Int {
        val c = calendar(epochSeconds, tz)
        // Coefficients chosen so the field arithmetic stays monotonic across years.
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.MONTH) * 32 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    private fun calendar(epochSeconds: Long, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply { timeInMillis = epochSeconds * 1000 }
}
