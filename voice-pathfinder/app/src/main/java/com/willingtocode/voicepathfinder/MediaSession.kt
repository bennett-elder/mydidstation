package com.willingtocode.voicepathfinder

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The audio device, as the media path sees it.
 *
 * An interface so a test can stand in for `AudioRecord` and `AudioTrack`, which is the
 * difference between checking the media path on the JVM and checking it with a handset.
 */
internal interface CallAudioPort {
    fun start(): Boolean
    fun readFrame(buffer: ShortArray): Int
    fun write(samples: ShortArray, count: Int)
    /** Routes the call to the speaker or the earpiece. Named apart from the property. */
    fun useSpeaker(on: Boolean)

    /** Stops pinning the streams to a device, so the platform's route decides. */
    fun releaseRouting()

    /** Makes the streams follow a route the platform reported. */
    fun followRoute(onSpeaker: Boolean)

    fun stop()
    val echoCancelled: Boolean
    val speaker: Boolean
}

/**
 * One call's media: a socket, a codec, a jitter buffer and the two audio directions.
 *
 * It knows nothing about SIP. A call hands it a remote address and, when the provider
 * agreed to encryption, two inline keys, and it runs until stopped. There is one per call
 * and never a shared instance: a multi-DID phone holds several lines and has more than one
 * call to account for over its life, so media that lives on a controller would repeat the
 * mistake the single-registration probe made.
 */
