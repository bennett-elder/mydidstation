package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a person types against what goes on the wire.
 *
 * A server field holding a scheme or a port, or a number with a leading plus, produces a
 * Request-URI that fails in a way that looks like a network problem. None of it is
 * visible in a log unless the log is read carefully, so each rule is pinned here.
 */
class ProviderTest {

    @Test
    fun `a server field loses its scheme, port and path`() {
        assertEquals("atlanta.voip.ms", Provider.host("atlanta.voip.ms"))
        assertEquals("atlanta.voip.ms", Provider.host("  sip:atlanta.voip.ms:5060  "))
        assertEquals("atlanta.voip.ms", Provider.host("sips:atlanta.voip.ms;transport=tls"))
        assertEquals("atlanta.voip.ms", Provider.host("udp://atlanta.voip.ms/"))
        assertEquals("atlanta.voip.ms", Provider.host("ATLANTA.VOIP.MS"))
        assertEquals("", Provider.host("   "))
    }

    @Test
    fun `a port inside the server field is found only when it is there`() {
        assertEquals(5060, Provider.port("sip:atlanta.voip.ms:5060"))
        assertNull(Provider.port("atlanta.voip.ms"))
        assertNull(Provider.port("sip:atlanta.voip.ms:99999"))
    }

    @Test
    fun `a dialled number keeps its digits, and a leading plus when present`() {
        assertEquals("+15551234567", Provider.normalizeNumber("+1 (555) 123-4567"))
        assertEquals("15551234567", Provider.normalizeNumber("1.555.123.4567"))
        assertEquals("15551234567", Provider.normalizeNumber(" 1 555 123 4567 "))
        assertEquals("", Provider.normalizeNumber("no digits here"))
        assertEquals("", Provider.normalizeNumber("+"))
    }

    @Test
    fun `the uris put the username and number where sip expects them`() {
        assertEquals("sip:atlanta.voip.ms", Provider.serverUri("atlanta.voip.ms"))
        assertEquals("sip:100000_VoIP@atlanta.voip.ms", Provider.userUri("atlanta.voip.ms", "100000_VoIP"))
        assertEquals("sip:15551234567@atlanta.voip.ms", Provider.dialUri("atlanta.voip.ms", "15551234567"))
        assertEquals(
            "<sip:100000_VoIP@192.0.2.10:5060>",
            Provider.contactUri("atlanta.voip.ms", "100000_VoIP", "192.0.2.10", 5060),
        )
    }
}
