package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The key that decides whether an arriving message belongs to the thread on screen.
 *
 * A wrong key is quiet in both directions. A message in the thread being read buzzes
 * anyway, or a message in another thread stays silent while the screen shows something
 * else, and neither shows up in a log.
 */
class VisibleThreadTest {

    @Test
    fun `a key is the number, a bar and the contact`() {
        assertEquals("5551234567|5559990009", VisibleThread.of("5551234567", "5559990009"))
    }

    @Test
    fun `the same pair is the same key and either part alone changes it`() {
        assertEquals(
            VisibleThread.of("5551234567", "5559990009"),
            VisibleThread.of("5551234567", "5559990009"),
        )
        assertNotEquals(
            VisibleThread.of("5551234567", "5559990009"),
            VisibleThread.of("5551234568", "5559990009"),
        )
        assertNotEquals(
            VisibleThread.of("5551234567", "5559990009"),
            VisibleThread.of("5551234567", "5559990008"),
        )
    }

    @Test
    fun `two pairs cannot collide`() {
        // The bar appears exactly once in a key built from digits, so a contact cannot
        // spell itself into another thread's key. A key built from free text would need
        // escaping. Both fields are digits by the time they reach here.
        val key = VisibleThread.of("5551234567", "5559990009")
        assertEquals(1, key.count { it == '|' })
        assertNotEquals(
            VisibleThread.of("5551234567", "55"),
            VisibleThread.of("55512345", "6755"),
        )
    }
}