internal class MediaSession(
    private val note: (String) -> Unit,
    private val socketFactory: () -> DatagramSocket? = { LocalNetwork.rtpSocket() },
    private val audioFactory: ((text: String) -> Unit) -> CallAudioPort? = { null },
    private val statsMillis: Long = 250L,
) {

    private var socket: DatagramSocket? = null
    private var audio: CallAudioPort? = null
    private var remote: InetSocketAddress? = null
    private var cryptoOut: SrtpContext? = null
    private var cryptoIn: SrtpContext? = null
    private val jitter = JitterBuffer()
    private val jobs = mutableListOf<Job>()
    private var toneJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private val toneLock = Any()
    private var dtmfTone: ShortArray? = null
    private var dtmfAt = 0

    private var sequence = 0
    private var timestamp = 0L
    private var ssrc = 0L
    private val framesSent = AtomicLong()
    private val framesReceived = AtomicLong()
    private val datagrams = AtomicLong()
    @Volatile private var muted = false
    @Volatile private var sendPeak = 0f
    @Volatile private var receivePeak = 0f
    private val random = SecureRandom()

    private val _stats = MutableStateFlow(MediaStats())
    val stats: StateFlow<MediaStats> = _stats

    /** The port the provider was told to send to, or 0 before [open]. */
    val localPort: Int get() = socket?.localPort ?: 0

    /** Binds a local port. The offer can only be built once this has a number. */
    fun open(): Int {
        val bound = socketFactory() ?: throw SipFault(
            "no free RTP port in ${LocalNetwork.RTP_FIRST}-${LocalNetwork.RTP_LAST}",
        )
        socket = bound
        _stats.update { it.copy(localRtpPort = bound.localPort) }
        return bound.localPort
    }

    /**
     * Takes the two SDES keys for this call.
     *
     * Both come from SDP, ours from the offer and the far side's from the answer, so there
     * is nothing else to fetch. Returns false when either is unusable, which is a call that
     * could be reported as encrypted and then be silent.
     */
    fun encryptWith(local: ByteArray?, theirs: ByteArray?): Boolean {
        if (local == null || theirs == null ||
            local.size != Srtp.INLINE_BYTES || theirs.size != Srtp.INLINE_BYTES
        ) {
            cryptoOut = null
            cryptoIn = null
            return false
        }
        cryptoOut = SrtpContext(SrtpKey.fromInline(local))
        cryptoIn = SrtpContext(SrtpKey.fromInline(theirs))
        return true
    }


    /**
     * Starts the four loops and returns whether audio is running.
     *
     * Without an audio device there is nothing to carry, so the media path does not start
     * and the call is reported as connected without audio rather than silently dead.
     */
    /** Starts the audio device once, so ringback and the call share it. */
    private fun device(): CallAudioPort? {
        audio?.let { return it }
        val opened = audioFactory { text -> note(text) } ?: return null
        if (!opened.start()) return null
        audio = opened
        return opened
    }

    /**
     * Plays ringback while the far end rings.
     *
     * A `180 Ringing` carries no audio, so the tone is the caller's job. Without this the
     * caller hears silence until the call is answered, which reads as a broken call.
     */
    fun beginTone(scope: CoroutineScope, onSpeaker: Boolean = false): Boolean {
        if (toneJob != null) return true
        val opened = device()
        if (opened == null) {
            note("the audio stack did not start, so there is no ringback")
            return false
        }
        opened.useSpeaker(onSpeaker)
        toneJob = scope.launch { toneLoop(opened) }
        return true
    }

    fun stopTone() {
        toneJob?.cancel()
        toneJob = null
    }

    fun begin(scope: CoroutineScope, to: InetSocketAddress, onSpeaker: Boolean = false): Boolean {
        val bound = socket ?: throw SipFault("the media session was opened before it had a port")
        stopTone()
        remote = to
        val opened = device()
        if (opened == null) {
            note("the audio stack did not start, so this call has no media")
            return false
        }
        val device = opened
        // The probe's habit, kept while the probe is still the harness: a phone call belongs
        // on the earpiece, but a screen being read across a desk does not.
        device.useSpeaker(onSpeaker)

        jitter.reset()
        ssrc = random.nextInt().toLong() and 0xFFFFFFFFL
        sequence = random.nextInt(65536)
        timestamp = random.nextInt().toLong() and 0xFFFFFFFFL
        framesSent.set(0)
        framesReceived.set(0)
        datagrams.set(0)
        sendPeak = 0f
        receivePeak = 0f

        sessionScope = scope
        jobs += scope.launch { sendLoop(bound, device) }
        jobs += scope.launch { receiveLoop(bound) }
        jobs += scope.launch { playoutLoop(device) }
        jobs += scope.launch { statsLoop() }
        return true
    }

    /**
     * Stops sending the microphone while keeping the RTP stream alive.
     *
     * Silence rather than dropping packets: a gap in the stream makes the far end's jitter
     * buffer starve and then click when it refills.
     */
    fun setMuted(on: Boolean) {
        muted = on
    }

    /**
     * Sends one keypress as RFC 4733 events.
     *
     * Five packets of 20 ms each, then three end packets, which is what RFC 4733 asks for so
     * a lost packet does not leave the digit sounding forever. They share the sequence and
     * timestamp stream with the audio, because they are RTP packets on the same stream.
     */
    fun sendDtmf(key: Char) {
        // Feedback first: the caller should hear the press even if the event is lost.
        Dtmf.localTone(key)?.let { tone ->
            synchronized(toneLock) {
                dtmfTone = tone
                dtmfAt = 0
            }
        }
        val code = Dtmf.code(key) ?: return
        val scope = sessionScope ?: return
        val target = remote ?: return
        val bound = socket ?: return
        scope.launch {
            for (step in 1..Dtmf.STEPS) {
                sendEvent(bound, target, code, Dtmf.STEP_SAMPLES * step, end = false)
                delay(Dtmf.STEP_SAMPLES / 8L)
            }
            repeat(3) {
                sendEvent(bound, target, code, Dtmf.STEP_SAMPLES * Dtmf.STEPS, end = true)
                delay(Dtmf.STEP_SAMPLES / 8L)
            }
        }
    }

    private fun sendEvent(
        bound: DatagramSocket,
        target: InetSocketAddress,
        code: Int,
        duration: Int,
        end: Boolean,
    ) {
        val packet = Rtp.build(
            payloadType = Sdp.TELEPHONE_EVENT_PT,
            marker = duration == Dtmf.STEP_SAMPLES,
            sequence = sequence,
            timestamp = timestamp,
            ssrc = ssrc,
            payload = Dtmf.payload(code, duration, end),
        )
        val wire = cryptoOut?.let { runCatching { it.protect(packet) }.getOrNull() } ?: packet
        runCatching { bound.send(DatagramPacket(wire, wire.size, target)) }
        sequence = Sequence.next(sequence)
    }

    /** Hands routing back to the platform, which is what Telecom expects to own. */
    fun releaseRouting() {
        audio?.releaseRouting()
    }

    fun followRoute(onSpeaker: Boolean) {
        audio?.followRoute(onSpeaker)
    }

    /** Records the route without asking the platform for one. */
    fun noteSpeaker(on: Boolean) {
        _stats.update { it.copy(speaker = on) }
    }

    fun setSpeaker(on: Boolean) {
        audio?.useSpeaker(on)
        // Only what the device reports, never the wish. Recording the wish here made the
        // chip claim a route the call did not have, and every later tap then sent "off".
        _stats.update { it.copy(speaker = audio?.speaker ?: false) }
    }

    /** Stops the loops and releases the socket. Safe to call more than once. */
    fun stop() {
        sessionScope = null
        stopTone()
        jobs.forEach { it.cancel() }
        jobs.clear()
        audio?.stop()
        audio = null
        socket?.close()
        socket = null
        remote = null
        cryptoOut = null
        cryptoIn = null
        jitter.reset()
    }

    /** The counters, for the log line at the end of a call. */
    fun totals(): String = "${framesSent.get()} sent, ${framesReceived.get()} received, " +
        "${datagrams.get()} datagrams, ${cryptoIn?.rejected ?: 0} srtp rejected"

    /** True once both keys are in place, for the report's "is this encrypted" line. */
    val encrypted: Boolean get() = cryptoIn != null

    /** Datagrams that arrived, whatever became of them. */
    val receivedDatagrams: Long get() = datagrams.get()

    /** Packets whose SRTP tag did not verify. */
    val rejected: Int get() = cryptoIn?.rejected ?: 0

    private suspend fun CoroutineScope.toneLoop(device: CallAudioPort) {
        val frame = ShortArray(G711.FRAME_SAMPLES)
        var index = 0
        while (isActive) {
            Ringback.fill(frame, index)
            device.write(frame, frame.size)
            index += frame.size
            delay(G711.FRAME_MILLIS.toLong())
        }
    }

    private fun CoroutineScope.sendLoop(bound: DatagramSocket, device: CallAudioPort) {
        val pcm = ShortArray(G711.FRAME_SAMPLES)
        var first = true
        while (isActive) {
            val read = device.readFrame(pcm)
            if (first) note("send loop: first read returned $read")
            if (muted) pcm.fill(0)
            if (read <= 0) {
                note("microphone stopped returning frames ($read)")
                return
            }
            val payload = G711.encode(pcm, read)
            val packet = Rtp.build(
                payloadType = G711.PAYLOAD_TYPE,
                marker = first,
                sequence = sequence,
                timestamp = timestamp,
                ssrc = ssrc,
                payload = payload,
            )
            first = false
            val target = remote
            if (target == null) {
                note("no remote media address; stopping the send path")
                return
            }
            // Encryption happens after the frame is built and before it reaches the socket,
            // so the packet this loop reasons about stays the plain one.
            val wire = cryptoOut?.let { context ->
                try {
                    context.protect(packet)
                } catch (fault: Exception) {
                    if (isActive) note("SRTP could not protect a frame: ${describe(fault)}")
                    return
                }
            } ?: packet
            try {
                if (framesSent.get() < 3L) {
                    note("sending ${wire.size} bytes to ${target.address.hostAddress}:${target.port} from ${bound.localPort}")
                }
                bound.send(DatagramPacket(wire, wire.size, target))
            } catch (fault: Exception) {
                if (isActive) note("RTP send failed: ${describe(fault)}")
                return
            }
            sequence = Sequence.next(sequence)
            timestamp = (timestamp + read) and 0xFFFFFFFFL
            framesSent.incrementAndGet()
            sendPeak = max(sendPeak, G711.peak(pcm, read))
        }
    }

    private fun CoroutineScope.receiveLoop(bound: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                bound.receive(packet)
            } catch (fault: Exception) {
                if (isActive) note("RTP receive stopped: ${describe(fault)}")
                return
            }
            datagrams.incrementAndGet()
            // RTCP has its own packet format and its own protection, so it is set aside
            // before any SRTP work rather than being fed through the audio path.
            //
            // The whole byte is read, not the seven bit payload type: RTCP types 200 to 204
            // all have the top bit set, and masking it turns them into 72 to 76, which is
            // why an earlier version of this check could never match.
            val rawType = if (packet.length > 1) packet.data[1].toInt() and 0xFF else -1
            if (rawType in 200..204) {
                // RTCP arriving is independent proof that the media path is two-way.
                note("RTCP received: ${packet.length} bytes, packet type $rawType")
                continue
            }
            val context = cryptoIn
            val clear = if (context == null) {
                packet.data.copyOf(packet.length)
            } else {
                context.unprotect(packet.data, packet.length)
            }
            if (clear == null) {
                // The tag is checked before anything is decrypted, so this is a dropped
                // packet rather than a corrupt one. Noted once, because a stream of them
                // would flood the log and say nothing new.
                if (context != null && context.rejected == 1) {
                    note("an SRTP packet failed authentication and was dropped; later ones stay quiet")
                }
                continue
            }
            val parsed = Rtp.parse(clear) ?: continue
            if (parsed.payloadType != G711.PAYLOAD_TYPE) continue
            if (framesReceived.get() == 0L) {
                val verb = if (context == null) "received" else "decrypted"
                note("first media packet $verb (${parsed.payload.size} bytes, seq ${parsed.sequence})")
            }
            framesReceived.incrementAndGet()
            jitter.put(parsed.sequence, parsed.payload)
        }
    }

    /**
     * One frame every 20 ms, silence when the buffer has nothing. Writing silence rather
     * than skipping is what keeps the track's clock honest: a skipped write becomes an
     * underrun, and an underrun is audible as a click even when the next packet is on
     * time.
     */
    private suspend fun CoroutineScope.playoutLoop(device: CallAudioPort) {
        val silence = ShortArray(G711.FRAME_SAMPLES)
        var next = System.nanoTime()
        while (isActive) {
            next += G711.FRAME_MILLIS * 1_000_000L
            val payload = jitter.poll()
            val pcm = if (payload == null) silence else G711.decode(payload)
            val mixed = mixDtmfTone(pcm)
            device.write(mixed, mixed.size)
            receivePeak = max(receivePeak, G711.peak(mixed, mixed.size))
            val wait = next - System.nanoTime()
            if (wait > 0) {
                delay(wait / 1_000_000L)
            } else if (wait < -200_000_000L) {
                next = System.nanoTime() // badly behind, do not try to catch up in a burst
            }
        }
    }

    /**
     * Adds the keypad's own tone over whatever is being played.
     *
     * Mixed rather than played on a second track: two tracks at once is how a call develops
     * a gap in the middle of a digit.
     */
    private fun mixDtmfTone(pcm: ShortArray): ShortArray {
        synchronized(toneLock) {
            val tone = dtmfTone ?: return pcm
            val out = ShortArray(pcm.size)
            for (i in pcm.indices) {
                val at = dtmfAt + i
                out[i] = if (at < tone.size) {
                    (pcm[i] + tone[at]).coerceIn(-32768, 32767).toShort()
                } else {
                    pcm[i]
                }
            }
            dtmfAt += pcm.size
            if (dtmfAt >= tone.size) {
                dtmfTone = null
                dtmfAt = 0
            }
            return out
        }
    }

    private suspend fun CoroutineScope.statsLoop() {
        while (isActive) {
            delay(statsMillis)
            val counters = jitter.counters()
            _stats.value = MediaStats(
                framesSent = framesSent.get(),
                framesReceived = framesReceived.get(),
                lost = counters.lost,
                duplicates = counters.duplicates,
                late = counters.late,
                reordered = counters.reordered,
                held = counters.held,
                sendLevel = sendPeak,
                receiveLevel = receivePeak,
                localRtpPort = localPort,
                remoteMedia = remote?.let { "${it.address.hostAddress}:${it.port}" }.orEmpty(),
                echoCancelled = audio?.echoCancelled ?: false,
                // Not `audio?.speaker`: under Telecom that field is whatever this app last set,
                // which is nothing, so every 250 ms tick reset the route to earpiece. The flag
                // is owned by whoever reported the route last, and this tick only carries it.
                speaker = _stats.value.speaker,
            )
            sendPeak = 0f
            receivePeak = 0f
        }
    }
}
