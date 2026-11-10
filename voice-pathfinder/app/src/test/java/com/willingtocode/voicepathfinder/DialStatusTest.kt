package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the dial page says.
 *
 * The page used to report the probe's own state, so it read "Not registered" over a phone
 * that was answering calls. These pin the wording to the lines, which are what register.
 */
class DialStatusTest {

    private val ready = Pathfinder.Readiness(lines = 1, registered = 1, grantedSeconds = 3600)

    @Test
    fun `a registered line reads as ready`() {
        assertEquals("Ready. 1 line registered.", dialStatus(ready, PathState.Unregistered))
    }

    @Test
    fun `several lines are counted`() {
        assertEquals(
            "Ready. 2 lines registered.",
            dialStatus(Pathfinder.Readiness(lines = 2, registered = 2), PathState.Unregistered),
        )
    }

    @Test
    fun `a line still registering says so rather than claiming ready`() {
        assertEquals(
            "Registering…",
            dialStatus(Pathfinder.Readiness(lines = 1, preparing = true), PathState.Unregistered),
        )
    }

    @Test
    fun `a refused registration carries the provider's reason`() {
        assertEquals(
            "Not answering: 403 Forbidden",
            dialStatus(Pathfinder.Readiness(lines = 1, failure = "403 Forbidden"), PathState.Unregistered),
        )
    }

    @Test
    fun `a phone that is still starting does not claim nothing is set up`() {
        assertEquals(
            "Setting up your phone…",
            dialStatus(Pathfinder.Readiness(), PathState.Unregistered, starting = true),
        )
    }

    @Test
    fun `once starting is over, no lines does point at settings`() {
        assertEquals(
            "No number is set up to answer. Choose one in Settings.",
            dialStatus(Pathfinder.Readiness(), PathState.Unregistered),
        )
    }

    @Test
    fun `a call in progress wins over the registration line`() {
        assertTrue(dialStatus(ready, PathState.Dialing("5551234567")).startsWith("Calling"))
    }

    @Test
    fun `a registered line is enough to place a call`() {
        assertNull(callBlockedReason(ready))
    }

    @Test
    fun `without a registration the button says what is missing`() {
        assertEquals(
            "Waiting for the registration to land.",
            callBlockedReason(Pathfinder.Readiness(lines = 1, preparing = true)),
        )
        assertEquals(
            "No line is registered, so there is nothing to call on.",
            callBlockedReason(Pathfinder.Readiness()),
        )
    }
}
