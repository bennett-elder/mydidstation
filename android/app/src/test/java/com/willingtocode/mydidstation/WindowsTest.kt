package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window arithmetic, on its own.
 *
 * These numbers decide what the app ever sees. A window that starts too late loses messages
 * without a word, a load-more that stops at the wrong place hides history, and a split with a
 * gap in it drops the overflow it was written to rescue. None of that fails visibly on a
 * phone, which is why the constants are pinned here rather than checked by reading the mock's
 * log.
 */
class WindowsTest {

    private val now = 1_700_000_000L
    private val day = Windows.DAY_SECONDS

    private fun days(n: Long) = now - n * day

    // The first look at a number or a mailbox.

    @Test
    fun `a first look covers thirty days and ends now`() {
        val window = Windows.first(now)
        assertEquals(days(30), window.from)
        assertEquals(now, window.to)
    }

    @Test
    fun `the two-year floor is longer than any single request`() {
        // The floor is where load-more stops and a request never asks for more than the cap.
        // If those two ever crossed, the floor would stop being what bounds a tap.
        assertEquals(2L * 365 * day, Windows.LOOKBACK_FLOOR_SECONDS)
        assertTrue(Windows.LOOKBACK_FLOOR_SECONDS > Windows.MAX_RANGE_SECONDS)
    }

    @Test
    fun `one request asks for the same span as the first look`() {
        assertEquals(Windows.INITIAL_WINDOW_SECONDS, Windows.MAX_RANGE_SECONDS)
    }

    // Steady state polling.

    @Test
    fun `a poll starts a day before the last window ended`() {
        // The overlap is the whole reason a message that arrived between two polls is not
        // lost: without it the next window begins exactly where the last one stopped.
        val window = Windows.steady(now, cursorSec = now)
        assertEquals(now - day, window.from)
        assertEquals(now, window.to)
    }

    @Test
    fun `the overlap wins over the cap for a recent cursor`() {
        // Spelled out as one day rather than read off the constant: a test that asks the code
        // what it is about to check would pass with the overlap set to nothing.
        val recent = now - 3_600
        assertEquals(recent - day, Windows.steady(now, recent).from)
    }

    @Test
    fun `the cap wins for a phone that has been off`() {
        // A month of silence must not become one month-long request, which is the Cloudflare
        // 522 this project has already met. The crossover matters as much: a cursor exactly
        // at the cap would otherwise reach a day past it.
        assertEquals(days(30), Windows.steady(now, cursorSec = days(40)).from)
        assertEquals(days(30), Windows.steady(now, cursorSec = days(30)).from)
    }

    @Test
    fun `consecutive polls overlap and never leave a gap`() {
        // Walk forward a poll at a time and check each window starts strictly before the
        // previous one ended. Strict, because an overlap of zero is exactly the loss case:
        // anything that arrived between the last request and this one would never arrive.
        var cursor = now - 2 * day
        repeat(5) { step ->
            val window = Windows.steady(now, cursor)
            assertTrue("poll $step starts after the last one ended", window.from < cursor)
            assertEquals("poll $step ends at now", now, window.to)
            cursor = window.to
        }
    }

    // What is known to be covered.

    @Test
    fun `a number with no record is treated as one window deep`() {
        assertEquals(days(30), Windows.covered(recordedSec = 0L, nowSec = now))
    }

    @Test
    fun `a recorded floor is used as it is`() {
        assertEquals(days(90), Windows.covered(recordedSec = days(90), nowSec = now))
    }

    // Load-more.

    @Test
    fun `a load-more pulls one capped window below what is covered`() {
        val covered = days(31)
        val span = Windows.lookBackSpan(now, covered)!!
        assertEquals(covered - Windows.MAX_RANGE_SECONDS, span.from)
        assertEquals("the window ends at the oldest thing already held", covered - 1, span.to)
    }

    @Test
    fun `the last load-more is clipped at the two-year floor`() {
        val covered = days(2 * 365 - 10)
        val span = Windows.lookBackSpan(now, covered)!!
        assertEquals("clipped to the floor rather than reaching past it", days(2 * 365), span.from)
        assertEquals(covered - 1, span.to)
    }

