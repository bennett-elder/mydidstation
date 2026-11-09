package com.willingtocode.voicepathfinder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The numbers here are from RFC 3711 Appendix B, not from this implementation. Without
 * them an SRTP layer is a guess that round trips against itself and fails against every
 * real endpoint, with silence as the only symptom.
 */
class SrtpTest {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    /** Appendix B.3, the master key and salt the derivation examples use. */
    private val masterKey = hex("E1F97A0D3E018BE0D64FA32C06DE4139")
    private val masterSalt = hex("0EC675AD498AFEEBB6960B3AABE6")

    private val inline = masterKey + masterSalt

    @Test
    fun `the cipher key matches RFC 3711 B_3`() {
        assertEquals(
            "C61E7A93744F39EE10734AFE3FF7A087",
            Srtp.derive(masterKey, masterSalt, 0x00, 16).hex(),
        )
    }

    @Test
    fun `the salt matches RFC 3711 B_3`() {
        assertEquals(
            "30CBBC08863D8C85D49DB34A9AE1",
            Srtp.derive(masterKey, masterSalt, 0x02, 14).hex(),
        )
    }

    /**
     * The published auth key is 94 octets and the vectors list its first three blocks.
     * Twenty octets is the whole of what HMAC-SHA1 uses, so the first 20 are pinned.
     */
    @Test
    fun `the auth key matches RFC 3711 B_3`() {
        assertEquals(
            "CEBE321F6FF7716B6FD4AB49AF256A156D38BAA4",
            Srtp.derive(masterKey, masterSalt, 0x01, 20).hex(),
        )
    }

    @Test
    fun `a session key bundle is derived from the inline bytes`() {
        val key = SrtpKey.fromInline(inline)
        assertEquals("C61E7A93744F39EE10734AFE3FF7A087", key.cipherKey.hex())
        assertEquals("30CBBC08863D8C85D49DB34A9AE1", key.salt.hex())
        assertEquals("CEBE321F6FF7716B6FD4AB49AF256A156D38BAA4", key.authKey.hex())
    }

    @Test
    fun `the keystream matches RFC 3711 B_2 at the start`() {
        val sessionKey = hex("2B7E151628AED2A6ABF7158809CF4F3C")
        val sessionSalt = hex("F0F1F2F3F4F5F6F7F8F9FAFBFCFD") // already shifted, per B.2
        val stream = Srtp.keystream(sessionKey, sessionSalt, ssrc = 0, index = 0, length = 48)
        assertEquals(
            "E03EAD0935C95E80E166B16DD92B4EB4" +
                "D23513162B02D0F72A43A2FE4A5F97AB" +
                "41E95B3BB0A2E8DD477901E4FCA894C0",
            stream.hex(),
        )
    }

    /**
     * The end of the published segment, which is the part a sixteen bit counter gets wrong
     * if it is written as an eight bit one. Counter 0xfeff, 0xff00 and 0xff01.
     */
    @Test
    fun `the keystream matches RFC 3711 B_2 at the top of the counter`() {
        val sessionKey = hex("2B7E151628AED2A6ABF7158809CF4F3C")
        val sessionSalt = hex("F0F1F2F3F4F5F6F7F8F9FAFBFCFD")
        val stream = Srtp.keystream(sessionKey, sessionSalt, 0, 0, 16 * 0xFF02)
        fun block(number: Int): String =
            stream.copyOfRange(number * 16, number * 16 + 16).hex()
        assertEquals("EC8CDF7398607CB0F2D21675EA9EA1E4", block(0xFEFF))
        assertEquals("362B7C3C6773516318A077D7FC5073AE", block(0xFF00))
        assertEquals("6A2CC3787889374FBEB4C81B17BA6C44", block(0xFF01))
    }

    // ------------------------------------------------------------------------ packets

    private fun packet(sequence: Int, payload: ByteArray = ByteArray(160) { (it * 7).toByte() }) =
        Rtp.build(
            payloadType = G711.PAYLOAD_TYPE,
            marker = false,
            sequence = sequence,
            timestamp = sequence * 160L,
            ssrc = 0x0BADC0DEL,
            payload = payload,
        )

