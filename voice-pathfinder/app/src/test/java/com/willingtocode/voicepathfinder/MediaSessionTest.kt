package com.willingtocode.voicepathfinder

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media path, driven without a handset.
 *
 * Audio is a fake port and the provider is a loopback socket, so what used to need a phone
 * and a real call is now a JVM test: packets leave, packets arrive, SRTP round trips, and
 * the counters say so.
 */
class MediaSessionTest {

    /** The loopback address, so nothing leaves the machine. */
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /**
     * Stands in for `AudioRecord` and `AudioTrack`.
     *
     * The 20 ms sleep is not padding: the send loop has no timer of its own because the
     * device blocks for a frame. Returning instantly would spin the CPU instead.
     */
    private class FakeAudio(private val sample: Short = 1200) : CallAudioPort {
        val written = mutableListOf<ShortArray>()

        override fun start() = true

        override fun readFrame(buffer: ShortArray): Int {
            buffer.fill(sample)
            Thread.sleep(20)
            return buffer.size
        }

        override fun write(samples: ShortArray, count: Int) {
            synchronized(written) {
                if (written.size < 20) written += samples.copyOf(count)
            }
        }

        override fun useSpeaker(on: Boolean) {
            speaker = on
        }

        override fun stop() = Unit

        override var echoCancelled = false
        override var speaker = true
    }

    private fun session(audio: CallAudioPort?) = MediaSession(
        note = { },
        socketFactory = { DatagramSocket(0, loopback) },
        audioFactory = { audio },
        statsMillis = 25,
    )

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `a frame leaves as plain RTP and the counter says so`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = DatagramSocket(0, loopback)
        provider.soTimeout = 3000
        val media = session(FakeAudio())
        media.open()
        assertTrue(media.begin(scope, InetSocketAddress(loopback, provider.localPort)))

        val packet = DatagramPacket(ByteArray(2048), 2048)
        provider.receive(packet)
        val parsed = Rtp.parse(packet.data.copyOf(packet.length))
        assertTrue("nothing parsable arrived", parsed != null)
        assertEquals(G711.PAYLOAD_TYPE, parsed!!.payloadType)
        assertEquals(G711.FRAME_SAMPLES, parsed.payload.size)

        waitFor("the send counter") { media.stats.value.framesSent > 0 }
        media.stop()
        scope.cancel()
        provider.close()
    }

    @Test
    fun `an encrypted call round trips through the two SDP keys`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = DatagramSocket(0, loopback)
        provider.soTimeout = 3000
        val ours = Sdp.newCryptoKey()
        val theirs = Sdp.newCryptoKey()
        val media = session(FakeAudio())
        media.open()
        assertTrue("the keys were rejected", media.encryptWith(Sdp.unbase64(ours), Sdp.unbase64(theirs)))
        assertTrue(media.begin(scope, InetSocketAddress(loopback, provider.localPort)))

        val packet = DatagramPacket(ByteArray(2048), 2048)
        provider.receive(packet)
        val wire = packet.data.copyOf(packet.length)
        // The far side holds the key we sent in our offer, so it can open what we wrote.
        val far = SrtpContext(SrtpKey.fromInline(Sdp.unbase64(ours)!!))
        val clear = far.unprotect(wire, wire.size)
        assertTrue("the packet did not decrypt with our own key", clear != null)
        assertEquals(G711.PAYLOAD_TYPE, Rtp.parse(clear!!)!!.payloadType)

        media.stop()
        scope.cancel()
        provider.close()
    }

    @Test
    fun `a packet from the far side is counted and reaches the speaker`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provider = DatagramSocket(0, loopback)
        val audio = FakeAudio()
        val media = session(audio)
        media.open()
        assertTrue(media.begin(scope, InetSocketAddress(loopback, provider.localPort)))

        val pcm = ShortArray(G711.FRAME_SAMPLES) { 800 }
        var sequence = 1
        repeat(6) {
            val wire = Rtp.build(
                payloadType = G711.PAYLOAD_TYPE,
                marker = it == 0,
                sequence = sequence++,
                timestamp = (it * G711.FRAME_SAMPLES).toLong(),
                ssrc = 424242L,
                payload = G711.encode(pcm, pcm.size),
            )
            provider.send(DatagramPacket(wire, wire.size, loopback, media.localPort))
            Thread.sleep(20)
        }

        waitFor("the receive counter") { media.stats.value.framesReceived >= 6 }
        waitFor("audio written to the track") {
            synchronized(audio.written) { audio.written.isNotEmpty() }
        }

        media.stop()
        scope.cancel()
        provider.close()
    }

    @Test
    fun `without an audio device the media path does not start`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val media = session(null)
        media.open()
        assertFalse(media.begin(scope, InetSocketAddress(loopback, 1)))
        media.stop()
        scope.cancel()
    }
}
