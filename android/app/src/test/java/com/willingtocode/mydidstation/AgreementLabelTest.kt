package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider's verdict, in words and in colour.
 *
 * The point of the mark is that it can disagree with the line's own view, so the case that
 * matters most is the one where it does. Silence is not agreement either: before anyone has
 * asked, the label says so rather than showing a reassuring green.
 */
class AgreementLabelTest {

    @Test
    fun agreement_names_who_is_answering_and_where() {
        val text = agreementLabel(Voice.Agreement.Agrees("pop.example.net", "TLS"))
        assertEquals("provider agrees (TLS, pop.example.net)", text)
    }

    @Test
    fun disagreement_is_stated_not_softened() {
        val text = agreementLabel(Voice.Agreement.Disagrees("the provider has no binding for it"))
        assertEquals("provider: the provider has no binding for it", text)
    }

    @Test
    fun not_asked_yet_is_not_agreement() {
        assertTrue(agreementLabel(null).contains("not asked"))
        assertTrue(agreementLabel(Voice.Agreement.Unknown).contains("not asked"))
    }

    @Test
    fun only_agreement_is_green() {
        val green = agreementColor(Voice.Agreement.Agrees("pop.example.net", "TLS"))
        val red = agreementColor(Voice.Agreement.Disagrees("no binding"))
        val amber = agreementColor(Voice.Agreement.Checking)
        assertTrue(green != red && green != amber && red != amber)
    }
}