    @Test
    fun `there is nothing to load once the floor is reached`() {
        assertNull(Windows.lookBackSpan(now, coveredSec = days(2 * 365)))
        assertNull("older than the floor is still nothing", Windows.lookBackSpan(now, coveredSec = days(800)))
    }

    @Test
    fun `the label matches the window it describes`() {
        val covered = days(31)
        val plan = Windows.lookBackPlan(now, covered)
        val span = Windows.lookBackSpan(now, covered)!!
        assertEquals("30 more days is what one tap pulls", 30, plan.days)
        assertEquals("the label and the window agree", (covered - span.from) / day, plan.days.toLong())
        assertTrue("a full window leaves more behind it", plan.more)
    }

    @Test
    fun `the clipped last window says nothing follows it`() {
        val plan = Windows.lookBackPlan(now, coveredSec = days(2 * 365 - 10))
        assertEquals("the clipped window is ten days", 10, plan.days)
        assertFalse("the floor has been reached", plan.more)
        assertEquals(LookBack(days = 0, more = false), Windows.lookBackPlan(now, days(2 * 365)))
    }

    @Test
    fun `repeated load-mores walk back to the floor and then stop`() {
        // The control has to end. A plan that never says more=false is a button that either
        // fetches nothing forever or offers a tap with nothing behind it.
        var covered = Windows.covered(recordedSec = 0L, nowSec = now)
        var taps = 0
        while (true) {
            val span = Windows.lookBackSpan(now, covered) ?: break
            assertTrue("tap $taps went forwards instead of back", span.from < covered)
            covered = span.from
            taps++
            assertTrue("load-more never terminated", taps < 60)
        }
        assertEquals("two years of thirty day taps", 24, taps)
        assertEquals("stopped exactly at the floor", now - Windows.LOOKBACK_FLOOR_SECONDS, covered)
    }

    // Splitting an over-full window.

    @Test
    fun `halves meet exactly with no gap and no overlap`() {
        val (left, right) = Windows.halves(from = 1000L, to = 2000L)
        assertEquals(1000L, left.from)
        assertEquals("the halves touch", left.to + 1, right.from)
        assertEquals(2000L, right.to)
        assertEquals(
            "every instant lands in exactly one half",
            2000L - 1000L + 1,
            (left.to - left.from + 1) + (right.to - right.from + 1),
        )
    }

    @Test
    fun `halves are well formed for odd and single-unit spans`() {
        listOf(1L to 2L, 1L to 3L, 10L to 11L, 0L to 999L).forEach { (from, to) ->
            val (left, right) = Windows.halves(from, to)
            assertTrue("$from..$to left stays inside", left.from >= from && left.to <= to)
            assertTrue("$from..$to right stays inside", right.from >= from && right.to <= to)
            assertEquals("$from..$to keeps the head", from, left.from)
            assertEquals("$from..$to leaves no gap", left.to + 1, right.from)
            assertEquals("$from..$to loses no tail", to, right.to)
        }
    }

    @Test
    fun `three levels of halving still cover the original span exactly`() {
        // fetchWindow splits three deep, so the arithmetic has to survive repetition: an
        // overlap at any level is a request made twice, and a gap is rows that never come.
        var spans = listOf(Span(from = 0L, to = 1000L))
        repeat(3) {
            spans = spans.flatMap { span ->
                val (left, right) = Windows.halves(span.from, span.to)
                listOf(left, right)
            }
        }
        assertEquals(8, spans.size)
        assertEquals("the first piece starts at the front", 0L, spans.first().from)
        assertEquals("the last piece ends at the back", 1000L, spans.last().to)
        spans.zipWithNext().forEach { (a, b) ->
            assertEquals("a gap between ${a.from}..${a.to} and ${b.from}..${b.to}", a.to + 1, b.from)
        }
        assertEquals("no instant is covered twice", 1001L, spans.sumOf { it.to - it.from + 1 })
    }
}
