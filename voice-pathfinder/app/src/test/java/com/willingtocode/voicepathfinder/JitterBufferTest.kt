package com.willingtocode.voicepathfinder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The buffer's arithmetic, driven by a synthetic clock.
 *
 * Every case here is something that happens on a real call and is invisible from a
 * green end-to-end test: a reordered pair, one missing packet, a duplicate, and the
 * sequence wrap that happens once every 65536 packets.
 */
class JitterBufferTest {

    private var now = 0L

    private fun buffer(depth: Int = 3) = JitterBuffer(depth = depth, now = { now })

    private fun payload(value: Int) = byteArrayOf(value.toByte())

    @Test
    fun `plays in order once the buffer fills`() {
        val buffer = buffer()
        buffer.put(1, payload(1))
        buffer.put(2, payload(2))
        buffer.put(3, payload(3))
        assertArrayEquals(payload(1), buffer.poll())
        assertArrayEquals(payload(2), buffer.poll())
        assertArrayEquals(payload(3), buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun `a short prebuffer starts on the clock rather than waiting to fill`() {
        val buffer = buffer()
        buffer.put(1, payload(1))
        assertNull(buffer.poll())
        now = JitterBuffer.DEFAULT_PREBUFFER_MILLIS + 1
        assertArrayEquals(payload(1), buffer.poll())
    }

    @Test
    fun `a reordered pair is played in sequence`() {
        val buffer = buffer(depth = 2)
        buffer.put(2, payload(2))
        buffer.put(1, payload(1))
        assertArrayEquals(payload(1), buffer.poll())
        assertArrayEquals(payload(2), buffer.poll())
        assertEquals(1, buffer.counters().reordered)
    }

    @Test
    fun `a gap is silence, then a skip that is counted as lost`() {
        val buffer = buffer(depth = 2)
        buffer.put(1, payload(1))
        buffer.put(2, payload(2))
        assertArrayEquals(payload(1), buffer.poll())
        assertArrayEquals(payload(2), buffer.poll())

        buffer.put(4, payload(4))
        assertNull(buffer.poll()) // waiting for 3, not skipping yet
        now = JitterBuffer.DEFAULT_SKIP_MILLIS + 1
        assertArrayEquals(payload(4), buffer.poll())
        assertEquals(1, buffer.counters().lost)
    }

    @Test
    fun `duplicates and packets behind the play head are dropped`() {
        val buffer = buffer(depth = 2)
        buffer.put(1, payload(1))
        buffer.put(2, payload(2))
        buffer.poll()
        buffer.poll()

        assertEquals(JitterBuffer.Put.LATE, buffer.put(1, payload(9)))
        assertEquals(JitterBuffer.Put.ACCEPTED, buffer.put(3, payload(3)))
        assertEquals(JitterBuffer.Put.DUPLICATE, buffer.put(3, payload(9)))
        assertEquals(1, buffer.counters().late)
        assertEquals(1, buffer.counters().duplicates)
        assertArrayEquals(payload(3), buffer.poll())
    }

    @Test
    fun `the sequence wrap is played in order`() {
        val buffer = buffer(depth = 2)
        buffer.put(65535, payload(1))
        buffer.put(0, payload(2))
        buffer.put(1, payload(3))
        assertArrayEquals(payload(1), buffer.poll())
        assertArrayEquals(payload(2), buffer.poll())
        assertArrayEquals(payload(3), buffer.poll())
    }

    @Test
    fun `reset forgets the play head`() {
        val buffer = buffer(depth = 1)
        buffer.put(500, payload(1))
        assertArrayEquals(payload(1), buffer.poll())
        buffer.reset()
        buffer.put(70000 and 0xFFFF, payload(2))
        assertArrayEquals(payload(2), buffer.poll())
        assertEquals(0, buffer.counters().lost)
    }
}