    @Test
    fun `a protected packet round trips and its header stays in the clear`() {
        val key = SrtpKey.fromInline(inline)
        val clear = packet(1234)
        val protected = SrtpContext(key).protect(clear)

        assertEquals(clear.size + Srtp.TAG_BYTES, protected.size)
        assertArrayEquals(
            clear.copyOfRange(0, Rtp.HEADER_BYTES),
            protected.copyOfRange(0, Rtp.HEADER_BYTES),
        )
        // The payload itself must not survive in the clear, or nothing is encrypted.
        assertFalse(
            clear.copyOfRange(Rtp.HEADER_BYTES, clear.size)
                .contentEquals(protected.copyOfRange(Rtp.HEADER_BYTES, clear.size)),
        )

        val received = SrtpContext(key).unprotect(protected)
        assertNotNull(received)
        requireNotNull(received)
        assertArrayEquals(clear, received)
    }

    @Test
    fun `a flipped bit anywhere is dropped rather than decrypted`() {
        val key = SrtpKey.fromInline(inline)
        val protected = SrtpContext(key).protect(packet(7))
        for (at in intArrayOf(protected.size - 1, protected.size - 10, 14, 0)) {
            val damaged = protected.copyOf()
            damaged[at] = (damaged[at].toInt() xor 0x01).toByte()
            assertNull("byte $at was accepted after tampering", SrtpContext(key).unprotect(damaged))
        }
    }

    @Test
    fun `the other direction cannot read it`() {
        val sender = SrtpContext(SrtpKey.fromInline(inline))
        val receiver = SrtpContext(SrtpKey.fromInline(ByteArray(30) { (it + 1).toByte() }))
        assertNull(receiver.unprotect(sender.protect(packet(9))))
    }

    @Test
    fun `a packet with no payload or a truncated tag is refused`() {
        val key = SrtpKey.fromInline(inline)
        val context = SrtpContext(key)
        assertNull(context.unprotect(ByteArray(8)))
        assertNull(context.unprotect(ByteArray(Rtp.HEADER_BYTES + Srtp.TAG_BYTES - 1)))
    }

    /**
     * The heart of SRTP bookkeeping. After the sequence wraps, the index moves to the next
     * rollover counter, and both ends have to agree on that without ever sending it.
     *
     * The ROC is part of the authenticated data (RFC 3711 section 4.2), so the two ends
     * cannot silently disagree: a receiver whose counter is an epoch behind computes a
     * different tag and drops the packet. An earlier version of this layer left the ROC out
     * of the tag and this test asserted the opposite, that the packet decrypts into
     * garbage, which was only ever true of that bug.
     */
    @Test
    fun `the rollover counter follows the sequence number past the wrap`() {
        val key = SrtpKey.fromInline(inline)
        val sender = SrtpContext(key)
        val receiver = SrtpContext(key)

        val last = packet(65535)
        val first = packet(0)
        val sentLast = sender.protect(last)
        val sentFirst = sender.protect(first)

        assertArrayEquals(last, receiver.unprotect(sentLast))
        assertArrayEquals(first, receiver.unprotect(sentFirst))

        // A sender still at rollover counter zero is an epoch behind the receiver, and the
        // tag says so rather than letting the packet through as noise.
        val freshSender = SrtpContext(key)
        assertNull(receiver.unprotect(freshSender.protect(packet(1))))
    }

    @Test
    fun `a late packet from the same epoch still decrypts`() {
        val key = SrtpKey.fromInline(inline)
        val sender = SrtpContext(key)
        val receiver = SrtpContext(key)
        val early = packet(100)
        val late = packet(101)
        val sentEarly = sender.protect(early)
        val sentLate = sender.protect(late)
        assertArrayEquals(late, receiver.unprotect(sentLate))
        assertArrayEquals(early, receiver.unprotect(sentEarly))
    }
}
