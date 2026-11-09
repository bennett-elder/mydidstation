package com.willingtocode.voicepathfinder

/**
 * G.711 mu-law, the codec VoIP.ms answers with when nothing else is asked for.
 *
 * It is here first because it is the smallest thing that proves the whole audio path:
 * sixteen-bit PCM from `AudioRecord` becomes one byte per sample, fits in an RTP
 * packet, comes back out as PCM for `AudioTrack`. If a call is silent with this codec,
 * the fault is not in a codec.
 *
 * The arithmetic is the standard companding curve, with the sign bit at the top and the
 * value stored inverted so that silence is 0xFF rather than 0x00. That inversion is the
 * detail behind most "loud static instead of silence" implementations, so [encodeSample]
 * and [decodeSample] are pinned by tests against known values.
 */
internal object G711 {

    /** RTP payload type for PCMU. Fixed by RFC 3551, never negotiated. */
    const val PAYLOAD_TYPE = 0

    const val SAMPLE_RATE = 8000
    const val FRAME_MILLIS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MILLIS / 1000

    /** One byte per sample, so a frame is exactly [FRAME_SAMPLES] bytes on the wire. */
    const val FRAME_BYTES = FRAME_SAMPLES

    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(samples: ShortArray, count: Int = samples.size): ByteArray {
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        val out = ByteArray(count)
        for (i in 0 until count) out[i] = encodeSample(samples[i].toInt()).toByte()
        return out
    }

    fun decode(payload: ByteArray): ShortArray {
        val out = ShortArray(payload.size)
        for (i in payload.indices) out[i] = decodeSample(payload[i].toInt() and 0xFF).toShort()
        return out
    }

    internal fun encodeSample(sample: Int): Int {
        var value = sample
        val sign: Int
        if (value < 0) {
            value = -value
            sign = 0x80
        } else {
            sign = 0
        }
        if (value > CLIP) value = CLIP
        value += BIAS
        // Exponent is how far the magnitude sits above 132, in powers of two, and it
        // is what becomes the top bits of the byte. This loop is the table-driven
        // version's table, computed instead of stored.
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (value and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (value shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa) xor 0xFF
    }

    internal fun decodeSample(value: Int): Int {
        val inverted = value.inv() and 0xFF
        val sign = inverted and 0x80
        val exponent = (inverted shr 4) and 0x07
        val mantissa = inverted and 0x0F
        var sample = ((mantissa shl 3) + BIAS) shl exponent
        sample -= BIAS
        return if (sign != 0) -sample else sample
    }

    /**
     * Peak absolute amplitude of a frame, 0..1, for the level meters. The meters are not
     * decoration: on a device with a working microphone and a broken RTP path they are
     * the only thing that says which half is at fault.
     */
    fun peak(samples: ShortArray, count: Int = samples.size): Float {
        var max = 0
        for (i in 0 until count) {
            val value = kotlin.math.abs(samples[i].toInt())
            if (value > max) max = value
        }
        return max / 32767f
    }
}
