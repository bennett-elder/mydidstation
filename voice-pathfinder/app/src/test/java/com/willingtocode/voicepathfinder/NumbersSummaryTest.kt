package com.willingtocode.voicepathfinder

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line under the Numbers heading.
 *
 * It is the only place the screen admits how old the list is, so the wording is worth
 * pinning: a count, when it was read, and what is registered off it.
 */
class NumbersSummaryTest {

    private val now = System.currentTimeMillis()

    private fun time(at: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    @Test
    fun `a read list says how many and when it was read`() {
        assertEquals(
            "5 numbers, checked ${time(now)}",
            numbersSummary(count = 5, checkedAt = now, lines = 0),
        )
    }

    @Test
    fun `a registered line is mentioned once the line exists`() {
        val text = numbersSummary(count = 5, checkedAt = now, lines = 1)
        assertTrue(text.endsWith(". 1 line registered."))
        assertTrue(text.contains("5 numbers"))
    }

    @Test
    fun `a list that was never read says so rather than showing a time`() {
        assertEquals(
            "3 numbers, not read from the account yet",
            numbersSummary(count = 3, checkedAt = 0L, lines = 0),
        )
    }
}
