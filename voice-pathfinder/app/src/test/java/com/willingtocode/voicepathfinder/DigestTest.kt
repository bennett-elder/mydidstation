package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    /**
     * The RFC 2617 example, which is the whole point of having this in one place: a
     * digest that is subtly wrong answers 403, and a 403 is indistinguishable from a
     * wrong password without a known-good vector.
     */
    @Test
    fun `the RFC 2617 example response matches`() {
        val challenge = DigestChallenge(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            qop = "auth",
            algorithm = "MD5",
            opaque = null,
        )
        val response = Digest.response(
            challenge = challenge,
            username = "Mufasa",
            password = "Circle Of Life",
            method = "GET",
            uri = "/dir/index.html",
            cnonce = "0a4f113b",
        )
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test
    fun `a challenge is parsed with quotes and a qop list intact`() {
        val parsed = Digest.parse(
            "Digest realm=\"asterisk\", nonce=\"1234abcd\", qop=\"auth,auth-int\", " +
                "algorithm=MD5, opaque=\"xyz\"",
        )
        val challenge = parsed ?: error("did not parse")
        assertEquals("asterisk", challenge.realm)
        assertEquals("1234abcd", challenge.nonce)
        assertEquals("auth", challenge.qop)
        assertEquals("MD5", challenge.algorithm)
        assertEquals("xyz", challenge.opaque)
    }

    @Test
    fun `a non digest challenge and a challenge without a nonce are refused`() {
        assertNull(Digest.parse("Basic realm=\"x\""))
        assertNull(Digest.parse("Digest realm=\"asterisk\""))
        assertNull(Digest.parse(""))
    }

    @Test
    fun `a quoted comma does not split a parameter`() {
        val parameters = Digest.splitParameters("realm=\"a, b\", nonce=n, qop=\"auth\"")
        assertEquals("a, b", parameters["realm"])
        assertEquals("n", parameters["nonce"])
        assertEquals("auth", parameters["qop"])
    }

    @Test
    fun `the header carries what the server asked for`() {
        val challenge = DigestChallenge("asterisk", "abc", "auth", "MD5", "opaque-value")
        val header = Digest.authorization(challenge, "100000_VoIP", "secret", "REGISTER", "sip:atlanta.voip.ms", "c0ffee")
        assertTrue(header.startsWith("Digest "))
        assertTrue(header.contains("username=\"100000_VoIP\""))
        assertTrue(header.contains("uri=\"sip:atlanta.voip.ms\""))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("nc=00000001"))
        assertTrue(header.contains("cnonce=\"c0ffee\""))
        assertTrue(header.contains("opaque=\"opaque-value\""))
        assertTrue(header.contains("response=\""))
    }

    @Test
    fun `without qop the older formula is used`() {
        val challenge = DigestChallenge("realm", "nonce", null, null, null)
        val ha1 = Digest.md5Hex("user:realm:pass")
        val ha2 = Digest.md5Hex("REGISTER:sip:host")
        assertEquals(Digest.md5Hex("$ha1:nonce:$ha2"), Digest.response(challenge, "user", "pass", "REGISTER", "sip:host", "unused"))
    }
}
