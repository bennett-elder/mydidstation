package com.willingtocode.mydidstation

/** One encoding attempt: scale the picture to this long edge, then compress at this quality. */
data class EncodeAttempt(val longEdge: Int, val quality: Int)

/**
 * How a picture is made small enough to send.
 *
 * The numbers are measured, not chosen. 1,150,000 bytes arrived intact against the real API on
 * 2026-11-09, and a carrier-recompressed MMS photo lands around 1600 pixels on the long edge, so
 * sending more detail than that spends bytes on something the recipient never sees.
 *
 * This object is only the plan. Deciding it is pure, which is why it is here and tested; making
 * actual bytes needs the Android codec, which Robolectric fakes, so [MmsEncoder] walks this plan
 * and a phone is where that path gets checked.
 */
internal object MmsImage {

    /** The file ceiling that was proven to arrive. The base64 around it can be larger. */
    const val TARGET_BYTES = 1_150_000

    /** The long edge the first attempt uses. */
    const val LONG_EDGE = 1600

    /** The smallest long edge worth sending, below which a picture is a thumbnail. */
    const val MIN_LONG_EDGE = 400

    /** Good enough to look untouched, down to the smallest the app will send. */
    private val QUALITIES = listOf(88, 80, 72, 64, 56, 48, 40)

    /**
     * Every attempt, in the order to try them: largest edge first, best quality first, so the
     * first fit is the best-looking one that fits.
     *
     * Two rules matter. The edge never grows, because upscaling invents detail and costs bytes.
     * Each edge is tried at every quality before the edge drops, because a slightly softer
     * picture at full size usually reads better than a smaller sharp one.
     */
    fun attempts(width: Int, height: Int): List<EncodeAttempt> {
        if (width <= 0 || height <= 0) return emptyList()
        val longest = maxOf(width, height)

        val out = mutableListOf<EncodeAttempt>()
        var edge = minOf(LONG_EDGE, longest)
        while (true) {
            QUALITIES.forEach { quality -> out += EncodeAttempt(edge, quality) }
            if (edge <= MIN_LONG_EDGE) break
            val next = (edge * 3) / 4
            edge = if (next < MIN_LONG_EDGE) MIN_LONG_EDGE else next
        }
        return out
    }

    /** The first attempt whose encoded size fits, or null when nothing did. */
    fun firstFit(sizes: List<Long>, target: Int = TARGET_BYTES): Int? =
        sizes.indexOfFirst { it <= target }.takeIf { it >= 0 }

    /**
     * The sample size to decode with for a given edge.
     *
     * A power of two, because that is what the decoder honours exactly; anything else is
     * rounded down to the nearest power of two and would leave the picture larger than the
     * attempt asked for.
     */
    fun sampleSize(longestSide: Int, longEdge: Int): Int {
        if (longEdge <= 0 || longestSide <= 0) return 1
        var sample = 1
        // Keep halving while the result is still wider than the edge. Testing the *next* sample
        // against the edge instead left the decoded picture over the edge it asked for, which
        // is how an attempt ends up bigger than the plan says.
        while (longestSide / sample > longEdge) sample *= 2
        return sample
    }
}
