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
    data class Registered(val grantedSeconds: Int) : LineState
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
                state.value = LineState.Registered(granted)
                log("$account registered, granted $granted s")
                while (isActive) {
                    delay(maxOf(MIN_REFRESH_MILLIS, granted * 800L))
                    granted = register()
                    state.value = LineState.Registered(granted)
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
        if (created is TlsLink) {
            // A TLS connection behind a NAT needs traffic to survive the idle timer, and a
            // registration refresh measured in minutes is far too slow to do that.
            keepalive = scope.launch {
                while (isActive) {
                    delay(KEEPALIVE_MILLIS)
                    runCatching { created.keepalive() }
                }
            }
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
                // Only responses to this line's own requests matter yet. A request arriving
                // here is an incoming call, which is the next phase.
                val cseqPair = SipMessage.cseqOf(message) ?: continue
                pending["${cseqPair.first} ${cseqPair.second}"]?.trySend(message)
            }
        }
    }

    private suspend fun request(message: SipMessage, timeoutMillis: Long): SipMessage? {
        val cseqPair = SipMessage.cseqOf(message) ?: throw SipFault("request without CSeq")
        val key = "${cseqPair.first} ${cseqPair.second}"
        val channel = Channel<SipMessage>(Channel.BUFFERED)
        pending[key] = channel
        return try {
            send(message)
            var answer = withTimeoutOrNull(timeoutMillis / 2) { channel.receive() }
            if (answer == null && link?.reliable == false) {
                send(message)
                answer = withTimeoutOrNull(timeoutMillis / 2) { channel.receive() }
            }
            answer
        } finally {
            pending.remove(key)
        }
    }

    private fun send(message: SipMessage) {
        val current = link ?: throw SipFault("the line is closed")
        current.send(message.toBytes())
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
        const val MIN_REFRESH_MILLIS = 30_000L
    }
}
