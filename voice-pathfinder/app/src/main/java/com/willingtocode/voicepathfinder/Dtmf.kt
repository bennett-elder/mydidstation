package com.willingtocode.voicepathfinder

import kotlin.math.PI
import kotlin.math.sin

/**
 * DTMF as RFC 4733, which is what the SDP already offers as `telephone-event`.
 *
 * In-band tones would be the alternative, and they are what the PSTN expects from a legacy
 * handset, but a G.711 tone can be mangled by any transcoding on the provider's side. The
 * event payload is explicit: the digit, how long it lasted, and a marker bit saying it ended.
 */
internal object Dtmf {

    /** 20 ms of a 8 kHz stream, the step each packet advances. */
    const val STEP_SAMPLES = 160

    /** Five steps is 100 ms, the duration most IVRs are tuned for. */
    const val STEPS = 5

    /** 0 to 63, and 10 is the conventional default. */
    const val VOLUME = 10

    /** The event code for a key, or null when the character is not a keypad key. */
    fun code(key: Char): Int? = when (key) {
        in '0'..'9' -> key - '0'
        '*' -> 10
        '#' -> 11
        in 'A'..'D' -> key - 'A' + 12
        in 'a'..'d' -> key - 'a' + 12
        else -> null
    }

    /**
     * The two frequencies a key is made of, rows first.
     *
     * Used for the local tone a caller hears when pressing a key. The far end gets the RFC
     * 4733 event instead, so this is feedback rather than signalling.
     */
    fun frequencies(key: Char): Pair<Double, Double>? = when (key) {
        '1' -> 697.0 to 1209.0
        '2' -> 697.0 to 1336.0
        '3' -> 697.0 to 1477.0
        '4' -> 770.0 to 1209.0
        '5' -> 770.0 to 1336.0
        '6' -> 770.0 to 1477.0
        '7' -> 852.0 to 1209.0
        '8' -> 852.0 to 1336.0
        '9' -> 852.0 to 1477.0
        '*' -> 941.0 to 1209.0
        '0' -> 941.0 to 1336.0
        '#' -> 941.0 to 1477.0
        else -> null
    }

    /**
     * A hundred milliseconds of the key, at the volume a handset uses for sidetone.
     *
     * The first and last few milliseconds ramp, because a tone that starts at full amplitude
     * is heard as a click rather than a beep.
     */
    fun localTone(key: Char, sampleRate: Int = Ringback.SAMPLE_RATE): ShortArray? {
        val (row, column) = frequencies(key) ?: return null
        val count = sampleRate / 10
        val ramp = sampleRate / 200
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val wave = (sin(2 * PI * row * t) + sin(2 * PI * column * t)) / 2
            val edge = when {
                i < ramp -> i.toDouble() / ramp
                i > count - ramp -> (count - i).toDouble() / ramp
                else -> 1.0
            }
            out[i] = (wave * edge * AMPLITUDE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private const val AMPLITUDE = 2_600.0

    /**
     * Four bytes: the event, a volume byte carrying the end bit, and the duration counted
     * in samples since the event started.
     */
    fun payload(event: Int, durationSamples: Int, end: Boolean): ByteArray {
        val volume = (if (end) 0x80 else 0x00) or (VOLUME and 0x3F)
        return byteArrayOf(
            event.toByte(),
            volume.toByte(),
            (durationSamples shr 8).toByte(),
            durationSamples.toByte(),
        )
    }
}
