package com.willingtocode.voicepathfinder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which calls belong to this phone.
 *
 * A line is a number, so the number decides. The record names it twice: as the destination
 * of an inbound call, and in the caller ID of an outbound one. Matching on the account name
 * instead dropped three calls that were in the portal and on the phone at once, which read as
 * an empty history. The account stays as a fallback for a row that names no number.
 *
 * Every number and account here is invented, so nothing from a real account can reach the repo.
 */
class LineMatchTest {

    private val ours = setOf("5551234567")

    @Test
    fun a_call_taken_on_our_number_is_ours() {
        assertTrue(mentionsOurNumber(setOf("5551234567", "5559998888"), ours))
    }

    @Test
    fun the_same_number_written_with_a_country_code_is_ours() {
        assertTrue(mentionsOurNumber(setOf("15551234567"), ours))
    }

    @Test
    fun brackets_and_spaces_do_not_decide_it() {
        assertTrue(mentionsOurNumber(setOf("(555) 123-4567"), ours))
    }

    @Test
    fun a_call_between_two_other_numbers_is_not_ours() {
        assertFalse(mentionsOurNumber(setOf("5559998888", "5557776666"), ours))
    }

    @Test
    fun no_numbers_at_all_is_not_ours() {
        assertFalse(mentionsOurNumber(emptySet(), ours))
    }

    @Test
    fun the_account_still_finds_a_row_that_names_the_account_alone() {
        assertTrue(belongsTo("100000", setOf("100000_TestLine")))
    }

    @Test
    fun the_account_does_not_claim_another_accounts_row() {
        assertFalse(belongsTo("200000", setOf("100000_TestLine")))
        assertFalse(belongsTo("1000001", setOf("100000_TestLine")))
        assertFalse(belongsTo("", setOf("100000_TestLine")))
    }
}
