package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * What the call list says when the provider holds nothing.
 *
 * `no_cdr` and an empty list look identical on screen if nobody stops them from being
 * translated into each other. The first means the history could not be read. The second
 * means there was nothing to read. These check that only the second reads as "no calls".
 */
class NoRecordsTest {

    private val window = "2026-11-14 to 2026-11-15, UTC-5"

    @Test
    fun no_cdr_says_the_provider_holds_nothing_for_the_dates_asked() {
        val said = providerHeldNothing(ApiException("no_cdr"), window)
        assertEquals("The provider has no call records for $window.", said)
    }

    @Test
    fun no_cdr_never_reads_as_no_calls() {
        val said = providerHeldNothing(ApiException("no_cdr"), window)
        assertEquals(false, said?.contains("No calls", ignoreCase = true))
        assertEquals(false, said?.contains("no calls", ignoreCase = true))
    }

    @Test
    fun another_refusal_stays_a_fault() {
        assertNull(providerHeldNothing(ApiException("invalid_credentials"), window))
        assertNull(providerHeldNothing(IOException("timeout"), window))
    }
}
