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

    /** How much turning a photo needs before it can be sent without its EXIF tag. */
    data class Transform(
        val degrees: Int,
        val flipX: Boolean = false,
        val flipY: Boolean = false,
    ) {
        val needed: Boolean get() = degrees != 0 || flipX || flipY
    }

    /**
     * The turn and mirror that EXIF orientation 1 to 8 asks for.
     *
     * Because the encoder strips metadata, the rotation the tag describes has to be applied to
     * the pixels or it is lost, and an upright photo arrives on its side. 5 and 7 are the two
     * that need a mirror as well as a turn, which is why this is not just a set of angles.
     * Anything unrecognised is treated as already upright.
     */
    fun transform(orientation: Int): Transform = when (orientation) {
        2 -> Transform(0, flipX = true)
        3 -> Transform(180)
        4 -> Transform(0, flipY = true)
        5 -> Transform(90, flipX = true)
        6 -> Transform(90)
        7 -> Transform(270, flipX = true)
        8 -> Transform(270)
        else -> Transform(0)
    }

    /**
     * The size to scale a decoded picture down to, so its long edge is the one asked for.
     *
     * The decoder only samples in powers of two, so a 4032 pixel photo asked for 1600 comes
     * back at 1008 and no amount of sampling gets closer. That is what made a 3.39 MB photo
     * leave as a 137 KB one: it was sent at 1008 pixels wide, 40% of the pixels the plan
     * promised. Scaling afterwards is what makes the attempt mean what it says.
     *
     * Never upscales: a picture already inside the edge is left at its own size, because
     * inventing pixels costs bytes and buys nothing.
     */
    fun scaledSize(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (width <= 0 || height <= 0 || longEdge <= 0 || longest <= longEdge) return width to height
        val scale = longEdge.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

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
        // Halve only while the picture stays at or ABOVE the edge, so the decode always has
        // enough pixels to scale down to it exactly. Sampling to the nearest power of two and
        // stopping is what sent a 4032 pixel photo as 1008: under the edge, with nothing left
        // to scale. This and [scaledSize] are a pair, and neither is right alone.
        while (longestSide / (sample * 2) >= longEdge) sample *= 2
        return sample
    }
}
