package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-dialog BYE.
 *
 * Measured on the handset: a BYE whose `To` header was rebuilt from the contact URI lost the
 * peer's tag and the provider answered `481 Call leg/transaction does not exist`, while the
 * app logged a hang-up. These pin the two headers that decide whether it is accepted.
 */
class ByeRequestTest {

    private val dialog = Call.Dialog(
        remoteTarget = "sip:15551234567@192.0.2.20:5061;transport=TLS",
        from = "<sip:100000_test@sip.example.net>;tag=local1a2b3c",
        to = "sip:15551234567@192.0.2.20:5061;transport=TLS;tag=peer9f8e7d",
        callId = "0a1b2c3d4e5f@sip.example.net",
        cseq = 101,
    )

    private fun call(): Call = Call(
        credentials = SipCredentials(
            host = "sip.example.net",
            port = 5061,
            username = "100000_test",
            password = "secret",
            expiresSeconds = 3600,
            label = "",
            codecs = "ulaw",
            tls = true,
        ),
        media = MediaSession(note = { }),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        note = { },
    )

    private fun headers() = call()
        .byeHeaders(dialog, "SIP/2.0/TLS 192.0.2.10:5060;branch=z9hG4bKabc")
        .toMap()

    @Test
    fun `the peer's tag survives into the bye`() {
        assertTrue(headers()["To"].orEmpty().endsWith(";tag=peer9f8e7d"))
    }

    @Test
    fun `the from tag is the one this call used`() {
        assertEquals(dialog.from, headers()["From"])
    }

    @Test
    fun `the bye carries the next cseq in the dialog`() {
        assertEquals("102 BYE", headers()["CSeq"])
    }

    @Test
    fun `the call id is the dialog's own`() {
        assertEquals(dialog.callId, headers()["Call-ID"])
    }
}
