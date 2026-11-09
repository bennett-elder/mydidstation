package com.willingtocode.voicepathfinder

/**
 * Playback ordering for RTP, with a synthetic clock so it can be tested without audio.
 *
 * The problem it exists for: packets arrive on a schedule the network chooses, and
 * `AudioTrack` wants one frame every twenty milliseconds. Playing each packet on
 * arrival reproduces every jitter spike as an audible gap. So packets are held briefly
 * and played on their own schedule.
 *
 * The rules are deliberately blunt, because a probe that hides packet loss is worse
 * than one that stutters:
 *
 * - Start after [depth] packets, or after [prebufferMillis] with fewer, so the first
 *   second of a call is not silence waiting for a buffer that never fills.
 * - Play the next expected sequence number. If it has not arrived, wait up to
 *   [skipMillis], then skip to the earliest packet actually held and count the gap as
 *   lost. Waiting forever is how a single dropped packet turns into a frozen call.
 * - A packet behind the play head is late and is dropped, not played out of order.
 *
 * The counters are the probe's evidence rather than diagnostics: they are what says
 * whether a bad-sounding call lost packets or merely lost time.
 */
internal class JitterBuffer(
    private val depth: Int = DEFAULT_DEPTH,
    private val prebufferMillis: Long = DEFAULT_PREBUFFER_MILLIS,
    private val skipMillis: Long = DEFAULT_SKIP_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Put { ACCEPTED, DUPLICATE, LATE }

    data class Counters(
        val played: Long,
        val lost: Long,
        val duplicates: Long,
        val late: Long,
        val reordered: Long,
        val held: Int,
    )

    private val lock = Any()
    private val packets = HashMap<Int, ByteArray>()

    private var started = false
    private var nextSequence = 0
    private var firstArrivalAt = 0L

    /**
     * When the expected packet went missing, as a timestamp, with -1 for "not waiting".
     * A nullable timestamp would be cleaner, but the clock in a test can start at zero,
     * and zero is exactly what a "no value" sentinel must not also mean.
     */
    private var missingSince = -1L

    private var highest = 0
    private var hasHighest = false

    private var played = 0L
    private var lost = 0L
    private var duplicates = 0L
    private var late = 0L
    private var reordered = 0L

    fun put(sequence: Int, payload: ByteArray): Put = synchronized(lock) {
        if (packets.containsKey(sequence)) {
            duplicates++
            return Put.DUPLICATE
        }
        if (started && Sequence.distance(sequence, nextSequence) < 0) {
            late++
            return Put.LATE
        }
        if (!started && packets.isEmpty()) {
            firstArrivalAt = now()
            nextSequence = sequence
        }
        // Standard RTP definition: a packet that arrives behind the highest one seen is
        // out of order, whether or not playback has started. Counting holes instead
        // misses exactly the two-packet reorder that matters most.
        if (hasHighest && Sequence.distance(sequence, highest) < 0) reordered++
        if (!hasHighest || Sequence.distance(sequence, highest) > 0) {
            highest = sequence
            hasHighest = true
        }
        packets[sequence] = payload
        return Put.ACCEPTED
    }

    /** The payload to play now, or null for a frame of silence. Call once per 20 ms. */
    fun poll(): ByteArray? = synchronized(lock) {
        if (!started) {
            if (packets.isEmpty()) return null
            val waited = now() - firstArrivalAt
            if (packets.size < depth && waited < prebufferMillis) return null
            started = true
            nextSequence = packets.keys.minByOrNull { Sequence.distance(it, nextSequence) } ?: nextSequence
        }

        packets.remove(nextSequence)?.let { payload ->
            nextSequence = Sequence.next(nextSequence)
            missingSince = -1L
            played++
            return payload
        }

        val at = now()
        if (missingSince < 0) missingSince = at
        if (packets.isNotEmpty() && at - missingSince >= skipMillis) {
            val earliest = packets.keys.minByOrNull { Sequence.distance(it, nextSequence) } ?: return null
            lost += Sequence.distance(earliest, nextSequence)
            nextSequence = Sequence.next(earliest)
            missingSince = -1L
            played++
            return packets.remove(earliest)
        }
        return null
    }

    fun counters(): Counters = synchronized(lock) {
        Counters(
            played = played,
            lost = lost,
            duplicates = duplicates,
            late = late,
            reordered = reordered,
            held = packets.size,
        )
    }

    fun reset() = synchronized(lock) {
        packets.clear()
        started = false
        nextSequence = 0
        firstArrivalAt = 0L
        missingSince = -1L
        highest = 0
        hasHighest = false
        played = 0
        lost = 0
        duplicates = 0
        late = 0
        reordered = 0
    }

    companion object {
        /** Three packets is 60 ms of audio, about the smallest buffer a human calls clear. */
        const val DEFAULT_DEPTH = 3
        const val DEFAULT_PREBUFFER_MILLIS = 40L
        const val DEFAULT_SKIP_MILLIS = 60L
    }
}
