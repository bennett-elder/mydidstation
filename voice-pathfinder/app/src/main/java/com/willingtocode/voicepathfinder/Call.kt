package com.willingtocode.voicepathfinder

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One outbound call.
 *
 * It owns a SIP transport of its own rather than borrowing a line's, because a line's
 * socket carries the registration and its keepalive, and closing the wrong socket would
 * take the registration with it. Both name the same sub-account, so the provider sees one
 * identity with a call leg beside it.
 *
 * Everything a call needs is here: the digest challenge, ACK, CANCEL, BYE, and one
 * [MediaSession]. It reports through a state flow, so a screen follows a call without
 * knowing what SIP is.
 */
internal class Call(
    private val credentials: SipCredentials,
    private val media: MediaSession,
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
) {

    /** The media this call is running, so a screen can show its counters. */
    val mediaStats: StateFlow<MediaStats> get() = media.stats

    /** Whether this call's media is SRTP. */
    val encrypted: Boolean get() = media.encrypted

    val receivedDatagrams: Long get() = media.receivedDatagrams

    val rejected: Int get() = media.rejected

    private val _state = MutableStateFlow<PathState>(PathState.Unregistered)
    val state: StateFlow<PathState> = _state

    private var link: SipLink? = null
    private var localIp = ""
    private var localPort = 0
    private var callId = ""
    private var fromTag = ""
    private var cseq = 0
    private var dialog: Dialog? = null
    private var invite: SipMessage? = null
    private var receiver: Job? = null
    private val pending = ConcurrentHashMap<String, Channel<SipMessage>>()
    private val random = SecureRandom()

    /** The inline key this call put in its offer, needed to open what comes back. */
    private val localCryptoKey = Sdp.newCryptoKey()

    /**
     * A confirmed dialog, holding what BYE has to repeat exactly.
     *
     * The `To` header is kept verbatim because its tag is what identifies the dialog. A
     * BYE that rebuilds `To` from the contact URI loses the tag and is answered 481, which
     * this app did once and logged as a hang-up anyway.
     */
    internal data class Dialog(
        val remoteTarget: String,
        val from: String,
        val to: String,
        val callId: String,
        var cseq: Int,
    )


    /** Opens the transport and places the call. Returns once the call is up or has failed. */
    suspend fun dial(number: String, mode: OfferMode) {
        try {
            open()
            val rtpPort = media.open()
            val uri = Provider.dialUri(credentials.host, number)
            val offer = Sdp.offer(
                System.currentTimeMillis() / 1000,
                localIp,
                rtpPort,
                mode,
                cryptoKey = localCryptoKey,
            )
            _state.value = PathState.Dialing(number)
            note("dialing $number as ${credentials.username}, offering ${mode.label}, RTP port $rtpPort")

            var authorization: Pair<String, String>? = null
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val headers = mutableListOf(
                    "Via" to via(branch()),
                    "Max-Forwards" to "70",
                    "From" to "<${Provider.userUri(credentials.host, credentials.username)}>;tag=$fromTag",
                    "To" to "<$uri>",
                    "Call-ID" to callId,
                    "CSeq" to "${nextCSeq()} INVITE",
                    "Contact" to Provider.contactUri(
                        credentials.host,
                        credentials.username,
                        localIp,
                        localPort,
                        if (credentials.tls) "tls" else null,
                    ),
                    "Allow" to "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO",
                    "Content-Type" to "application/sdp",
                    "User-Agent" to SipMessage.USER_AGENT,
                )
                authorization?.let { headers += it }
                val request = SipMessage.request("INVITE", uri, headers, offer)
                invite = request

                val response = transact(request, INVITE_TIMEOUT_MILLIS) { provisional(number, it) }
                if (response == null) {
                    sendCancelFor(request)
                    throw SipFault("no answer to INVITE after ${INVITE_TIMEOUT_MILLIS / 1000} s")
                }
                val status = response.start.status ?: throw SipFault("malformed response to INVITE")
                if (status == 200) {
                    media.stopTone()
                    ack(request, response, non2xx = false)
                    startMedia(number, response, mode, rtpPort)
                    return
                }
                if (status !in 300..699) {
                    ack(request, response, non2xx = false)
                    throw SipFault("INVITE answered $status ${response.start.reason}")
                }
                ack(request, response, non2xx = true)
                if (status == 401 || status == 407) {
                    val challengeName = if (status == 401) "WWW-Authenticate" else "Proxy-Authenticate"
                    val headerName = if (status == 401) "Authorization" else "Proxy-Authorization"
                    val value = response.header(challengeName)
                        ?: throw SipFault("$status without a $challengeName header")
                    val challenge = Digest.parse(value)
                        ?: throw SipFault("$challengeName is not Digest: ${value.take(48)}")
                    authorization = headerName to Digest.authorization(
                        challenge = challenge,
                        username = credentials.username,
                        password = credentials.password,
                        method = "INVITE",
                        uri = uri,
                        cnonce = randomHex(8),
                    )
                    nextCSeq()
                    note("$status to INVITE, retrying with $headerName")
                    continue
                }
                throw SipFault("INVITE answered $status ${response.start.reason}")
            }
            throw SipFault("gave up after repeated INVITE challenges")
        } catch (fault: Exception) {
            end("call failed: ${describe(fault)}", failed = true)
        }
    }

    /** BYE if the call is up, CANCEL while it is still ringing. */
    suspend fun hangUp() {
        val settled = dialog
        val ringing = invite
        if (settled != null) {
            bye()
        } else if (ringing != null) {
            sendCancelFor(ringing)
            end("canceled before it was answered", failed = false)
        }
    }

    /** Sends one keypress to the far end, as RFC 4733 events. */
    fun sendDtmf(key: Char) = media.sendDtmf(key)

    /** Silences the microphone without stopping the stream. */
    fun setMuted(on: Boolean) = media.setMuted(on)

    /** Moves this call between the speaker and the earpiece. */
    fun setSpeaker(on: Boolean) = media.setSpeaker(on)

    /** Ends the call and releases everything, whether or not it was up. */
    fun close() {
        media.stop()
        receiver?.cancel()
        receiver = null
        pending.clear()
        link?.close()
        link = null
        _state.value = PathState.Unregistered
    }

    private fun open() {
        val binding = LocalNetwork.ipv4() ?: throw SipFault("no IPv4 network to place a call on")
        localIp = binding.address
        val transport = if (credentials.tls) {
            TlsLink(credentials.host, credentials.port)
        } else {
            val target = InetAddress.getByName(credentials.host)
            UdpLink(DatagramSocket().apply { soTimeout = 0 }, InetSocketAddress(target, credentials.port))
        }
        link = transport
        localPort = transport.localPort
        callId = "${randomHex(10)}@$localIp"
        fromTag = randomHex(8)
        cseq = randomInt(1, 5000)
        note("call transport ${transport.transport} to ${credentials.host}:${credentials.port} from $localIp:$localPort")
        receiver = scope.launch { receive(transport) }
    }

    private fun receive(transport: SipLink) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val incoming = transport.receive() ?: break
                val message = SipMessage.parse(incoming.bytes) ?: continue
                note("<<< ${redact(String(message.toBytes(), Charsets.ISO_8859_1))}")
                if (message.start.isRequest) {
                    handleRequest(message, incoming.source)
                    continue
                }
                val pair = SipMessage.cseqOf(message) ?: continue
                pending["${pair.first} ${pair.second}"]?.trySend(message)
            }
        }
    }

    private fun handleRequest(message: SipMessage, source: InetSocketAddress?) {
        when (message.start.method) {
            "OPTIONS" -> reply(message, source, 200, "OK")
            "BYE" -> {
                reply(message, source, 200, "OK")
                note("the far end hung up")
                end("the far end hung up", failed = false)
            }
            // This is a call leg, so an INVITE arriving on it is a re-INVITE or a glare
            // case, neither of which this build answers. Saying so beats silence.
            else -> reply(message, source, 501, "Not Implemented")
        }
    }

    private fun reply(request: SipMessage, source: InetSocketAddress?, code: Int, reason: String) {
        send(SipMessage.replyingTo(request, code, reason, toTag = randomHex(6)), source)
    }

    private fun send(message: SipMessage, target: InetSocketAddress? = null) {
        val current = link ?: throw SipFault("the call transport is closed")
        val bytes = message.toBytes()
        note(">>> ${redact(String(bytes, Charsets.ISO_8859_1))}")
        current.send(bytes, target)
    }

    /**
     * One request/response exchange, with the retransmission UDP needs.
     *
     * A provisional response stops the resends, which RFC 3261 requires: after one, the
     * caller waits and the server owns the timers. Without that rule a call rings at the
     * far end while this side INVITEs it again every four seconds.
     */
    private suspend fun transact(
        request: SipMessage,
        timeoutMillis: Long,
        onProvisional: (SipMessage) -> Unit = {},
    ): SipMessage? {
        val pair = SipMessage.cseqOf(request) ?: throw SipFault("request without CSeq")
        val key = "${pair.first} ${pair.second}"
        val channel = Channel<SipMessage>(Channel.UNLIMITED)
        pending[key] = channel
        val deadline = System.currentTimeMillis() + timeoutMillis
        var interval = FIRST_RETRANSMIT_MILLIS
        var heard = false
        try {
            send(request)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    note("$key timed out after ${timeoutMillis / 1000} s")
                    return null
                }
                val wait = if (heard) remaining else minOf(interval, remaining)
                val next = withTimeoutOrNull(wait) { channel.receive() }
                if (next == null) {
                    if (heard || link?.reliable == true) {
                        note("$key timed out after ${timeoutMillis / 1000} s")
                        return null
                    }
                    send(request)
                    interval = minOf(interval * 2, MAX_RETRANSMIT_MILLIS)
                    continue
                }
                val status = next.start.status ?: continue
                if (status in 100..199) {
                    heard = true
                    onProvisional(next)
                    continue
                }
                return next
            }
        } finally {
            pending.remove(key)
        }
    }


    private fun provisional(number: String, message: SipMessage) {
        when (val status = message.start.status ?: return) {
            100 -> _state.value = PathState.Dialing(number)
            180, 183 -> {
                _state.value = PathState.Ringing(number)
                note("ringing ($status)")
                // The caller hears the tone, not the network: a 180 carries no audio at all.
                media.beginTone(scope)
                val early = Sdp.parse(message.body)
                if (early != null) {
                    note("early media: ${early.protocol} ${early.codec?.encoding ?: "unknown"} at ${early.address}:${early.port}")
                }
            }
            else -> note("provisional $status")
        }
    }

    /**
     * ACK, whose branch rules are opposite in the two cases and both mandatory: a non-2xx
     * ACK reuses the INVITE's branch, a 2xx ACK is a new transaction with its own. Getting
     * this backwards produces a retransmitted 200 and, eventually, a BYE.
     */
    private fun ack(invite: SipMessage, response: SipMessage, non2xx: Boolean) {
        val inviteCseq = SipMessage.cseqOf(invite) ?: return
        val inviteBranch = SipMessage.branchOf(invite.allHeaders("Via").firstOrNull().orEmpty())
        val target = if (non2xx) {
            invite.start.uri ?: return
        } else {
            response.header("Contact")?.let { bracket(it) } ?: (invite.start.uri ?: return)
        }
        val headers = listOf(
            "Via" to via(if (non2xx) inviteBranch ?: branch() else branch()),
            "Max-Forwards" to "70",
            "From" to (invite.header("From") ?: return),
            "To" to (response.header("To") ?: invite.header("To") ?: return),
            "Call-ID" to (invite.header("Call-ID") ?: return),
            "CSeq" to "${inviteCseq.first} ACK",
            "User-Agent" to SipMessage.USER_AGENT,
        )
        send(SipMessage.request("ACK", target, headers))
    }

    private fun sendCancelFor(invite: SipMessage) {
        val inviteCseq = SipMessage.cseqOf(invite) ?: return
        val inviteBranch = SipMessage.branchOf(invite.allHeaders("Via").firstOrNull().orEmpty()) ?: return
        val headers = listOf(
            "Via" to via(inviteBranch),
            "Max-Forwards" to "70",
            "From" to (invite.header("From") ?: return),
            "To" to (invite.header("To") ?: return),
            "Call-ID" to (invite.header("Call-ID") ?: return),
            "CSeq" to "${inviteCseq.first} CANCEL",
            "User-Agent" to SipMessage.USER_AGENT,
        )
        invite.start.uri?.let { target -> runCatching { send(SipMessage.request("CANCEL", target, headers)) } }
    }

    private suspend fun bye() {
        val settled = dialog ?: return
        val response = transact(byeRequest(settled), BYE_TIMEOUT_MILLIS)
        val status = response?.start?.status
        if (status != null && status !in 200..299) {
            // A rejected BYE leaves the far end holding a call, so it is reported rather
            // than followed by a cheerful "hung up".
            note("BYE answered $status ${response.start.reason}")
        }
        end("hung up", failed = false)
    }

    /** Builds the request from the confirmed dialog. */
    private fun byeRequest(settled: Dialog): SipMessage =
        SipMessage.request("BYE", settled.remoteTarget, byeHeaders(settled, via(branch())))

    /**
     * The headers of an in-dialog BYE.
     *
     * Separate from the request so a test can check them without a network. The peer's To
     * tag and the dialog's own CSeq are the two things that decide whether the provider
     * accepts it, and this app once sent neither.
     */
    internal fun byeHeaders(settled: Dialog, via: String): List<Pair<String, String>> = listOf(
        "Via" to via,
        "Max-Forwards" to "70",
        "From" to settled.from,
        "To" to settled.to,
        "Call-ID" to settled.callId,
        "CSeq" to "${settled.cseq + 1} BYE",
        "User-Agent" to SipMessage.USER_AGENT,
    )

    private fun startMedia(number: String, response: SipMessage, mode: OfferMode, port: Int) {
        val answer = Sdp.parse(response.body)
            ?: throw SipFault("the 200 OK has no usable SDP: ${response.body.replace("\r\n", " ").take(80)}")
        val codec = answer.codec
        val name = codec?.let { "${it.encoding}/${it.clockRate}" } ?: "unknown"
        note("answer: ${answer.protocol}, $name, ptime ${answer.ptime ?: "-"}")
        note("remote media ${answer.address}:${answer.port}, local RTP $port")

        val address = answer.address ?: throw SipFault("the SDP answer has no connection address")
        if (answer.port <= 0) throw SipFault("the SDP answer refuses the audio stream (port ${answer.port})")
        val target = InetSocketAddress(InetAddress.getByName(address), answer.port)
        dialog = Dialog(
            remoteTarget = response.header("Contact")?.let { bracket(it) }
                ?: invite?.start?.uri.orEmpty(),
            from = response.header("From") ?: invite?.header("From").orEmpty(),
            to = response.header("To") ?: invite?.header("To").orEmpty(),
            callId = response.header("Call-ID") ?: callId,
            cseq = SipMessage.cseqOf(invite ?: response)?.first ?: nextCSeq(),
        )

        if (answer.encrypted) {
            val theirs = answer.crypto.firstNotNullOfOrNull { Sdp.inlineKey(it) }
            if (theirs == null || !media.encryptWith(Sdp.unbase64(localCryptoKey), theirs)) {
                note("OUTCOME: encrypted media (${answer.protocol}) but the key material is not usable")
                _state.value = PathState.Connected(number, name, true, audio = false, at = System.currentTimeMillis())
                return
            }
            note(
                "SRTP ${Srtp.SUITE}: both keys from SDP, ${Srtp.TAG_BYTES} byte tags, " +
                    "server chose $name from ${mode.label}",
            )
        } else {
            note("OUTCOME: clear media (${answer.protocol}), server chose $name from ${mode.label}")
        }
        if (codec?.payloadType != G711.PAYLOAD_TYPE) {
            note("OUTCOME: the server chose $name, which this build does not decode (offered ${mode.label})")
            _state.value = PathState.Connected(number, name, false, audio = false, at = System.currentTimeMillis())
            return
        }
        if (!media.begin(scope, target)) {
            _state.value = PathState.Connected(number, name, false, audio = false, at = System.currentTimeMillis())
            return
        }
        _state.value = PathState.Connected(number, name, false, audio = true, at = System.currentTimeMillis())
        note("call is up; speak")
    }

    private fun end(reason: String, failed: Boolean) {
        note("media totals: ${media.totals()}")
        media.stop()
        dialog = null
        invite = null
        if (reason.isNotEmpty()) note(reason)
        _state.value = if (failed) PathState.Failed(reason) else PathState.Ended(reason)
    }

    private fun nextCSeq(): Int = synchronized(this) { ++cseq }

    private fun branch(): String = "z9hG4bK" + randomHex(10)

    private fun via(branch: String, ip: String = localIp, port: Int = localPort): String {
        val current = link ?: throw SipFault("the call transport is closed")
        val rport = if (current.reliable) "" else ";rport"
        return "SIP/2.0/${current.transport} $ip:$port;branch=$branch$rport"
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomInt(from: Int, to: Int): Int = from + random.nextInt(to - from + 1)

    private fun bracket(value: String): String {
        val open = value.indexOf('<')
        val close = value.indexOf('>')
        return if (open >= 0 && close > open) value.substring(open + 1, close) else value.trim()
    }

    /** The log is pasted into notes, so the response hash goes. It is not the password. */
    private fun redact(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("Authorization:", ignoreCase = true)) "Authorization: <redacted>" else line
        }

    private companion object {
        const val INVITE_TIMEOUT_MILLIS = 45_000L
        const val BYE_TIMEOUT_MILLIS = 6_000L
        const val FIRST_RETRANSMIT_MILLIS = 500L
        const val MAX_RETRANSMIT_MILLIS = 4_000L
    }
}
