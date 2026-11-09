package com.willingtocode.voicepathfinder

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SRTP with the single suite VoIP.ms offers: `AES_CM_128_HMAC_SHA1_80`.
 *
 * The keys arrive in the SDP as an `a=crypto` line (SDES, RFC 4568), thirty bytes of
 * master key and salt in base64, one pair each way. Observed on a real call on
 * 2026-11-09: the answer carried its own key and the media port was `RTP/SAVP`.
 *
 * Only the payload is encrypted. The twelve byte RTP header travels in the clear because
 * the receiver needs the sequence number and SSRC to derive the keystream, and the
 * authentication tag covers the whole packet including that header. Bending either rule
 * produces a stream the far end silently discards, which is the worst class of bug here:
 * no error, just silence.
 *
 * The primitive below is checked against RFC 3711 Appendix B, both the key derivation and
 * the counter mode keystream including the blocks at counter 0xfeff and up. The packet
 * format has no published vector, so it is pinned by round trips instead.
 */
internal object Srtp {

    const val SUITE = "AES_CM_128_HMAC_SHA1_80"

    /** Truncated HMAC-SHA1, the 80 in the suite name. */
    const val TAG_BYTES = 10

    const val KEY_BYTES = 16
    const val SALT_BYTES = 14

    /** Thirty bytes of `inline:` material: key then salt. */
    const val INLINE_BYTES = KEY_BYTES + SALT_BYTES

    /**
     * Derives a session key from the master key and salt, RFC 3711 section 4.3.3.
     *
     * With a derivation rate of zero, which is what SDES gives us, the input is the master
     * salt with the label exclusive-ored into its eighth byte, then two zero octets,
     * encrypted as a counter block. The label sits at byte 7 because the six byte index
     * occupies bytes 8 to 13 and the label immediately precedes it.
     */
    fun derive(masterKey: ByteArray, masterSalt: ByteArray, label: Int, length: Int): ByteArray {
        val iv = ByteArray(16)
        masterSalt.copyInto(iv, 0, 0, minOf(masterSalt.size, SALT_BYTES))
        iv[7] = (iv[7].toInt() xor label).toByte()
        return counterMode(masterKey, iv, length)
    }

    /**
     * The keystream for one packet, RFC 3711 section 4.1.1.
     *
     * IV = (salt * 2^16) XOR (SSRC * 2^64) XOR (index * 2^16), so the salt occupies bytes
     * 0 to 13, the SSRC bytes 4 to 7 and the 48 bit index bytes 8 to 13. The low sixteen
     * bits are the block counter, which caps a packet at 2^16 blocks.
     */
    fun keystream(
        sessionKey: ByteArray,
        sessionSalt: ByteArray,
        ssrc: Long,
        index: Long,
        length: Int,
    ): ByteArray {
        val iv = ByteArray(16)
        sessionSalt.copyInto(iv, 0, 0, minOf(sessionSalt.size, SALT_BYTES))
        for (i in 0 until 4) {
            val byte = ((ssrc ushr (24 - 8 * i)) and 0xFF).toInt()
            iv[4 + i] = (iv[4 + i].toInt() xor byte).toByte()
        }
        for (i in 0 until 6) {
            val byte = ((index ushr (40 - 8 * i)) and 0xFF).toInt()
            iv[8 + i] = (iv[8 + i].toInt() xor byte).toByte()
        }
        iv[14] = 0
        iv[15] = 0
        return counterMode(sessionKey, iv, length)
    }

    /** AES counter mode over a sixteen bit counter, which is what RFC 3711 specifies. */
    private fun counterMode(key: ByteArray, baseIv: ByteArray, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val iv = baseIv.copyOf(16)
        val out = ByteArray(length)
        var position = 0
        var counter = 0
        while (position < length) {
            iv[14] = ((counter ushr 8) and 0xFF).toByte()
            iv[15] = (counter and 0xFF).toByte()
            val block = cipher.doFinal(iv)
            val take = minOf(16, length - position)
            block.copyInto(out, position, 0, take)
            position += take
            counter = (counter + 1) and 0xFFFF
        }
        return out
    }
}

