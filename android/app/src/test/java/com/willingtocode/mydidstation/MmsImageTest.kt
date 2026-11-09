package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan for shrinking a picture, without a codec.
 *
 * What can be wrong here is ordering and arithmetic: an attempt that upscales, an edge that
 * grows, a quality ladder that runs backwards, or a sample size that leaves the picture bigger
 * than the attempt asked for. None of that shows up until a phone sends something and the
 * recipient gets a worse picture than they should.
 */
class MmsImageTest {

    @Test
    fun `a camera photo starts at the long edge and shrinks from there`() {
        val attempts = MmsImage.attempts(4032, 3024)
        assertEquals("the first attempt is the long edge", MmsImage.LONG_EDGE, attempts.first().longEdge)
        val edges = attempts.map { it.longEdge }.distinct()
        assertEquals("edges only go down", edges.sortedDescending(), edges)
        assertTrue("every edge is inside the budget", edges.all { it <= MmsImage.LONG_EDGE })
        assertTrue("it ends small enough to be worth trying", edges.last() <= MmsImage.MIN_LONG_EDGE + 1)
    }

    @Test
    fun `a small picture is never upscaled`() {
        // Upscaling invents detail and costs bytes, so a picture already under the long edge is
        // sent at its own size and then only shrunk.
        val attempts = MmsImage.attempts(800, 600)
        assertEquals(800, attempts.first().longEdge)
        assertTrue("nothing exceeds the source", attempts.all { it.longEdge <= 800 })
    }

    @Test
    fun `every quality is tried before the edge drops`() {
        val attempts = MmsImage.attempts(4032, 3024)
        val firstEdge = attempts.first().longEdge
        val forFirstEdge = attempts.takeWhile { it.longEdge == firstEdge }
        assertTrue("the ladder has several steps", forFirstEdge.size >= 4)
        assertEquals(
            "quality only goes down",
            forFirstEdge.map { it.quality }.sortedDescending(),
            forFirstEdge.map { it.quality },
        )
        assertTrue("the best quality is tried first", forFirstEdge.first().quality > 60)
    }

    @Test
    fun `a portrait picture is judged by its longest side`() {
        val portrait = MmsImage.attempts(3024, 4032)
        val landscape = MmsImage.attempts(4032, 3024)
        assertEquals(landscape.map { it.longEdge }, portrait.map { it.longEdge })
    }

    @Test
    fun `nonsense dimensions produce no attempts rather than a crash`() {
        assertEquals(emptyList<EncodeAttempt>(), MmsImage.attempts(0, 0))
        assertEquals(emptyList<EncodeAttempt>(), MmsImage.attempts(-1, 500))
    }

    @Test
    fun `the first size under the target wins`() {
        val target = MmsImage.TARGET_BYTES
        assertEquals(2, MmsImage.firstFit(listOf(target + 10L, target + 1L, target.toLong(), 1L)))
        assertEquals(0, MmsImage.firstFit(listOf(1L)))
        assertNull("nothing fit", MmsImage.firstFit(listOf(target + 1L, target + 2L)))
        assertNull("no attempts at all", MmsImage.firstFit(emptyList()))
    }

    @Test
    fun `scaling puts the long edge exactly where the attempt asked`() {
        // The bug this exists for: sampling lands on a power of two, so a 4032 pixel photo
        // asked for 1600 was decoded at 1008 and sent that way, 40% of the pixels promised.
        assertEquals(1600 to 1200, MmsImage.scaledSize(4032, 3024, 1600))
        assertEquals(1200 to 1600, MmsImage.scaledSize(3024, 4032, 1600))
        assertEquals(1600 to 900, MmsImage.scaledSize(4000, 2250, 1600))
    }

    @Test
    fun `a picture already inside the edge is never scaled up`() {
        assertEquals(800 to 600, MmsImage.scaledSize(800, 600, 1600))
        assertEquals(1600 to 1200, MmsImage.scaledSize(1600, 1200, 1600))
        assertEquals(300 to 200, MmsImage.scaledSize(300, 200, 400))
    }

    @Test
    fun `nonsense dimensions survive the scaling arithmetic`() {
        assertEquals(0 to 0, MmsImage.scaledSize(0, 0, 1600))
        assertEquals(100 to 100, MmsImage.scaledSize(100, 100, 0))
    }

    @Test
    fun `the decode keeps enough pixels to scale down to the edge`() {
        // The pairing that fixes the 137 KB photo: the sample must leave the decode at or
        // above the edge, because scaledSize refuses to invent pixels. Changing either half
        // alone sends a smaller picture than the plan promised, silently.
        listOf(
            4032 to MmsImage.LONG_EDGE,
            4032 to 1200,
            4032 to 900,
            800 to 400,
        ).forEach { (longest, edge) ->
            val sample = MmsImage.sampleSize(longest, edge)
            assertTrue("sample $sample is a power of two", sample and (sample - 1) == 0)
            assertTrue(
                "$longest at sample $sample is under edge $edge, so nothing is left to scale",
                longest / sample >= edge,
            )
            if (longest / (sample * 2) > 0) {
                assertTrue(
                    "$longest at sample ${sample * 2} would still clear edge $edge, so it sampled too far",
                    longest / (sample * 2) < edge,
                )
            }
        }
    }

    // Orientation, which the encoder has to bake in because it strips the tag.

    @Test
    fun `every orientation maps to the turn that makes it upright`() {
        assertEquals(MmsImage.Transform(0), MmsImage.transform(1))
        assertEquals(MmsImage.Transform(0, flipX = true), MmsImage.transform(2))
        assertEquals(MmsImage.Transform(180), MmsImage.transform(3))
        assertEquals(MmsImage.Transform(0, flipY = true), MmsImage.transform(4))
        assertEquals(MmsImage.Transform(90, flipX = true), MmsImage.transform(5))
        assertEquals(MmsImage.Transform(90), MmsImage.transform(6))
        assertEquals(MmsImage.Transform(270, flipX = true), MmsImage.transform(7))
        assertEquals(MmsImage.Transform(270), MmsImage.transform(8))
    }

    @Test
    fun `an orientation nobody has heard of leaves the picture alone`() {
        assertEquals(MmsImage.Transform(0), MmsImage.transform(0))
        assertEquals(MmsImage.Transform(0), MmsImage.transform(9))
        assertEquals(MmsImage.Transform(0), MmsImage.transform(-1))
        assertFalse("nothing to do should not report work", MmsImage.transform(1).needed)
        assertTrue("a 90 degree turn is work", MmsImage.transform(6).needed)
        assertTrue("a mirror with no turn is work", MmsImage.transform(2).needed)
    }
}
