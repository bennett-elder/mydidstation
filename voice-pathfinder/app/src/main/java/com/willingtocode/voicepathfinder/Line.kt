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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What one registered line is doing. */
internal sealed interface LineState {
    data object Idle : LineState
    data class Preparing(val step: String) : LineState
    /**
     * @param atMillis when the provider granted it, so the screen can say how long is left
     *   rather than how long there was at the start.
     */
    data class Registered(val grantedSeconds: Int, val atMillis: Long = 0L) : LineState
    data class Failed(val reason: String) : LineState
    data object Stopped : LineState
}

/**
 * One sub-account, registered on its own transport.
 *
 * The provider keeps a binding per sub-account, so several can be live at once: two numbers
 * routed to two sub-accounts mean two registrations, both able to receive. The engine used
 * to hold exactly one, which was right for a probe and wrong for a phone.
 *
 * Each line owns its socket, its transaction table and its refresh timer. Nothing is shared
 * with another line, so one failing cannot disturb the others and a line can be stopped
 * without touching the rest.
 */
internal class Line(
    val account: String,
    val subId: String,
    /** The sub-account this line registers, which is also what a call from it presents. */
    val credentials: SipCredentials,
    private val log: (String) -> Unit,
) {

    val state = MutableStateFlow<LineState>(LineState.Idle)

    private lateinit var scope: CoroutineScope
    private var link: SipLink? = null
    private var receiver: Job? = null
    private var keepalive: Job? = null
    private var refresher: Job? = null
    private val pending = ConcurrentHashMap<String, Channel<SipMessage>>()

    /** Chosen to be under the ring delay that used to follow a lost answer. */
    private val RESEND_MILLIS = 600L

    /** Long enough that a network flap does not turn into a registration storm. */
    private val RESTART_DELAY_MILLIS = 2_000L
    private var localIp = ""
    private var callId = ""
    private var fromTag = ""
    private var cseq = 0
    private val random = SecureRandom()

    /** Registers, then keeps the binding alive at 80% of whatever was granted. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        refresher?.cancel()
        refresher = scope.launch {
            try {
                state.value = LineState.Preparing("opening ${credentials.transport} to ${credentials.host}")
                open()
                var granted = register()
                state.value = LineState.Registered(granted, System.currentTimeMillis())
                log("$account registered, granted $granted s")
                while (isActive) {
                    delay(maxOf(MIN_REFRESH_MILLIS, granted * 800L))
                    granted = register()
                    state.value = LineState.Registered(granted, System.currentTimeMillis())
                }
            } catch (fault: Exception) {
                val reason = fault.message ?: fault.toString()
                state.value = LineState.Failed(reason)
                log("$account is not registered: $reason")
                close()
            }
        }
    }

    fun stop() {
        refresher?.cancel()
        refresher = null
        // Remove the binding rather than waiting for it to expire. Same Call-ID and Contact
        // as the registration, with Expires 0, which is what RFC 3261 section 10.2.2
        // describes and what every polite client does when it logs out. Best effort: if the
        // network is gone, the expiry still cleans up.
        if (::scope.isInitialized && link != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val response = request(registerRequest(null, ++cseq, expires = 0), DEREGISTER_TIMEOUT_MILLIS)
                    log(
                        if (response == null) {
                            "$account did not answer the deregistration; the binding will expire"
                        } else {
                            "$account deregistered (${response.start.status})"
                        },
                    )
                }.onFailure { log("$account deregistration failed: ${it.message}") }
                close()
            }
        } else {
            close()
        }
        if (state.value !is LineState.Failed) state.value = LineState.Stopped
    }

    private suspend fun register(): Int {
        var authorization: Pair<String, String>? = null
        var cseqValue = ++cseq
        repeat(3) { attempt ->
            val response = request(registerRequest(authorization, cseqValue), REGISTER_TIMEOUT_MILLIS)
                ?: throw SipFault("no answer to REGISTER from ${credentials.host}:${credentials.port}")
            when (val status = response.start.status ?: -1) {
                200 -> {
                    val granted = grantedExpiry(response)
                    log(
                        "$account asked for ${credentials.expiresSeconds} s, granted $granted s " +
                            "(Expires: ${response.header("Expires")}, " +
                            "Contact: ${response.header("Contact")?.take(90)})",
                    )
                    return granted
                }
                401, 407 -> {
                    val challengeName =
                        if (status == 401) "WWW-Authenticate" else "Proxy-Authenticate"
                    val headerName = if (status == 401) "Authorization" else "Proxy-Authorization"
                    val value = response.header(challengeName)
                        ?: throw SipFault("$status without a $challengeName header")
                    val challenge = Digest.parse(value)
                        ?: throw SipFault("$challengeName is not Digest: ${value.take(48)}")
                    authorization = headerName to Digest.authorization(
                        challenge = challenge,
                        username = credentials.username,
                        password = credentials.password,
                        method = "REGISTER",
                        uri = Provider.serverUri(credentials.host),
                        cnonce = randomHex(8),
                    )
                    cseqValue = ++cseq
                    if (attempt == 2) {
                        throw SipFault("the server keeps answering $status; check the password")
                    }
                }
                else -> throw SipFault("REGISTER answered $status ${response.start.reason}")
            }
        }
        throw SipFault("could not register")
    }

    private fun registerRequest(
        authorization: Pair<String, String>?,
        cseqValue: Int,
        expires: Int = credentials.expiresSeconds,
    ): SipMessage {
        val identity = Provider.userUri(credentials.host, credentials.username)
        val current = link ?: throw SipFault("the line is closed")
        val rport = if (current.reliable) "" else ";rport"
        val headers = mutableListOf(
            "Via" to "SIP/2.0/${current.transport} $localIp:${current.localPort};" +
                "branch=${branch()}$rport",
            "Max-Forwards" to "70",
            "From" to "<$identity>;tag=$fromTag",
            "To" to "<$identity>",
            "Call-ID" to callId,
            "CSeq" to "$cseqValue REGISTER",
            "Contact" to Provider.contactUri(
                credentials.host,
                credentials.username,
                localIp,
                current.localPort,
                if (credentials.tls) "tls" else null,
            ),
            "Expires" to expires.toString(),
            "User-Agent" to SipMessage.USER_AGENT,
        )
        authorization?.let { headers += it }
        return SipMessage.request("REGISTER", Provider.serverUri(credentials.host), headers)
    }

    /** The expiry the registrar actually granted, which is often less than was asked. */
    private fun grantedExpiry(response: SipMessage): Int {
        response.header("Expires")?.trim()?.toIntOrNull()?.let { if (it > 0) return it }
        response.header("Contact")?.let { contact ->
            Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
                .find(contact)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { if (it > 0) return it }
        }
        return credentials.expiresSeconds
    }

    private fun open() {
        val binding = LocalNetwork.ipv4()
            ?: throw SipFault("no IPv4 address; connect Wi-Fi or cellular first")
        localIp = binding.address
        val created: SipLink = if (credentials.tls) {
            TlsLink(credentials.host, credentials.port)
        } else {
            UdpLink(
                DatagramSocket().apply { soTimeout = 0 },
                InetSocketAddress(InetAddress.getByName(credentials.host), credentials.port),
            )
        }
        link = created
        callId = "${randomHex(10)}@$localIp"
        fromTag = randomHex(8)
        cseq = random.nextInt(5000) + 1
        log(
            "$account: ${created.transport} to ${credentials.host}:${credentials.port} " +
                "from $localIp:${created.localPort}",
        )
        receive(created)
        // Both transports need this. A TLS connection has to survive the NAT idle timer, and a
        // UDP one has the same problem from the other side: the registration refresh runs at 80%
        // of the grant, which is 48 minutes for an hour, and a UDP mapping is gone in under a
        // minute. After that the provider's INVITE is addressed to a closed hole and dropped
        // without an error, while its own view still says the client is registered.
        keepalive = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MILLIS)
                val writeFailed = runCatching {
                    if (created is TlsLink) created.keepalive() else created.send(KEEPALIVE_PING, null)
                }.isFailure
                // A write that fails means the socket is gone, and the provider is still holding
                // the contact it was registered with. That is the honest signal, and it was
                // ignored here for weeks: this `runCatching` had no `else`.
                //
                // A LAN address change is deliberately not a trigger. The provider never talks
                // to the private address in our Contact; it uses the public endpoint the
                // registration came from. Moving between two private addresses means nothing
                // to it, and treating it as stale would re-register on every DHCP renewal.
                if (!writeFailed) continue
                log("$account: the socket to ${credentials.host} went quiet, so the line is registering again")
                restart()
                return@launch
            }
        }
    }

    /**
     * Throws this attempt away and registers again.
     *
     * Launched rather than done inline: it runs from the keepalive job, which `close()` cancels,
     * and a cancelled coroutine must not be the one that starts its replacement.
     */
    private fun restart() {
        val scope = scope ?: return
        state.value = LineState.Preparing("reconnecting")
        scope.launch {
            delay(RESTART_DELAY_MILLIS)
            runCatching { close() }
            start(scope)
        }
    }

    private fun close() {
        receiver?.cancel()
        receiver = null
        keepalive?.cancel()
        keepalive = null
        pending.values.forEach { it.close() }
        pending.clear()
        link?.close()
        link = null
    }

    private fun receive(current: SipLink) {
        receiver = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val incoming = current.receive() ?: break
                val message = SipMessage.parse(incoming.bytes) ?: continue
                // Every message in, unfiltered. A reply the provider sends to something we sent
                // (a 4xx to our 200, for instance) matched no pending request and vanished
                // without a trace, which is how a broken answer looks like no answer at all.
                log("$account <<< ${message.start.firstLine.take(120)}")
                if (message.start.isRequest) {
                    // A request on this socket is a call: the provider only sends INVITE and its
                    // in-dialog requests to a registered contact. Responses are matched by
                    // CSeq below, so the two cannot be confused.
                    onRequest(message)
                    continue
                }
                val cseqPair = SipMessage.cseqOf(message) ?: continue
                pending["${cseqPair.first} ${cseqPair.second}"]?.trySend(message)
            }
        }
    }

    /**
     * Sends a request and waits for its response, resending until the time is up.
     *
     * It used to resend only on an unreliable transport, on the assumption that TCP and TLS
     * cannot lose a message. Measured on 2026-11-10: a `200 OK` and a `BYE` were both written,
     * logged as sent, and never arrived. A dialog that ends with an unacknowledged BYE leaves
     * the far end believing the call is still up, so the request is repeated either way.
     */
    internal suspend fun request(message: SipMessage, timeoutMillis: Long): SipMessage? {
        val cseqPair = SipMessage.cseqOf(message) ?: throw SipFault("request without CSeq")
        val key = "${cseqPair.first} ${cseqPair.second}"
        val channel = Channel<SipMessage>(Channel.BUFFERED)
        pending[key] = channel
        return try {
            var answer: SipMessage? = null
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (answer == null && System.currentTimeMillis() < deadline) {
                send(message)
                answer = withTimeoutOrNull(RESEND_MILLIS) { channel.receive() }
            }
            answer
        } finally {
            pending.remove(key)
        }
    }

    /** The transport this line is on right now, for a Via that matches the socket. */
    val transport: String get() = link?.transport ?: credentials.transport

    /** The local SIP port, which is not the RTP port and has been mistaken for it. */
    val sipLocalPort: Int get() = link?.localPort ?: 0

    /** Answers an incoming call on this line, and nothing else. */
    internal fun send(message: SipMessage) {
        val current = link ?: throw SipFault("the line is closed")
        val bytes = message.toBytes()
        log("$account >>> ${message.start.firstLine.take(120)} (${bytes.size} bytes, body ${message.body.toByteArray(Charsets.ISO_8859_1).size})")
        current.send(bytes)
    }

    /** Where an incoming request goes. Set by the owner, which decides what to do with it. */
    @Volatile
    var onRequest: (SipMessage) -> Unit = {}

    /** A tag for the dialog this line would create if it answers. */
    fun newTag(): String = randomHex(8)

    /**
     * This line's contact, for a 200 OK to an INVITE.
     *
     * RFC 3261 requires a Contact in a 2xx to an INVITE: it is the address the caller sends
     * its ACK to. Without it the provider never acknowledged our answer, kept ringing the
     * caller, and eventually cancelled a call we had already picked up.
     */
    internal fun contact(): String? {
        val current = link ?: return null
        return Provider.contactUri(
            credentials.host,
            credentials.username,
            localIp,
            current.localPort,
            if (credentials.tls) "tls" else null,
        )
    }

    private fun branch(): String = "z9hG4bK" + randomHex(10)

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REGISTER_TIMEOUT_MILLIS = 16_000L
        const val DEREGISTER_TIMEOUT_MILLIS = 5_000L
        const val KEEPALIVE_MILLIS = 25_000L

        /** CRLFCRLF: a SIP ping. Servers ignore it and NATs count it as traffic. */
        val KEEPALIVE_PING = "\r\n\r\n".toByteArray()
        const val MIN_REFRESH_MILLIS = 30_000L
    }
}

