package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
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
    fun `the decode sample leaves the picture at or under the edge asked for`() {
        // A sample size that is too small sends more pixels than the attempt planned, which is
        // how a ladder can look right in a test and still produce an oversized file.
        listOf(
            4032 to MmsImage.LONG_EDGE,
            4032 to 1200,
            4032 to 900,
            4032 to 400,
            800 to 400,
        ).forEach { (longest, edge) ->
            val sample = MmsImage.sampleSize(longest, edge)
            assertTrue("sample $sample is a power of two", sample and (sample - 1) == 0)
            assertTrue("$longest at sample $sample is over edge $edge", longest / sample <= edge)
            if (sample > 1) {
                assertTrue(
                    "$longest at sample ${sample / 2} would have fit edge $edge, so the sample is too aggressive",
                    longest / (sample / 2) > edge,
                )
            }
        }
    }
}
