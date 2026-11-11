package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the person reads instead of trusting a count.
 *
 * `Ready. 1 line registered.` was true of a line that had registered an hour ago and of one
 * that had never succeeded, because both were a number in a tally. These check that the words
 * carry the transport, the server and what is left, or the reason there is none.
 */
class LineStandingTest {

    @Test
    fun a_grant_says_what_is_left_of_it() {
        val now = 1_000_000L
        val state = LineState.Registered(grantedSeconds = 3600, atMillis = now - 60_000)
        val text = lineStanding(state, "TLS", "tampa1.voip.ms", now)
        assertTrue(text, text.contains("TLS to tampa1.voip.ms"))
        assertTrue(text, text.contains("59 min"))
    }

    @Test
    fun a_failure_says_why_rather_than_that_it_failed() {
        val text = lineStanding(LineState.Failed("no answer to REGISTER"), "TLS", "tampa1.voip.ms")
        assertEquals("Not registered: no answer to REGISTER", text)
    }

    @Test
    fun a_line_that_is_working_says_what_it_is_doing() {
        val text = lineStanding(LineState.Preparing("opening TLS to tampa1.voip.ms"), "", "")
        assertEquals("Registering: opening TLS to tampa1.voip.ms", text)
    }

    @Test
    fun no_line_makes_no_claim() {
        assertEquals("Not started.", lineStanding(null, "", ""))
    }
}
