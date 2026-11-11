package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tag that puts a request inside its dialog.
 *
 * Measured on 2026-11-10: five BYEs went out for one hang-up and the provider answered none
 * of them, because the From header was copied from the INVITE's To, which has no tag. The
 * caller's call kept running while the app showed it ended.
 */
class DialogTagTest {

    @Test
    fun a_header_without_a_tag_gains_ours() {
        val to = "<sip:100000_XYZ@203.0.113.10:5060;transport=tls>"
        assertEquals("$to;tag=0000", withLocalTag(to, "0000"))
    }

    @Test
    fun a_header_that_already_has_one_is_left_alone() {
        val from = "\"EXAMPLE CALLER\" <sip:15550001111@198.51.100.20>;tag=as00000000"
        assertEquals(from, withLocalTag(from, "0000"))
    }
}
