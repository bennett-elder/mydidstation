package com.willingtocode.mydidstation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the call window has something to show.
 *
 * This rule decides whether `CallActivity` stays up, and it read both flows in two places, so
 * the two could disagree. The failure it pins is the one measured on the handset: a sample with
 * neither a ring nor a call closed the window 16 ms after an answer, while the call was up.
 */
class CallWindowLiveTest {

    private val ring = Voice.Incoming(caller = "5551234567", did = "5550001111", line = "line")

    private val connected = PathState.Connected(
        number = "5551234567",
        codec = "PCMU/8000",
        encrypted = true,
        audio = true,
        at = 0L,
    )

    @Test
    fun `a ringing call is live`() {
        assertTrue(liveCall(ring, null))
    }

    @Test
    fun `dialling, ringing and a connected call are live`() {
        assertTrue(liveCall(null, PathState.Dialing("5551234567")))
        assertTrue(liveCall(null, PathState.Ringing("5551234567")))
        assertTrue(liveCall(null, connected))
    }

    @Test
    fun `a registration is not a call`() {
        assertFalse(liveCall(null, PathState.Registered(grantedSeconds = 3600, at = 0L)))
        assertFalse(liveCall(null, PathState.Registering(attempt = 1)))
        assertFalse(liveCall(null, PathState.Unregistered))
    }

    @Test
    fun `an ended or failed call is not live`() {
        assertFalse(liveCall(null, PathState.Ended("hung up")))
        assertFalse(liveCall(null, PathState.Failed("no network")))
    }

    @Test
    fun `nothing at all is not live`() {
        assertFalse(liveCall(null, null))
    }

    @Test
    fun `the ring keeps the window up even when the call flow has not caught up`() {
        // The answer clears the ring and sets the call in two writes. Between them the call can
        // still read as idle, and the ring is what stops that sample from closing the window.
        assertTrue(liveCall(ring, PathState.Ended("stale")))
    }
}
