package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which calls belong on this phone's history.
 *
 * The numbers decide, and only the numbers. A line is a number, and that is what the person
 * chose. The account a record mentions is not evidence about which line a call used, because
 * an account covers every line under it: trusting it kept a dropped line's calls on screen.
 * When a record names none of the chosen numbers it is not shown, and the log says how many
 * were left out so none of them disappear without a word.
 *
 * Every number here is invented, so nothing from a real account can reach the repo.
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
    fun a_call_on_a_number_we_dropped_is_not_ours_even_on_the_same_account() {
        // This is the case that put another line's calls on screen.
        assertFalse(mentionsOurNumber(setOf("5559998888"), setOf("5551234567")))
    }

    private fun did(number: String) =
        Did(number = number, description = "", routing = "account:100000_TestLine", voicemail = "", dialtime = 0)

    @Test
    fun a_row_that_names_our_number_finds_the_line_it_belongs_to() {
        val dids = listOf(did("5551234567"), did("5557654321"))
        assertEquals(listOf("5551234567"), didsForNumbers(dids, setOf("5551234567", "5559998888")))
    }

    @Test
    fun the_same_number_with_a_country_code_finds_the_line_too() {
        val dids = listOf(did("5551234567"))
        assertEquals(listOf("5551234567"), didsForNumbers(dids, setOf("15551234567")))
    }

    @Test
    fun a_row_on_another_number_finds_no_line_of_ours() {
        val dids = listOf(did("5551234567"))
        assertTrue(didsForNumbers(dids, setOf("5559998888")).isEmpty())
        assertTrue(didsForNumbers(dids, emptySet()).isEmpty())
    }

    @Test
    fun a_record_that_names_no_number_is_not_ours() {
        assertFalse(mentionsOurNumber(emptySet(), ours))
    }

    @Test
    fun a_feature_code_alone_does_not_make_a_call_ours() {
        assertFalse(mentionsOurNumber(setOf("97"), ours))
    }

    @Test
    fun nothing_chosen_means_nothing_is_ours() {
        assertFalse(mentionsOurNumber(setOf("5551234567"), emptySet()))
    }
}
