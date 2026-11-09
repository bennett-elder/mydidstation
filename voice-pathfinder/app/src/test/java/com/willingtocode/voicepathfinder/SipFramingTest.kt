package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where one message ends on a stream.
 *
 * This is the failure that would be hardest to see from a handset: a truncated message
 * parses into null and vanishes, and two messages delivered in one read parse into one,
 * so a registration response could be silently swallowed by a response to something
 * else. Every case below is a shape a real socket produces.
 */
class SipFramingTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private val response = bytes(
        "SIP/2.0 200 OK\r\n" +
            "CSeq: 1 REGISTER\r\n" +
            "Content-Length: 2\r\n" +
            "\r\n" +
            "hi",
    )

    @Test
    fun `a whole message is measured with its body`() {
        assertEquals(Frame.Message(0, response.size), SipFraming.next(response, response.size))
    }

    @Test
    fun `two messages in one read yield the first`() {
        val both = response + bytes("SIP/2.0 100 Trying\r\nContent-Length: 0\r\n\r\n")
        assertEquals(Frame.Message(0, response.size), SipFraming.next(both, both.size))
    }

    @Test
    fun `a header without content length ends at the blank line`() {
        val text = bytes("SIP/2.0 100 Trying\r\nCSeq: 1 INVITE\r\n\r\n")
        assertEquals(Frame.Message(0, text.size), SipFraming.next(text, text.size))
    }

    @Test
    fun `an incomplete header waits for more`() {
        val text = bytes("SIP/2.0 200 OK\r\nCSeq: 1 REG")
        assertEquals(Frame.Incomplete, SipFraming.next(text, text.size))
    }

    @Test
    fun `an incomplete body waits for more`() {
        val text = bytes("SIP/2.0 200 OK\r\nContent-Length: 10\r\n\r\nshort")
        assertEquals(Frame.Incomplete, SipFraming.next(text, text.size))
    }

    @Test
    fun `keepalive crlfs are reported so they can be dropped`() {
        val ping = bytes("\r\n\r\n")
        assertEquals(Frame.Keepalive(4), SipFraming.next(ping, ping.size))

        val pingThenMessage = ping + response
        assertEquals(
            Frame.Message(4, response.size),
            SipFraming.next(pingThenMessage, pingThenMessage.size),
        )
    }

    @Test
    fun `an empty read is incomplete rather than a message`() {
        assertEquals(Frame.Incomplete, SipFraming.next(ByteArray(0), 0))
    }
}
