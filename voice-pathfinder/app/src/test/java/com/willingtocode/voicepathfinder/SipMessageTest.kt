package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {

    private val response =
        "SIP/2.0 200 OK\r\n" +
            "Via: SIP/2.0/UDP 192.0.2.10:5060;branch=z9hG4bK1;rport\r\n" +
            "From: <sip:100000_VoIP@atlanta.voip.ms>;tag=abc\r\n" +
            "To: <sip:15551234567@atlanta.voip.ms>;tag=def\r\n" +
            "Call-ID: 1234@192.0.2.10\r\n" +
            "CSeq: 42 INVITE\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: 12\r\n" +
            "\r\n" +
            "v=0\r\nt=0 0\r\n"

    @Test
    fun `a response is parsed with its body`() {
        val message = SipMessage.parse(response.toByteArray(Charsets.ISO_8859_1)) ?: error("did not parse")
        assertEquals(200, message.start.status)
        assertEquals("OK", message.start.reason)
        assertEquals("42 INVITE", message.header("cseq"))
        assertEquals("1234@192.0.2.10", message.header("Call-ID"))
        assertEquals("v=0\r\nt=0 0\r\n", message.body)
        assertEquals(42 to "INVITE", SipMessage.cseqOf(message))
    }

    @Test
    fun `a request is parsed`() {
        val request =
            "INVITE sip:15551234567@atlanta.voip.ms SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 192.0.2.10:5060;branch=z9hG4bK2\r\n" +
                "Call-ID: x\r\n" +
                "CSeq: 1 INVITE\r\n" +
                "\r\n"
        val message = SipMessage.parse(request.toByteArray(Charsets.ISO_8859_1)) ?: error("did not parse")
        assertTrue(message.start.isRequest)
        assertEquals("INVITE", message.start.method)
        assertEquals("sip:15551234567@atlanta.voip.ms", message.start.uri)
        assertEquals("z9hG4bK2", SipMessage.branchOf(message.allHeaders("Via").first()))
    }

    @Test
    fun `a folded header is joined`() {
        val message = SipMessage.parse(
            ("SIP/2.0 200 OK\r\n" +
                "Subject: I know\r\n" +
                " you are there\r\n" +
                "Content-Length: 0\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        ) ?: error("did not parse")
        assertEquals("I know you are there", message.header("Subject"))
    }

    @Test
    fun `content length decides where the body ends`() {
        val message = SipMessage.parse(
            ("SIP/2.0 200 OK\r\n" +
                "Content-Length: 4\r\n\r\n" +
                "0123456789").toByteArray(Charsets.ISO_8859_1),
        ) ?: error("did not parse")
        assertEquals("0123", message.body)
    }

    @Test
    fun `serialising recomputes content length`() {
        val message = SipMessage.request(
            method = "INVITE",
            uri = "sip:1@host",
            headers = listOf("Content-Length" to "999", "Content-Type" to "application/sdp"),
            body = "v=0\r\n",
        )
        val text = String(message.toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Length: 5\r\n"))
        assertTrue(!text.contains("999"))
        assertTrue(text.endsWith("\r\n\r\nv=0\r\n"))
    }

    @Test
    fun `a reply echoes the transaction and adds a to tag`() {
        val request = SipMessage.parse(
            ("INVITE sip:1@host SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 192.0.2.10:5060;branch=z9hG4bK9\r\n" +
                "Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK8\r\n" +
                "From: <sip:2@host>;tag=a\r\n" +
                "To: <sip:1@host>\r\n" +
                "Call-ID: call\r\n" +
                "CSeq: 7 INVITE\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        ) ?: error("did not parse")
        val reply = SipMessage.replyingTo(request, 486, "Busy Here", toTag = "zz")
        assertEquals(2, reply.allHeaders("Via").size)
        assertEquals("<sip:1@host>;tag=zz", reply.header("To"))
        assertEquals("7 INVITE", reply.header("CSeq"))
        assertEquals("call", reply.header("Call-ID"))
    }

    @Test
    fun `something that is not a sip message is refused`() {
        assertNull(SipMessage.parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()))
        assertNull(SipMessage.parse(ByteArray(0)))
        assertNotNull(SipMessage.parse("SIP/2.0 100 Trying\r\n\r\n".toByteArray()))
    }
}
