package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The status strip's countdown to the next background poll.
 *
 * It is rebuilt once a second while a number is on screen, and `internal` only so this
 * file can reach it. A wrong label here is invisible until somebody reads the strip, so
 * the boundaries are pinned rather than sampled.
 */
class CountdownTextTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `no scheduled run means no text`() {
        assertNull(countdownText(null, now))
    }

    @Test
    fun `a run that has come due says so`() {
        assertEquals("due now", countdownText(now, now))
        assertEquals("due now", countdownText(now - 5_000, now))
    }

    @Test
    fun `under an hour counts minutes and seconds`() {
        assertEquals("0:59", countdownText(now + 59_000, now))
        assertEquals("1:00", countdownText(now + 60_000, now))
        assertEquals("1:30", countdownText(now + 90_000, now))
        assertEquals("59:59", countdownText(now + 3_599_000, now))
    }

    @Test
    fun `an hour or more switches to hours and minutes`() {
        assertEquals("1h 00m", countdownText(now + 3_600_000, now))
        assertEquals("1h 01m", countdownText(now + 3_660_000, now))
        assertEquals("25h 00m", countdownText(now + 90_000_000, now))
    }

    @Test
    fun `a partial second truncates rather than rounding up`() {
        // 59.6 seconds left is still under a minute, and rounding would print 1:00 while
        // the poll is still a second away.
        assertEquals("0:59", countdownText(now + 59_600, now))
    }
}