/** The three session keys a master key and salt produce, derived once per call. */
internal class SrtpKey(masterKey: ByteArray, masterSalt: ByteArray) {

    init {
        require(masterKey.size == Srtp.KEY_BYTES) {
            "an SRTP master key is ${Srtp.KEY_BYTES} bytes, not ${masterKey.size}"
        }
        require(masterSalt.size == Srtp.SALT_BYTES) {
            "an SRTP master salt is ${Srtp.SALT_BYTES} bytes, not ${masterSalt.size}"
        }
    }

    val cipherKey: ByteArray = Srtp.derive(masterKey, masterSalt, 0x00, Srtp.KEY_BYTES)
    val salt: ByteArray = Srtp.derive(masterKey, masterSalt, 0x02, Srtp.SALT_BYTES)
    val authKey: ByteArray = Srtp.derive(masterKey, masterSalt, 0x01, 20)

    companion object {
        /** The thirty bytes of an `inline:` key: master key then master salt. */
        fun fromInline(bytes: ByteArray): SrtpKey {
            require(bytes.size == Srtp.INLINE_BYTES) {
                "an inline SRTP key is ${Srtp.INLINE_BYTES} bytes, not ${bytes.size}"
            }
            return SrtpKey(
                bytes.copyOfRange(0, Srtp.KEY_BYTES),
                bytes.copyOfRange(Srtp.KEY_BYTES, Srtp.INLINE_BYTES),
            )
        }
    }
}

/**
 * One direction of SRTP.
 *
 * Rollover counters are tracked per direction as RFC 3711 section 3.3 describes, because
 * the wire carries only sixteen bits of sequence number while the keystream depends on
 * the full 48 bit index. Guessing wrong once corrupts everything after it with no error
 * anywhere, so the guess is conservative: a jump of more than half the sequence space
 * backwards is a wrap, anything else is a late packet from the current epoch.
 */
internal class SrtpContext(private val key: SrtpKey) {

    private var sendRoc = 0L
    private var lastSent = -1

    private var receiveRoc = 0L
    private var highestReceived = -1

    /** Packets dropped because the tag did not match or the shape was wrong. */
    var rejected = 0
        private set

    /** Encrypts the payload and appends the tag. The header is left exactly as it was. */
    fun protect(packet: ByteArray): ByteArray {
        val headerBytes = headerLength(packet, packet.size)
        require(headerBytes in 12 until packet.size) {
            "an RTP packet needs a twelve byte header and at least one payload byte"
        }
        val sequence = sequenceOf(packet)
        if (lastSent >= 0 && sequence < lastSent && lastSent - sequence > HALF_SPACE) sendRoc++
        lastSent = sequence
        val index = (sendRoc shl 16) or sequence.toLong()

        val payloadBytes = packet.size - headerBytes
        val stream = Srtp.keystream(key.cipherKey, key.salt, ssrcOf(packet), index, payloadBytes)

        val out = ByteArray(packet.size + Srtp.TAG_BYTES)
        packet.copyInto(out, 0, 0, headerBytes)
        for (i in 0 until payloadBytes) {
            out[headerBytes + i] = (packet[headerBytes + i].toInt() xor stream[i].toInt()).toByte()
        }
        tagOf(out, 0, packet.size, sendRoc).copyInto(out, packet.size, 0, Srtp.TAG_BYTES)
        return out
    }

