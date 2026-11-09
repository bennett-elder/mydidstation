package com.willingtocode.voicepathfinder

/**
 * A parsed RTP packet, reduced to the fields this probe uses.
 *
 * RTCP is not modelled. Its packets share the port and the version field, so [parse]
 * returns them and [isRtcp] is how the caller tells the two apart before handing a
 * payload to the jitter buffer.
 */
internal data class RtpPacket(
    val payloadType: Int,
    val marker: Boolean,
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
)

/**
 * RTP framing, with no socket in it.
 *
 * The header is twelve bytes and there is no checksum, which is why sequence arithmetic
 * gets more attention here than the header layout. See [Sequence].
 */
internal object Rtp {

    const val VERSION = 2
    const val HEADER_BYTES = 12

    /** RFC 3550 reserves payload types 200 upward on the media port for RTCP. */
    fun isRtcp(payloadType: Int): Boolean = payloadType in 200..204

    fun build(
        payloadType: Int,
        marker: Boolean,
        sequence: Int,
        timestamp: Long,
        ssrc: Long,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = 0x80.toByte() // version 2, no padding, no extension, no CSRC
        out[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        out[2] = ((sequence shr 8) and 0xFF).toByte()
        out[3] = (sequence and 0xFF).toByte()
        for (i in 0 until 4) out[4 + i] = ((timestamp shr (24 - 8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) out[8 + i] = ((ssrc shr (24 - 8 * i)) and 0xFF).toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    fun parse(bytes: ByteArray, length: Int = bytes.size): RtpPacket? {
        if (length < HEADER_BYTES) return null
        if ((bytes[0].toInt() ushr 6) and 0x03 != VERSION) return null

        val hasPadding = (bytes[0].toInt() and 0x20) != 0
        val hasExtension = (bytes[0].toInt() and 0x10) != 0
        val csrcCount = bytes[0].toInt() and 0x0F

        var start = HEADER_BYTES + csrcCount * 4
        if (start > length) return null
        if (hasExtension) {
            if (start + 4 > length) return null
            val words = ((bytes[start + 2].toInt() and 0xFF) shl 8) or (bytes[start + 3].toInt() and 0xFF)
            start += 4 + words * 4
            if (start > length) return null
        }

        var end = length
        if (hasPadding) {
            val pad = bytes[length - 1].toInt() and 0xFF
            if (pad == 0 || start + pad > end) return null
            end -= pad
        }
        if (end < start) return null

        return RtpPacket(
            payloadType = bytes[1].toInt() and 0x7F,
            marker = (bytes[1].toInt() and 0x80) != 0,
            sequence = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF),
            timestamp = unsignedInt(bytes, 4),
            ssrc = unsignedInt(bytes, 8),
            payload = bytes.copyOfRange(start, end),
        )
    }

    private fun unsignedInt(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}

/**
 * Sequence arithmetic that survives the wrap at 65535.
 *
 * RTP sequence numbers are sixteen bits and wrapped comparisons are the only correct
 * way to order them. `a < b` is wrong exactly once per 65536 packets, so the jitter
 * buffer would reorder or discard a packet every few minutes of a long call. Distance
 * is signed: positive means `a` is newer than `b`.
 */
internal object Sequence {

    fun next(sequence: Int): Int = (sequence + 1) and 0xFFFF

    fun distance(a: Int, b: Int): Int {
        var delta = (a - b) and 0xFFFF
        if (delta >= 0x8000) delta -= 0x10000
        return delta
    }
}
