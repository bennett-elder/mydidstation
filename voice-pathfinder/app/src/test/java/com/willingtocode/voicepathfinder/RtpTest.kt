package com.willingtocode.voicepathfinder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpTest {

    @Test
    fun `build and parse round trip`() {
        val payload = ByteArray(160) { it.toByte() }
        val bytes = Rtp.build(
            payloadType = 0,
            marker = true,
            sequence = 65535,
            timestamp = 0xFFFFFFFFL,
            ssrc = 0xDEADBEEFL,
            payload = payload,
        )
        val packet = Rtp.parse(bytes) ?: error("did not parse")
        assertEquals(0, packet.payloadType)
        assertTrue(packet.marker)
        assertEquals(65535, packet.sequence)
        assertEquals(0xFFFFFFFFL, packet.timestamp)
        assertEquals(0xDEADBEEFL, packet.ssrc)
        assertArrayEquals(payload, packet.payload)
        assertEquals(Rtp.HEADER_BYTES + payload.size, bytes.size)
    }

    @Test
    fun `a packet from another version is rejected`() {
        val bytes = Rtp.build(0, false, 1, 0, 1, ByteArray(4))
        bytes[0] = 0x40 // version 1
        assertNull(Rtp.parse(bytes))
        assertNull(Rtp.parse(ByteArray(8)))
    }

    @Test
    fun `padding is stripped from the payload`() {
        val base = Rtp.build(0, false, 1, 2, 3, byteArrayOf(1, 2, 3, 4))
        val padded = ByteArray(base.size + 2)
        base.copyInto(padded)
        padded[0] = (padded[0].toInt() or 0x20).toByte()
        padded[padded.size - 1] = 2
        val packet = Rtp.parse(padded) ?: error("did not parse")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packet.payload)
    }

    @Test
    fun `a header extension is skipped`() {
        val bytes = ByteArray(24)
        bytes[0] = 0x90.toByte() // version 2, extension bit
        bytes[2] = 0
        bytes[3] = 7
        bytes[14] = 0
        bytes[15] = 1 // one 32-bit extension word
        bytes[20] = 9
        bytes[21] = 8
        bytes[22] = 7
        bytes[23] = 6
        val packet = Rtp.parse(bytes) ?: error("did not parse")
        assertEquals(7, packet.sequence)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), packet.payload)
    }

    @Test
    fun `rtcp shares the port and is told apart by payload type`() {
        assertFalse(Rtp.isRtcp(0))
        assertFalse(Rtp.isRtcp(101))
        assertTrue(Rtp.isRtcp(200))
        assertTrue(Rtp.isRtcp(204))
    }

    @Test
    fun `sequence arithmetic survives the wrap`() {
        assertEquals(0, Sequence.next(65535))
        assertEquals(1, Sequence.distance(0, 65535))
        assertEquals(-1, Sequence.distance(65535, 0))
        assertEquals(0, Sequence.distance(5, 5))
        assertEquals(3, Sequence.distance(2, 65535))
    }
}