    /**
     * Verifies and decrypts, or returns null.
     *
     * The tag is checked before anything is decrypted, so a forged or corrupted packet
     * never reaches the jitter buffer. Null means drop it, which is the only correct
     * response to a failed authentication.
     */
    fun unprotect(bytes: ByteArray, length: Int = bytes.size): ByteArray? {
        if (length < 12 + Srtp.TAG_BYTES) return null
        val packetBytes = length - Srtp.TAG_BYTES
        val headerBytes = try {
            headerLength(bytes, packetBytes)
        } catch (fault: IllegalArgumentException) {
            rejected++
            return null
        }
        if (headerBytes >= packetBytes) return null

        val sequence = sequenceOf(bytes)
        // The ROC is part of the authenticated data, so it has to be known before the tag
        // can be checked. It is only committed once the packet authenticates, or a forged
        // packet could walk the counter forward and poison everything after it.
        val candidateRoc = estimateRoc(sequence)
        val expected = tagOf(bytes, 0, packetBytes, candidateRoc)
        if (!MessageDigest.isEqual(expected, bytes.copyOfRange(packetBytes, length))) {
            rejected++
            return null
        }
        commitReceived(sequence)
        val index = (receiveRoc shl 16) or sequence.toLong()

        val payloadBytes = packetBytes - headerBytes
        val stream = Srtp.keystream(key.cipherKey, key.salt, ssrcOf(bytes), index, payloadBytes)
        val out = ByteArray(packetBytes)
        bytes.copyInto(out, 0, 0, headerBytes)
        for (i in 0 until payloadBytes) {
            out[headerBytes + i] = (bytes[headerBytes + i].toInt() xor stream[i].toInt()).toByte()
        }
        return out
    }

    /**
     * The tag over the authenticated portion plus the rollover counter.
     *
     * RFC 3711 section 4.2: "In the case of SRTP, M SHALL consist of the Authenticated
     * Portion of the packet concatenated with the ROC, M = Authenticated Portion || ROC."
     * SRTCP is the exception and omits it. Leaving the ROC out produces a tag that a
     * standard receiver rejects on every packet, with no error anywhere: the far end just
     * stops sending, which looks exactly like a dead network.
     */
    private fun tagOf(bytes: ByteArray, from: Int, to: Int, roc: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.authKey, "HmacSHA1"))
        mac.update(bytes, from, to - from)
        mac.update(
            byteArrayOf(
                ((roc ushr 24) and 0xFF).toByte(),
                ((roc ushr 16) and 0xFF).toByte(),
                ((roc ushr 8) and 0xFF).toByte(),
                (roc and 0xFF).toByte(),
            ),
        )
        return mac.doFinal().copyOf(Srtp.TAG_BYTES)
    }

    private fun sequenceOf(bytes: ByteArray): Int =
        ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

    /** The rollover counter this sequence implies, without committing to it. */
    private fun estimateRoc(sequence: Int): Long {
        if (highestReceived < 0) return receiveRoc
        val advance = (sequence - highestReceived) and 0xFFFF
        if (advance >= HALF_SPACE) return receiveRoc
        return if (sequence < highestReceived) receiveRoc + 1 else receiveRoc
    }

    /** Records a packet that authenticated, moving the epoch if the sequence wrapped. */
    private fun commitReceived(sequence: Int) {
        if (highestReceived < 0) {
            highestReceived = sequence
            return
        }
        val advance = (sequence - highestReceived) and 0xFFFF
        if (advance < HALF_SPACE) {
            if (sequence < highestReceived) receiveRoc++
            highestReceived = sequence
        }
    }

    private fun ssrcOf(bytes: ByteArray): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[8 + i].toLong() and 0xFF)
        return value
    }

    /** Twelve bytes, plus any contributing sources and one extension header if present. */
    private fun headerLength(bytes: ByteArray, limit: Int): Int {
        if (limit < 12) throw IllegalArgumentException("short RTP header")
        var length = 12 + (bytes[0].toInt() and 0x0F) * 4
        if ((bytes[0].toInt() and 0x10) != 0) {
            if (length + 4 > limit) throw IllegalArgumentException("short RTP extension header")
            val words = ((bytes[length + 2].toInt() and 0xFF) shl 8) or
                (bytes[length + 3].toInt() and 0xFF)
            length += 4 + words * 4
        }
        if (length > limit) throw IllegalArgumentException("RTP header runs past the packet")
        return length
    }

    private companion object {
        /** More than half the sequence space backwards means the counter wrapped. */
        const val HALF_SPACE = 0x8000
    }
}
