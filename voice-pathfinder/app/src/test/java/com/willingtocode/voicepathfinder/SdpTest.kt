package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {

    private val address = "192.0.2.10"

    @Test
    fun `the control offer asks for pcmu and telephone event`() {
        val offer = Sdp.offer(1, address, 40000, OfferMode.PCMU, cryptoKey = "key")
        assertTrue(offer.startsWith("v=0\r\n"))
        assertTrue(offer.contains("m=audio 40000 RTP/SAVP 0 101\r\n"))
        assertTrue(offer.contains("a=rtpmap:0 PCMU/8000\r\n"))
        assertTrue(offer.contains("a=fmtp:101 0-16\r\n"))
        assertTrue(offer.contains("a=ptime:20\r\n"))
        assertTrue(offer.contains("a=sendrecv\r\n"))
    }

    /**
     * The classic G.722 bug: it is 16 kHz audio with an 8 kHz RTP clock. Writing 16000
     * here produces half-speed audio rather than an error, so it is pinned.
     */
    @Test
    fun `g722 is offered first and keeps the 8000 clock`() {
        val offer = Sdp.offer(1, address, 40000, OfferMode.G722_FIRST, cryptoKey = "key")
        assertTrue(offer.contains("m=audio 40000 RTP/SAVP 9 0 101\r\n"))
        assertTrue(offer.contains("a=rtpmap:9 G722/8000\r\n"))
        assertFalse(offer.contains("G722/16000"))
    }

    /**
     * Offering G.722 first changed nothing, because the provider keeps its own order and
     * normalizes the account's codec list to `ulaw;g729;g722` whatever order is written.
     * The only remaining way to make it choose G.722 is not to offer anything else.
     */
    @Test
    fun `g722 alone is the only way to force it`() {
        val offer = Sdp.offer(1, address, 40000, OfferMode.G722_ONLY, cryptoKey = "key")
        assertTrue(offer.contains("m=audio 40000 RTP/SAVP 9 101\r\n"))
        assertTrue(offer.contains("a=rtpmap:9 G722/8000\r\n"))
        assertFalse(offer.contains("a=rtpmap:0 "))
    }

    /**
     * Every offer is encrypted except the deliberate control, because the account has
     * `sip_traffic` on and refuses plain media with 488 while it is on.
     */
    @Test
    fun `every encrypted mode offers savp with a crypto line`() {
        for (mode in OfferMode.entries.filter { it != OfferMode.PCMU_CLEAR }) {
            val offer = Sdp.offer(1, address, 40000, mode, cryptoKey = "abc123")
            assertTrue("$mode is not encrypted", offer.contains(" RTP/SAVP "))
            assertFalse("$mode still offers plain RTP", offer.contains(" RTP/AVP "))
            assertTrue(
                "$mode has no crypto line",
                offer.contains("a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:abc123\r\n"),
            )
        }
    }

    /**
     * The audio test. No crypto line, plain `RTP/AVP`, and it only works while Encrypted
     * SIP Traffic is off on the sub-account. This is the mode the existing RTP and audio
     * code can actually run against.
     */
    @Test
    fun `the unencrypted control offers plain rtp and no crypto`() {
        val offer = Sdp.offer(1, address, 40000, OfferMode.PCMU_CLEAR, cryptoKey = "abc123")
        assertTrue(offer.contains("m=audio 40000 RTP/AVP 0 101\r\n"))
        assertFalse(offer.contains("a=crypto"))
        assertFalse(offer.contains("RTP/SAVP"))
    }

    @Test
    fun `an answer that picks pcmu is not encrypted`() {
        val answer = Sdp.parse(
            "v=0\r\n" +
                "o=- 1 1 IN IP4 203.0.113.9\r\n" +
                "s=-\r\n" +
                "c=IN IP4 203.0.113.9\r\n" +
                "t=0 0\r\n" +
                "m=audio 41234 RTP/AVP 0 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=ptime:20\r\n",
        )
        assertNotNull(answer)
        requireNotNull(answer)
        assertEquals(Sdp.PROTO_RTP, answer.protocol)
        assertEquals(41234, answer.port)
        assertEquals("203.0.113.9", answer.address)
        assertEquals("PCMU", answer.codec?.encoding)
        assertEquals(20, answer.ptime)
        assertFalse(answer.encrypted)
    }

    @Test
    fun `the first payload type in the answer is the one that was chosen`() {
        val answer = Sdp.parse(
            "v=0\r\n" +
                "c=IN IP4 203.0.113.9\r\n" +
                "m=audio 41234 RTP/AVP 9 0\r\n" +
                "a=rtpmap:9 G722/8000\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n",
        )
        requireNotNull(answer)
        assertEquals("G722", answer.codec?.encoding)
        assertEquals(8000, answer.codec?.clockRate)
    }

    @Test
    fun `savp or a crypto line means the media is encrypted`() {
        val savp = Sdp.parse(
            "v=0\r\n" +
                "c=IN IP4 203.0.113.9\r\n" +
                "m=audio 41234 RTP/SAVP 0\r\n" +
                "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:abc\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n",
        )
        requireNotNull(savp)
        assertTrue(savp.encrypted)
        assertEquals(1, savp.crypto.size)
    }

    @Test
    fun `a media level connection line overrides the session one`() {
        val answer = Sdp.parse(
            "v=0\r\n" +
                "c=IN IP4 10.0.0.1\r\n" +
                "m=audio 41234 RTP/AVP 0\r\n" +
                "c=IN IP4 203.0.113.9\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n",
        )
        requireNotNull(answer)
        assertEquals("203.0.113.9", answer.address)
    }

    @Test
    fun `an answer with no audio stream is rejected`() {
        assertNull(Sdp.parse("v=0\r\nm=video 5004 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"))
        assertNull(Sdp.parse(""))
    }

    @Test
    fun `a rejected stream is reported with port zero`() {
        val answer = Sdp.parse("v=0\r\nc=IN IP4 203.0.113.9\r\nm=audio 0 RTP/AVP 0\r\n")
        requireNotNull(answer)
        assertEquals(0, answer.port)
    }

    @Test
    fun `the srtp key is base64 padded to a multiple of four`() {
        assertEquals("", Sdp.base64(ByteArray(0)))
        assertEquals("AAAA", Sdp.base64(ByteArray(3)))
        assertEquals(40, Sdp.base64(ByteArray(30)).length)
    }

    @Test
    fun `an answer mirrors a clear offer without inventing crypto`() {
        val offer = Sdp.parse(
            "v=0\r\n"+"c=IN IP4 198.51.100.4\r\n"+"m=audio 41000 RTP/AVP 0 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n"+"a=rtpmap:101 telephone-event/8000\r\n",
        )
        assertNotNull(offer)
        val answer = Sdp.answer(offer!!, address, 40000, cryptoKey = "key")
        assertNotNull(answer)
        assertTrue(answer!!.contains("m=audio 40000 RTP/AVP 0 101\r\n"))
        assertTrue(answer.contains("c=IN IP4 $address\r\n"))
        assertTrue(answer.contains("a=ptime:20\r\n"))
        assertFalse(answer.contains("a=crypto"))
    }

    @Test
    fun `an answer to an encrypted offer uses our own key`() {
        val offer = Sdp.parse(
            "v=0\r\n"+"c=IN IP4 198.51.100.4\r\n"+"m=audio 41000 RTP/SAVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:theirKey\r\n",
        )
        assertNotNull(offer)
        val answer = Sdp.answer(offer!!, address, 40000, cryptoKey = "ourKey")
        assertTrue(answer!!.contains("m=audio 40000 RTP/SAVP 0\r\n"))
        assertTrue(answer.contains("a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:ourKey\r\n"))
        // The telephone event was not offered, so it must not be answered: RFC 3264 forbids
        // naming a payload type that the offer did not contain.
        assertFalse(answer.contains("101"))
    }

    @Test
    fun `an offer with no codec this app decodes is refused rather than answered`() {
        val offer = Sdp.parse(
            "v=0\r\n"+"c=IN IP4 198.51.100.4\r\n"+"m=audio 41000 RTP/AVP 9\r\n" +
                "a=rtpmap:9 G722/8000\r\n",
        )
        assertNotNull(offer)
        assertNull(Sdp.answer(offer!!, address, 40000, cryptoKey = "key"))
    }
}
