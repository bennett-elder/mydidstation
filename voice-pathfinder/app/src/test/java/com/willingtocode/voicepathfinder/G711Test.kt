package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * G.711 against known values.
 *
 * The companding curve is easy to get plausibly wrong: the bits are stored inverted, so
 * the mistake sounds like loud noise rather than silence, and it is the first thing that
 * would be blamed on the network. These pin the three points that catch it.
 */
class G711Test {

    @Test
    fun `silence and the rails encode to the standard bytes`() {
        assertEquals(0xFF, G711.encodeSample(0))
        assertEquals(0x80, G711.encodeSample(32767))
        assertEquals(0x00, G711.encodeSample(-32768))
    }

    @Test
    fun `decoding the standard bytes gives the standard amplitudes`() {
        assertEquals(0, G711.decodeSample(0xFF))
        assertEquals(32124, G711.decodeSample(0x80))
        assertEquals(-32124, G711.decodeSample(0x00))
    }

    @Test
    fun `a frame is one byte per sample`() {
        assertEquals(160, G711.FRAME_SAMPLES)
        assertEquals(160, G711.encode(ShortArray(G711.FRAME_SAMPLES) { 1000 }).size)
        assertEquals(160, G711.decode(ByteArray(G711.FRAME_BYTES)).size)
    }

    @Test
    fun `a round trip stays inside the companding error`() {
        for (value in -32000..32000 step 97) {
            val decoded = G711.decodeSample(G711.encodeSample(value))
            // Quantisation is coarse for loud signals and fine for quiet ones, so the
            // tolerance is a fraction of the amplitude with a floor for near silence.
            val tolerance = maxOf(160, abs(value) / 16)
            assertTrue("$value encoded and came back as $decoded", abs(decoded - value) <= tolerance)
        }
    }

    @Test
    fun `peak reports the loudest sample`() {
        assertEquals(1f, G711.peak(shortArrayOf(0, -32767), 2), 0.0001f)
        assertEquals(0f, G711.peak(shortArrayOf(0, 0), 2), 0.0001f)
        assertEquals(0.5f, G711.peak(shortArrayOf(0, 16384), 2), 0.01f)
    }
}
