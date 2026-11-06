package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinksTest {

    private fun found(body: String) = TextLinks.find(body).map { body.substring(it) }

    @Test
    fun `finds a plain http link`() {
        assertEquals(listOf("http://example.com/a"), found("go to http://example.com/a now"))
    }

    @Test
    fun `finds https and stops at whitespace`() {
        assertEquals(listOf("https://example.com/x?y=1"), found("https://example.com/x?y=1 then"))
    }

    @Test
    fun `drops a trailing full stop`() {
        assertEquals(listOf("https://example.com/a"), found("see https://example.com/a."))
    }

    @Test
    fun `keeps a balanced closing bracket`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            found("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `drops a wrapping closing bracket`() {
        assertEquals(listOf("https://example.com/a"), found("(https://example.com/a)"))
    }

    @Test
    fun `a bare www host is assumed to be https`() {
        val ranges = TextLinks.find("www.example.com/page is the one")
        assertEquals(1, ranges.size)
        assertEquals("https://www.example.com/page", TextLinks.toUrl("www.example.com/page"))
    }

    @Test
    fun `finds several links in one body`() {
        assertEquals(
            listOf("http://a.example", "https://b.example"),
            found("http://a.example and https://b.example"),
        )
    }

    @Test
    fun `leaves phone numbers and plain text alone`() {
        assertTrue(TextLinks.find("call 5551234567 or 555.987.6543").isEmpty())
        assertTrue(TextLinks.find("no links here at all").isEmpty())
        assertTrue(TextLinks.find("").isEmpty())
    }

    @Test
    fun `does not match a scheme glued to a word`() {
        // \b is what stops "xhttps://" from matching half a word.
        assertTrue(TextLinks.find("xhttps://example.com").isEmpty())
    }
}
