package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a destination is, and whether it is worth sending to.
 *
 * No Robolectric and no server: these are pure functions over strings, and they decide which
 * conversation an arriving message lands in and whether the composer will send at all. They
 * used to be tested from the API client's test class, which meant the file that mocks HTTP was
 * also the file documenting a rule about numbers.
 */
class PhoneNumbersTest {

    @Test
    fun `a shortcode can be replied to`() {
        // The reply that stops marketing traffic. Seven digits used to be the floor, so the
        // compose field refused every shortcode and the app could not send STOP.
        assertTrue(PhoneNumbers.isSendable("22395"))
        assertTrue(PhoneNumbers.isSendable("32665"))
    }

    @Test
    fun `a normal number in any shape is sendable`() {
        assertTrue(PhoneNumbers.isSendable("5551234567"))
        assertTrue(PhoneNumbers.isSendable("(555) 123-4567"))
        assertTrue(PhoneNumbers.isSendable("+1 555 123 4567"))
        assertTrue(PhoneNumbers.isSendable("+44 20 7946 0958"))
    }

    @Test
    fun `an empty or nonsense destination is not sendable`() {
        assertFalse(PhoneNumbers.isSendable(""))
        assertFalse(PhoneNumbers.isSendable("   "))
        assertFalse(PhoneNumbers.isSendable("123"))
        assertFalse(PhoneNumbers.isSendable("1234567890123456"))
    }

    @Test
    fun `the sendable range starts at a short code and ends at fifteen digits`() {
        // Both ends are refusals people meet in the composer: a truncated paste, and a wall
        // of digits. The middle is what has to keep working.
        assertFalse("four digits is shorter than any short code", PhoneNumbers.isSendable("2239"))
        assertTrue("five digits is a short code", PhoneNumbers.isSendable("22395"))
        assertTrue("fifteen digits is the E.164 ceiling", PhoneNumbers.isSendable("123456789012345"))
        assertFalse("sixteen digits is past it", PhoneNumbers.isSendable("1234567890123456"))
    }

    @Test
    fun `a short code loses the country code the API added`() {
        // What the API actually sends for a five digit sender. Replying to the unstripped
        // form is answered with malformed-invalid-to-number, because it is not a number.
        assertEquals("89866", PhoneNumbers.normalizePhone("189866"))
        assertEquals("898661", PhoneNumbers.normalizePhone("1898661"))
    }

    @Test
    fun `a number loses its country code as it always did`() {
        assertEquals("8885551234", PhoneNumbers.normalizePhone("18885551234"))
        assertEquals("5551234567", PhoneNumbers.normalizePhone("(555) 123-4567"))
    }

    @Test
    fun `a short code with no prefix is left alone`() {
        assertEquals("89866", PhoneNumbers.normalizePhone("89866"))
    }

    @Test
    fun `a short code that starts with one keeps its digits`() {
        // Somewhere other than NANP, a five digit code may begin with 1. The allocated
        // ranges say US and Canadian codes never do, so a remainder that starts with 1 is
        // not one, and stripping it would invent a different destination.
        assertEquals("112345", PhoneNumbers.normalizePhone("112345"))
    }

    @Test
    fun `a value that is neither a number nor a short code is untouched`() {
        assertEquals("", PhoneNumbers.normalizePhone(""))
        assertEquals("123", PhoneNumbers.normalizePhone("123"))
        assertEquals("15551234", PhoneNumbers.normalizePhone("15551234"))
    }
}
