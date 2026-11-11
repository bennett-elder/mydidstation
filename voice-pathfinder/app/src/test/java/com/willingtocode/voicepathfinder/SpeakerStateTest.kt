package com.willingtocode.voicepathfinder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The speaker button's state, checked without a call and without a handset.
 *
 * What went wrong three times on the phone was never the route request. It was the flag
 * behind the button: a request the device never applied was recorded as though it had been,
 * so the chip read "Earpiece" while the call was on the earpiece, and every later tap
 * computed `!true` and asked for the earpiece again. The logs showed four `route earpiece`
 * and never one `route speaker`.
 *
 * `CallAudioPort` exists so a test can stand in for the real device. The stand-in here
 * refuses every route, which is exactly what Telecom does to an app that is not the mode
 * owner, and the two properties that matter are what it was asked for and what the screen
 * was then told.
 */
class SpeakerStateTest {

    /** A device that accepts a route request and does not act on it. */
    private class RefusingDevice : CallAudioPort {
        val requests = mutableListOf<Boolean>()

        override fun start(): Boolean = true
        override fun readFrame(buffer: ShortArray): Int = 0
        override fun write(samples: ShortArray, count: Int) = Unit
        override fun useSpeaker(on: Boolean) {
            requests += on
        }
        override fun releaseRouting() = Unit
        override fun followRoute(onSpeaker: Boolean) = Unit
        override fun stop() = Unit
        override val echoCancelled: Boolean = false

        /** Always the earpiece, whatever was asked for. */
        override val speaker: Boolean = false
    }

    private fun media(): Pair<MediaSession, RefusingDevice> {
        val device = RefusingDevice()
        return MediaSession(note = {}, audioFactory = { device }) to device
    }

    @Test
    fun a_call_starts_on_the_earpiece() {
        val (session, device) = media()
        session.beginTone(CoroutineScope(Dispatchers.Unconfined))
        // Ringback used to ask for the speaker here, a leftover from the probe. That request
        // cannot take under Telecom, but the audio layer recorded it as the route anyway.
        assertEquals(listOf(false), device.requests)
        session.stopTone()
    }

    @Test
    fun a_refused_route_is_not_recorded_as_the_route() {
        val (session, device) = media()
        session.beginTone(CoroutineScope(Dispatchers.Unconfined))
        session.setSpeaker(true)
        assertEquals(true, device.requests.last())
        // Asked for, refused, so the chip must still say earpiece. Recording the wish here is
        // what inverted the button and made the speaker unreachable.
        assertFalse(session.stats.value.speaker)
        session.stopTone()
    }

    /**
     * The device used to start claiming the speaker, a leftover from the probe. Opening a call
     * copied that claim into the chip, so the button offered "Earpiece" while the call was
     * already on the earpiece, and every tap asked for the earpiece again.
     */
    @Test
    fun a_device_that_claims_the_speaker_does_not_set_the_chip() {
        val device = object : CallAudioPort {
            override fun start(): Boolean = true
            override fun readFrame(buffer: ShortArray): Int = 0
            override fun write(samples: ShortArray, count: Int) = Unit
            override fun useSpeaker(on: Boolean) = Unit
            override fun releaseRouting() = Unit
        override fun followRoute(onSpeaker: Boolean) = Unit
            override fun stop() = Unit
            override val echoCancelled: Boolean = false
            override val speaker: Boolean = true
        }
        val session = MediaSession(note = {}, audioFactory = { device })
        session.beginTone(CoroutineScope(Dispatchers.Unconfined))
        // Only the platform and an applied route may move this flag.
        assertFalse(session.stats.value.speaker)
        session.stopTone()
    }

    @Test
    fun the_platform_can_correct_the_chip() {
        val (session, _) = media()
        session.beginTone(CoroutineScope(Dispatchers.Unconfined))
        session.setSpeaker(true)
        assertFalse(session.stats.value.speaker)
        // What `onCallAudioStateChanged` does when Telecom reports the real route.
        session.noteSpeaker(true)
        assertEquals(true, session.stats.value.speaker)
        session.stopTone()
    }
}
