package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding the provider's per-leg records into one row per call.
 *
 * Measured on the account: a single call arrives as `Outbound - Customer` and
 * `Inbound - Local Caller` with the same timestamp and duration, so the raw list makes one
 * call look like two.
 */
class CallLegsTest {

    private fun leg(
        date: String,
        destination: String,
        description: String,
        seconds: Int,
        account: String = "100000_VoIP",
        callerId: String = "",
    ) = CallRecord(
        date = date,
        destination = destination,
        disposition = "ANSWERED",
        description = description,
        seconds = seconds,
        logs = "",
        account = account,
        callerId = callerId,
    )

    @Test
    fun `two legs of one call become one row`() {
        val rows = collapseLegs(
            listOf(
                leg("2026-11-10 08:48:29", "15559876543", "Outbound - Customer", 4),
                leg("2026-11-10 08:48:29", "15551234567", "Inbound - Local Caller", 4),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("the outbound leg should name the dialled number", "5559876543", rows.first().destination)
        assertEquals("Outbound", rows.first().direction)
        assertEquals(4, rows.first().seconds)
    }

    @Test
    fun `an inbound call with no caller id shows nothing rather than our own number`() {
        val rows = collapseLegs(
            listOf(leg("2026-11-09 16:36:58", "15551234567", "Inbound - Local Caller", 7)),
        )
        assertEquals(1, rows.size)
        assertEquals("Inbound", rows.first().direction)
        // The destination of an inbound call is our own number, so borrowing it would name a
        // caller who never called. Unknown stays unknown, and the row says so in words.
        assertEquals("", rows.first().destination)
    }

    @Test
    fun `an inbound call names the caller when the record has one`() {
        val rows = collapseLegs(
            listOf(
                leg(
                    "2026-11-09 16:36:58",
                    "15551234567",
                    "Inbound - Local Caller",
                    7,
                    callerId = "\"Someone\" <15559876543>",
                ),
            ),
        )
        assertEquals("5559876543", rows.first().destination)
    }

    @Test
    fun `separate calls stay separate`() {
        val rows = collapseLegs(
            listOf(
                leg("2026-11-10 08:48:29", "15559876543", "Outbound - Customer", 4),
                leg("2026-11-10 08:48:29", "15551234567", "Inbound - Local Caller", 4),
                leg("2026-11-10 08:41:52", "15559876543", "Outbound - Customer", 20),
                leg("2026-11-10 08:41:52", "15551234567", "Inbound - Local Caller", 20),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(listOf(4, 20), rows.map { it.seconds })
    }

    @Test
    fun `a leading country code is dropped once`() {
        val rows = collapseLegs(
            listOf(leg("2026-11-10 08:48:29", "15559876543", "Outbound - Customer", 4)),
        )
        assertEquals("5559876543", rows.first().destination)
    }

    @Test
    fun `a row keeps the sub-account the provider reported`() {
        val rows = collapseLegs(
            listOf(leg("2026-11-10 08:48:29", "15559876543", "Outbound - Customer", 4, "100000_one")),
        )
        assertEquals("the row must carry the account a filter matches on", "100000_one", rows.first().account)
    }

    @Test
    fun `an inbound call shows the caller, not our own number`() {
        val rows = collapseLegs(
            listOf(
                leg(
                    date = "2026-11-09 16:36:58",
                    destination = "15551234567",
                    description = "Inbound - Local Caller",
                    seconds = 7,
                    callerId = "\"Jane Doe\" <15551234567>",
                ),
            ),
        )
        assertEquals("Inbound", rows.first().direction)
        assertEquals("the row should name the caller", "5551234567", rows.first().destination)
    }

    @Test
    fun `a caller id without brackets is reduced to digits`() {
        assertEquals("5551234567", callerNumber("5551234567"))
        assertEquals("+15551234567", callerNumber("+15551234567"))
        assertEquals("", callerNumber(""))
    }

    @Test
    fun `a row knows every account its legs mention`() {
        val rows = collapseLegs(
            listOf(
                leg("2026-11-10 08:48:29", "15551230000", "Outbound - Customer", 30, account = "100000_out"),
                leg("2026-11-10 08:48:29", "15551234567", "Inbound - Local Caller", 30, account = "100000_in"),
            ),
        )
        // The filter has to match on either leg, or an inbound call cannot be found by the
        // line it arrived on.
        assertTrue("100000_in" in rows.first().accounts)
        assertTrue("100000_out" in rows.first().accounts)
    }
}