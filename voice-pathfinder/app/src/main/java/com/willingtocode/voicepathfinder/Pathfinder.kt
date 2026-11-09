package com.willingtocode.voicepathfinder

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the probe is doing, in the words the screen shows. */
sealed interface PathState {
    data object Unregistered : PathState
    data class Registering(val attempt: Int) : PathState
    data class Registered(val grantedSeconds: Int, val at: Long) : PathState
    data class RegistrationFailed(val reason: String) : PathState
    data class Dialing(val number: String) : PathState
    data class Ringing(val number: String) : PathState
    data class Connected(
        val number: String,
        val codec: String,
        val encrypted: Boolean,
        val audio: Boolean,
        val at: Long,
    ) : PathState
    data class Ended(val reason: String) : PathState
    data class Failed(val reason: String) : PathState
}

/** Counters for the call, refreshed four times a second rather than per packet. */
data class MediaStats(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val lost: Long = 0,
    val duplicates: Long = 0,
    val late: Long = 0,
    val reordered: Long = 0,
    val held: Int = 0,
    val sendLevel: Float = 0f,
    val receiveLevel: Float = 0f,
    val localRtpPort: Int = 0,
    val remoteMedia: String = "",
    val echoCancelled: Boolean = false,
    val speaker: Boolean = false,
)

/**
 * The answers to the questions this app exists to answer, filled in while it runs and
 * copied out as text. Keeping them apart from the log is deliberate: a log is evidence,
 * and this is the finding.
 */
data class ProbeAnswers(
    val offered: String = "",
    val protocol: String = "",
    val encrypted: Boolean = false,
    val cryptoLines: Int = 0,
    val negotiated: String = "",
    val payloadTypes: String = "",
    val ptime: Int? = null,
    val remoteMedia: String = "",
    val grantedSeconds: Int = 0,
    val refreshSeconds: Long = 0,
    val inviteMillis: Long = -1,
    val incomingInvites: Int = 0,
)

sealed interface AccountListState {
    data object Idle : AccountListState
    data object Loading : AccountListState
    data object Loaded : AccountListState
    data class Failed(val reason: String) : AccountListState
}

/** What the provider's call records say, once they have been asked for. */
data class CallsView(
    val loading: Boolean = false,
    val error: String? = null,
    val records: List<CallRecord> = emptyList(),
)

/**
 * Where the credentials came from, stated so the screen can be honest about it.
 *
 * [generatedPassword] means this app wrote to the sub-account, which is the one action
 * here with consequences outside the phone. It is a finding, not a detail.
 */
data class ProvisionDetail(
    val generatedPassword: Boolean,
    val serverSource: String,
    val registered: Boolean,
    val registeredOn: String,
    /** UDP, TCP or TLS, as the provider reports it for whatever device is registered. */
    val transport: String,
    val sipTraffic: Boolean,
    val codecs: String,
    val maxExpiry: Int,
)

sealed interface ProvisionState {
    data object Idle : ProvisionState
    data class Working(val step: String) : ProvisionState
    data class Failed(val reason: String) : ProvisionState
    data class Ready(val credentials: SipCredentials, val detail: ProvisionDetail) : ProvisionState
}

internal class SipFault(message: String) : Exception(message)

/**
 * The whole probe, in one object that outlives the screen.
 *
 * It holds the SIP registration and at most one call. A service would be the right shape
 * for a product and is explicitly out of scope for Phase 0, so the application object
 * owns this and the activity only observes it. Rotating the phone therefore does not
 * drop a call, which matters when the thing being tested is audio.
 */
class Pathfinder(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = PathfinderSettings(appContext)
    private val api = VoipMs(settings)
    private val random = SecureRandom()
    private val cseqLock = Any()

    private val _state = MutableStateFlow<PathState>(PathState.Unregistered)
    val state: StateFlow<PathState> = _state

    private val _stats = MutableStateFlow(MediaStats())
    val stats: StateFlow<MediaStats> = _stats

    private val _answers = MutableStateFlow(ProbeAnswers())
    val answers: StateFlow<ProbeAnswers> = _answers

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val lines = ArrayDeque<String>()

    val apiSettings: PathfinderSettings get() = settings

    /**
     * The API-driven half: the sub-accounts the account owns, and the state of turning
     * one of them into SIP credentials.
     *
     * Kept as state rather than as functions returning values, because the screen has to
     * survive rotation while a request is in flight, and because the password this
     * produces must never end up in a `remember` that is restored from a bundle.
     */
    private val _accounts = MutableStateFlow<List<SubAccount>>(emptyList())
    val accounts: StateFlow<List<SubAccount>> = _accounts

    private val _accountState = MutableStateFlow<AccountListState>(AccountListState.Idle)
    val accountState: StateFlow<AccountListState> = _accountState

    private val _provision = MutableStateFlow<ProvisionState>(ProvisionState.Idle)
    val provision: StateFlow<ProvisionState> = _provision

    private val _calls = MutableStateFlow(CallsView())
    val calls: StateFlow<CallsView> = _calls

    @Volatile private var prepared: SubAccount? = null

    /**
     * The password for [prepared], held in memory only.
     *
     * Not persisted and not logged. It exists so a second write to the same sub-account
     * (the G.722 button) does not have to re-read a secret just to send it back.
     */
    @Volatile private var preparedPassword: String? = null

    @Volatile private var credentials: SipCredentials? = null

    /**
     * Whether the registration is believed live, kept apart from [PathState].
     *
     * The state describes the call as well as the line, so it says `Ended` when a call
     * finishes. Refusing to dial on that basis would mean re-registering between every
     * two calls, which is not what happened on the wire.
     */
    @Volatile private var registered = false
    @Volatile private var sipLink: SipLink? = null
    @Volatile private var localIp: String = ""
    @Volatile private var localSipPort: Int = 0
    @Volatile private var callId: String = ""
    @Volatile private var fromTag: String = ""
    private var cseq = 0

    private val pending = ConcurrentHashMap<String, Channel<SipMessage>>()
    private var sipReceiver: Job? = null
    private var refresh: Job? = null
    private var keepalive: Job? = null

    @Volatile private var currentInvite: SipMessage? = null
    @Volatile private var dialog: Dialog? = null

    /** A confirmed dialog, holding what BYE and re-INVITE have to repeat. */
    private data class Dialog(
        val remoteTarget: String,
        val from: String,
        val to: String,
        val callId: String,
        var cseq: Int,
    )

    @Volatile private var rtpSocket: DatagramSocket? = null
    @Volatile private var audio: CallAudio? = null
    @Volatile private var remoteMedia: InetSocketAddress? = null
    private val jitter = JitterBuffer()
    private val mediaJobs = mutableListOf<Job>()
    private var statsJob: Job? = null

    private var sequence = 0
    private var timestamp = 0L
    @Volatile private var ssrc = 0L

    /** The Expires this client asks for, when it differs from what the account allows. */
    @Volatile private var expiryRequest: Int? = null

    /** The inline key this call put in its offer, base64, needed to decrypt what comes back. */
    @Volatile private var localCryptoKey = ""

    @Volatile private var srtpOut: SrtpContext? = null
    @Volatile private var srtpIn: SrtpContext? = null
    private val framesSent = AtomicLong()
    private val framesReceived = AtomicLong()
    private val datagrams = AtomicLong()
    @Volatile private var sendPeak = 0f
    @Volatile private var receivePeak = 0f

    // ---------------------------------------------------------------- public surface

    /** Registers, replacing any existing registration and ending any call. */
    fun register(credentials: SipCredentials, requestedExpiry: Int? = null) {
        val problem = credentials.problem()
        if (problem != null) {
            note(problem)
            _state.value = PathState.RegistrationFailed(problem)
            return
        }
        scope.launch {
            refresh?.cancel()
            refresh = null
            endCall("replaced by a new registration", failed = false)
            closeTransport()
            this@Pathfinder.credentials = credentials
            expiryRequest = requestedExpiry
            registered = false
            _answers.update { ProbeAnswers(offered = it.offered) }
            _state.value = PathState.Registering(1)
            try {
                openTransport(credentials)
                val granted = doRegister(credentials)
                registered = true
                _state.value = PathState.Registered(granted, System.currentTimeMillis())
                scheduleRefresh(credentials, granted)
            } catch (fault: Exception) {
                registered = false
                note("registration failed: ${describe(fault)}")
                _state.value = PathState.RegistrationFailed(describe(fault))
                closeTransport()
            }
        }
    }

    /** Best-effort deregistration, then the socket goes. */
    fun unregister() {
        scope.launch {
            refresh?.cancel()
            refresh = null
            val credentials = credentials
            if (credentials != null && sipLink != null) {
                runCatching { send(requestRegister(credentials, 0, null, nextCSeq())) }
                note("sent REGISTER with Expires: 0")
            }
            endCall("unregistered", failed = false)
            closeTransport()
            _state.value = PathState.Unregistered
        }
    }

    fun dial(rawNumber: String, mode: OfferMode) {
        val credentials = credentials ?: return fail("Register first.")
        if (!registered) return fail("Not registered right now.")
        if (_state.value is PathState.Connected) return fail("A call is already up.")
        val number = Provider.normalizeNumber(rawNumber)
        if (number.isEmpty()) return fail("Enter a number to dial.")
        settings.lastNumber = rawNumber.trim()
        // A fresh SDES key per call. The answer's own key plus this one is the whole
        // exchange, so a stale key here would mean silence rather than an error.
        localCryptoKey = Sdp.newCryptoKey()
        srtpOut = null
        srtpIn = null
        scope.launch {
            try {
                val socket = LocalNetwork.rtpSocket()
                    ?: throw SipFault("no free RTP port in ${LocalNetwork.RTP_FIRST}-${LocalNetwork.RTP_LAST}")
                rtpSocket = socket
                _answers.update {
                    it.copy(
                        offered = mode.label,
                        protocol = "",
                        encrypted = false,
                        cryptoLines = 0,
                        negotiated = "",
                        payloadTypes = "",
                        ptime = null,
                        remoteMedia = "",
                        inviteMillis = -1,
                    )
                }
                _stats.value = MediaStats(localRtpPort = socket.localPort)
                _state.value = PathState.Dialing(number)
                note("dialing $number, offering ${mode.label}, RTP port ${socket.localPort}")
                invite(credentials, number, mode, socket)
            } catch (fault: Exception) {
                endCall("call failed: ${describe(fault)}", failed = true)
            }
        }
    }

    fun hangUp() {
        when (_state.value) {
            is PathState.Dialing, is PathState.Ringing -> scope.launch { cancelInvite() }
            is PathState.Connected -> scope.launch { bye() }
            else -> Unit
        }
    }

    fun setSpeaker(on: Boolean) {
        audio?.setSpeaker(on)
        _stats.update { it.copy(speaker = on) }
    }

    /** Ends the call, deregisters, and leaves nothing running. */
    fun release() {
        scope.launch {
            refresh?.cancel()
            refresh = null
            endCall("released", failed = false)
            closeTransport()
            _state.value = PathState.Unregistered
        }
    }

    fun warn(text: String) {
        note(text)
    }

    // -------------------------------------------------------------- the API-driven part

    fun saveApi(email: String, password: String) {
        settings.saveApi(email, password)
        note("API credentials saved, sealed with the Keystore")
    }

    fun clearApi() {
        settings.clearApi()
        _accounts.value = emptyList()
        _accountState.value = AccountListState.Idle
        _provision.value = ProvisionState.Idle
        prepared = null
        preparedPassword = null
        note("API credentials removed")
    }

    fun loadAccounts() {
        scope.launch {
            _accountState.value = AccountListState.Loading
            try {
                val list = api.subAccounts()
                _accounts.value = list
                _accountState.value = AccountListState.Loaded
                note("${list.size} sub-account(s) on this VoIP.ms account")
            } catch (fault: Exception) {
                val reason = describe(fault)
                _accountState.value = AccountListState.Failed(reason)
                note("could not list sub-accounts: $reason")
            }
        }
    }

    /**
     * Asks the provider what it recorded for today's calls.
     *
     * This is the only way to learn why a call the provider accepted did not complete:
     * the INVITE tells us what we sent, and this tells us what happened to it, in the
     * provider's own words.
     */
    fun loadCalls() {
        scope.launch {
            _calls.value = CallsView(loading = true)
            try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val now = System.currentTimeMillis()
                val records = api.callRecords(
                    fromDate = format.format(Date(now - DAY_MILLIS)),
                    toDate = format.format(Date(now)),
                )
                _calls.value = CallsView(records = records.take(CALL_RECORDS_SHOWN))
                note("read ${records.size} call record(s) from the provider")
            } catch (fault: Exception) {
                _calls.value = CallsView(error = describe(fault))
                note("could not read the call records: ${describe(fault)}")
            }
        }
    }

    /**
     * Turns one sub-account into credentials.
     *
     * Order matters. The password is read from the list the API already returned and is
     * only written back when it came back empty, because writing is the step with
     * consequences outside this phone. The server is resolved from the provider's own
     * registration status first, then from its recommended POP, because a sub-account
     * does not name a server and asking the user to know one would be a worse answer.
     */
    fun prepare(id: String) {
        scope.launch {
            _provision.value = ProvisionState.Working("reading the sub-account")
            try {
                val list = api.subAccounts()
                _accounts.value = list
                var sub = list.firstOrNull { it.id == id }
                    ?: throw SipFault("that sub-account is no longer on the account")
                var generated = false
                var password = sub.password
                if (password.isNullOrBlank()) {
                    val fresh = Provisioning.generatePassword()
                    note("the API returned no password for ${sub.account}; writing a generated one")
                    _provision.value = ProvisionState.Working("writing a generated password")
                    api.setSubAccount(Provisioning.updateParams(sub, fresh))
                    val verified = api.subAccounts().firstOrNull { it.id == id }
                        ?: throw SipFault("the sub-account disappeared after the password update")
                    if (verified.password != fresh) {
                        throw SipFault("the provider did not keep the generated password")
                    }
                    note("the generated password was accepted; it is held in memory only")
                    sub = verified
                    password = fresh
                    generated = true
                }
                val known = password.orEmpty().ifEmpty {
                    throw SipFault("no SIP password could be read or written")
                }

                _provision.value = ProvisionState.Working("finding the SIP server")
                val status = runCatching { api.registrationStatus(sub.account) }
                    .onFailure { note("registration status unavailable: ${describe(it)}") }
                    .getOrNull() ?: RegistrationStatus(false, null, null, null, null)
                val servers = runCatching { api.servers() }.getOrDefault(emptyList())

                val override = settings.serverOverride
                val source = when {
                    override.isNotBlank() -> "the server you typed"
                    status.serverHostname != null -> "the provider's registration status"
                    servers.any { it.recommended } -> "the POP the provider recommends"
                    servers.isNotEmpty() -> "the first POP the provider listed"
                    else -> "nothing: no server could be found"
                }
                val host = override.ifBlank {
                    Provisioning.chooseServer(status, servers)
                        ?: throw SipFault("no SIP server could be found; type one above and try again")
                }

                val tls = settings.useTls
                val result = Provisioning.credentials(sub, known, host, settings.portFor(tls), tls)
                prepared = sub
                preparedPassword = known
                settings.selectedAccountId = sub.id
                _provision.value = ProvisionState.Ready(
                    credentials = result,
                    detail = ProvisionDetail(
                        generatedPassword = generated,
                        serverSource = source,
                        registered = status.registered,
                        registeredOn = listOfNotNull(status.serverHostname, status.registerIp)
                            .joinToString(" "),
                        transport = status.transport ?: "unknown",
                        sipTraffic = sub.sipTraffic,
                        codecs = sub.allowedCodecs,
                        maxExpiry = sub.maxExpiry,
                    ),
                )
                note("prepared ${result.username} at ${result.host}:${result.port}, codecs ${sub.allowedCodecs}")
                if (sub.sipTraffic) {
                    note("sip_traffic is on for this sub-account; plain UDP registration may be refused")
                }
            } catch (fault: Exception) {
                _provision.value = ProvisionState.Failed(describe(fault))
                note("preparation failed: ${describe(fault)}")
            }
        }
    }

    /**
     * Adds `g722` to the sub-account's allowed codecs, preserving everything else.
     *
     * This is one of only two things that writes to the account, and it is a button rather
     * than a default because it changes what every device on the account may negotiate,
     * not only this probe. The update is built from the freshly read record, so it cannot
     * clear a required setting it did not name.
     */
    fun enableG722() {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        writeCodecs(Provisioning.withCodec(sub.allowedCodecs, Provisioning.G722), "adding g722")
    }

    /**
     * Moves `g722` to the front of the allowed codecs.
     *
     * The provider builds its answer from this list, not from our SDP order: on 2026-11-09
     * an offer of `9 0 101` came back as `0 9 101` while the account allowed
     * `ulaw;g729;g722`. So this is the call that separates "the server prefers PCMU" from
     * "the server follows whatever order the account gives it".
     */
    fun preferG722() {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        writeCodecs(
            Provisioning.withCodecFirst(sub.allowedCodecs, Provisioning.G722),
            "moving g722 to the front",
        )
    }

    /**
     * Turns Encrypted SIP Traffic on or off for the prepared sub-account.
     *
     * It is the single setting that decides whether media is SRTP or in the clear, and the
     * provider applies it to the account rather than to the client: with it off, a
     * sub-account answers even an encrypted offer with plain RTP. Which is why flipping it
     * from here is the fastest way between the two worlds, and why the read-back matters.
     */
    fun setEncryptedSipTraffic(on: Boolean) {
        val sub = prepared ?: return note("prepare a sub-account before changing it")
        if (sub.sipTraffic == on) {
            note("Encrypted SIP Traffic is already ${if (on) "on" else "off"}")
            return
        }
        writeSubAccount(
            sub.copy(sipTraffic = on),
            if (on) "turning encrypted SIP traffic on" else "turning encrypted SIP traffic off",
        )
    }

    /**
     * Writes the sub-account, then lets the read-back decide whether it took.
     *
     * A write that times out says nothing about the write itself. Both attempts on
     * 2026-11-09 timed out at 30 s and the change had to be made in the portal, so the
     * response here is not treated as evidence. The next read is.
     */
    private fun writeCodecs(updated: String, what: String) {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        if (updated == sub.allowedCodecs) {
            note("the sub-account already allows ${sub.allowedCodecs}")
            return
        }
        writeSubAccount(sub.copy(allowedCodecs = updated), what)
    }

    private fun writeSubAccount(updated: SubAccount, what: String) {
        scope.launch {
            val password = preparedPassword
            if (password == null) {
                note("prepare a sub-account before changing it")
                return@launch
            }
            _provision.value = ProvisionState.Working(what)
            note(
                "writing ${updated.id}: codecs ${updated.allowedCodecs}, encrypted SIP " +
                    "${if (updated.sipTraffic) "on" else "off"}, expiry ${updated.maxExpiry} s, " +
                    "canada routing ${updated.canadaRouting}, international ${updated.internationalRoute}",
            )
            var writeFault: Exception? = null
            try {
                api.setSubAccount(Provisioning.updateParams(updated, password))
            } catch (fault: Exception) {
                writeFault = fault // the write may still have landed, so carry on
            }
            writeFault?.let { note("the write did not answer (${describe(it)}), so the read decides") }

            // The record can lag the write, so one read is not evidence either. Three
            // reads, a second and a half apart, and the last one decides. This is what
            // tells "slow to apply" apart from "accepted and ignored".
            var latest: SubAccount? = null
            for (attempt in 1..READ_BACK_ATTEMPTS) {
                if (attempt > 1) delay(READ_BACK_DELAY_MILLIS)
                latest = try {
                    api.subAccounts().firstOrNull { it.id == updated.id }
                } catch (fault: Exception) {
                    note("read-back attempt $attempt failed: ${describe(fault)}")
                    null
                }
                if (latest != null && kept(latest, updated)) break
            }
            val verified = latest
            if (verified == null) {
                _provision.value = ProvisionState.Failed("the sub-account could not be read back")
                note("the sub-account could not be read back, so its state is unknown")
                return@launch
            }
            if (kept(verified, updated)) {
                note(
                    "the sub-account now allows ${verified.allowedCodecs}, encrypted SIP " +
                        "${if (verified.sipTraffic) "on" else "off"}, expiry ${verified.maxExpiry} s",
                )
                prepare(updated.id)
            } else {
                // Name every field that was compared. An earlier version reported only the
                // codecs and the encryption, so a mismatch on the expiry read as though the
                // codecs had been refused, which sent us looking in the wrong place.
                val message = "the provider kept codecs ${verified.allowedCodecs}, encrypted SIP " +
                    "${if (verified.sipTraffic) "on" else "off"} and expiry ${verified.maxExpiry} s; " +
                    "sent codecs ${updated.allowedCodecs}, encrypted SIP " +
                    "${if (updated.sipTraffic) "on" else "off"} and expiry ${updated.maxExpiry} s"
                _provision.value = ProvisionState.Failed(message)
                note(message)
                note(
                    "the write was accepted and the field did not change after " +
                        "${READ_BACK_ATTEMPTS} reads, so this is the API ignoring it rather than lag",
                )
            }
        }
    }

    /** Whether the provider kept the fields this app writes. */
    private fun kept(actual: SubAccount, wanted: SubAccount): Boolean =
        actual.allowedCodecs.equals(wanted.allowedCodecs, ignoreCase = true) &&
            actual.sipTraffic == wanted.sipTraffic &&
            actual.maxExpiry == wanted.maxExpiry

    // ------------------------------------------------------------------------ logging

    private fun note(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lines) {
            for (line in text.trimEnd().split("\n")) lines.addLast("$stamp  $line")
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            _log.value = lines.toList()
        }
        // Also to logcat, because a secondary Android user's files are unreadable to adb
        // while logcat is not. Without this the only way to see a run from another profile
        // is to retype it by hand.
        for (line in text.trimEnd().split("\n")) Log.i(LOGCAT_TAG, "$stamp  $line")
    }

    private fun describe(fault: Throwable): String =
        fault.message?.takeIf { it.isNotBlank() } ?: fault.javaClass.simpleName

    private fun fail(reason: String) {
        note(reason)
        _state.value = PathState.Failed(reason)
    }

    /**
     * The log is meant to be copied into notes, so the digest response goes. It is a
     * hash, not the password, but with the nonce and URI beside it in the same file it
     * is one less thing to think about.
     */
    private fun redact(text: String): String = text
        .replace(Regex("response=\"[0-9a-fA-F]{32}\""), "response=\"<redacted>\"")
        .replace(Regex("(password=)\"[^\"]*\""), "$1\"<redacted>\"")

    // ---------------------------------------------------------------------- transport

    private fun openTransport(credentials: SipCredentials) {
        val binding = LocalNetwork.ipv4()
            ?: throw SipFault("no IPv4 address; connect Wi-Fi or cellular first")
        val link = if (credentials.tls) {
            TlsLink(credentials.host, credentials.port)
        } else {
            val target = InetAddress.getByName(credentials.host)
            UdpLink(DatagramSocket().apply { soTimeout = 0 }, InetSocketAddress(target, credentials.port))
        }

        sipLink = link
        localSipPort = link.localPort
        localIp = binding.address
        callId = "${randomHex(10)}@$localIp"
        fromTag = randomHex(8)
        cseq = randomInt(1, 5000)

        note("local $localIp:${link.localPort} via ${binding.interfaceName}")
        note("${link.transport} to ${credentials.host}:${credentials.port}")
        sipReceiver = scope.launch { receiveSip(link) }
        if (link is TlsLink) {
            // A TLS connection behind a NAT needs traffic to survive the idle timer.
            // Registration refresh is measured in minutes, which is far too slow.
            keepalive = scope.launch {
                while (isActive) {
                    delay(KEEPALIVE_MILLIS)
                    runCatching { link.keepalive() }
                }
            }
        }
    }

    private fun closeTransport() {
        sipReceiver?.cancel()
        sipReceiver = null
        keepalive?.cancel()
        keepalive = null
        pending.clear()
        sipLink?.close()
        sipLink = null
        registered = false
    }

    private fun CoroutineScope.receiveSip(link: SipLink) {
        while (isActive) {
            val incoming = link.receive()
            if (incoming == null) {
                if (isActive) note("SIP connection closed")
                return
            }
            val message = SipMessage.parse(incoming.bytes)
            if (message == null) {
                note("unparsable ${incoming.bytes.size} bytes")
                continue
            }
            note("<<< ${redact(String(message.toBytes(), Charsets.ISO_8859_1))}")
            try {
                handle(message, incoming.source)
            } catch (fault: Exception) {
                note("handling a SIP message failed: ${describe(fault)}")
            }
        }
    }

    private fun handle(message: SipMessage, source: InetSocketAddress?) {
        if (message.start.isRequest) {
            handleRequest(message, source)
            return
        }
        val cseq = SipMessage.cseqOf(message)
        if (cseq == null) {
            note("response without a usable CSeq: ${message.start.firstLine}")
            return
        }
        val channel = pending["${cseq.first} ${cseq.second}"]
        if (channel == null) {
            note("no transaction for ${cseq.first} ${cseq.second}, ignoring it")
            return
        }
        channel.trySend(message)
    }

    private fun handleRequest(message: SipMessage, source: InetSocketAddress?) {
        when (message.start.method) {
            "OPTIONS" -> reply(message, source, 200, "OK")
            "BYE" -> {
                reply(message, source, 200, "OK")
                note("the far end hung up")
                endCall("the far end hung up", failed = false)
            }
            "INVITE" -> {
                // An incoming call is not answered in this phase. Replying 486 is still
                // the proof that the registration is live and reachable, which no
                // status line produced by our own registration can show.
                val count = _answers.value.incomingInvites + 1
                _answers.update { it.copy(incomingInvites = count) }
                note("incoming INVITE #$count from ${message.header("From")}")
                note("this build answers 486 on purpose; incoming calls are Phase B")
                reply(message, source, 486, "Busy Here")
            }
            "CANCEL" -> reply(message, source, 481, "Call/Transaction Does Not Exist")
            else -> reply(message, source, 405, "Method Not Allowed")
        }
    }

    private fun reply(request: SipMessage, source: InetSocketAddress?, code: Int, reason: String) {
        val response = SipMessage.replyingTo(request, code, reason, toTag = randomHex(6))
        send(response, source)
    }

    private fun send(message: SipMessage) {
        send(message, null)
    }

    private fun send(message: SipMessage, target: InetSocketAddress?) {
        val link = sipLink ?: throw SipFault("the SIP transport is closed")
        val bytes = message.toBytes()
        note(">>> ${redact(String(bytes, Charsets.ISO_8859_1))}")
        link.send(bytes, target)
    }

    /**
     * One request/response exchange, with the retransmission UDP needs.
     *
     * A provisional response stops the resends, which is what RFC 3261 requires: after
     * one, the caller waits and the server owns the timers. Without that rule a call
     * rings at the far end while this side INVITEs it again every four seconds.
     */
    private suspend fun transact(
        request: SipMessage,
        timeoutMillis: Long,
        onProvisional: (SipMessage) -> Unit = {},
    ): SipMessage? {
        val cseq = SipMessage.cseqOf(request) ?: throw SipFault("request without CSeq")
        val key = "${cseq.first} ${cseq.second}"
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
                    // A reliable stream owns its own delivery, so re-sending over it
                    // duplicates the request rather than recovering anything.
                    if (heard || sipLink?.reliable == true) {
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

    private fun nextCSeq(): Int = synchronized(cseqLock) { ++cseq }

    private fun branch(): String = "z9hG4bK" + randomHex(10)

    private fun via(branch: String): String {
        val link = sipLink ?: throw SipFault("the SIP transport is closed")
        val rport = if (link.reliable) "" else ";rport"
        return "SIP/2.0/${link.transport} $localIp:$localSipPort;branch=$branch$rport"
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomInt(from: Int, to: Int): Int = from + random.nextInt(to - from + 1)

    // --------------------------------------------------------------------------- calls

    private suspend fun invite(credentials: SipCredentials, number: String, mode: OfferMode, rtp: DatagramSocket) {
        val started = System.currentTimeMillis()
        val uri = Provider.dialUri(credentials.host, number)
        val offer = Sdp.offer(
            System.currentTimeMillis() / 1000,
            localIp,
            rtp.localPort,
            mode,
            cryptoKey = localCryptoKey,
        )
        var authorization: Pair<String, String>? = null
        var cseqValue = nextCSeq()
        var attempt = 0

        while (attempt < 3) {
            attempt++
            val headers = mutableListOf(
                "Via" to via(branch()),
                "Max-Forwards" to "70",
                "From" to "<${Provider.userUri(credentials.host, credentials.username)}>;tag=$fromTag",
                "To" to "<$uri>",
                "Call-ID" to callId,
                "CSeq" to "$cseqValue INVITE",
                "Contact" to Provider.contactUri(
                credentials.host,
                credentials.username,
                localIp,
                localSipPort,
                if (credentials.tls) "tls" else null,
            ),
                "Allow" to "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO",
                "Content-Type" to "application/sdp",
                "User-Agent" to SipMessage.USER_AGENT,
            )
            authorization?.let { headers += it }
            val request = SipMessage.request("INVITE", uri, headers, offer)
            currentInvite = request

            val response = transact(request, INVITE_TIMEOUT_MILLIS) { provisional(number, it) }
            if (response == null) {
                sendCancelFor(request)
                throw SipFault("no answer to INVITE after ${INVITE_TIMEOUT_MILLIS / 1000} s")
            }
            val status = response.start.status ?: throw SipFault("malformed response to INVITE")

            if (status == 200) {
                _answers.update { it.copy(inviteMillis = System.currentTimeMillis() - started) }
                ack(request, response, non2xx = false)
                startMedia(number, response, mode, rtp)
                return
            }
            if (status !in 300..699) {
                ack(request, response, non2xx = false)
                throw SipFault("INVITE answered $status ${response.start.reason}")
            }

            // Everything from 300 up is acknowledged the same way, including the
            // challenge that is about to be answered and retried.
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
                cseqValue = nextCSeq()
                note("$status to INVITE, retrying with $headerName")
                continue
            }
            throw SipFault("INVITE answered $status ${response.start.reason}")
        }
        throw SipFault("gave up after repeated INVITE challenges")
    }

    private fun provisional(number: String, message: SipMessage) {
        when (val status = message.start.status ?: return) {
            100 -> _state.value = PathState.Dialing(number)
            180, 183 -> {
                _state.value = PathState.Ringing(number)
                note("ringing ($status)")
                val early = Sdp.parse(message.body)
                if (early != null) {
                    note("early media: ${early.protocol} ${early.codec?.encoding ?: "unknown"} at ${early.address}:${early.port}")
                }
            }
            else -> note("provisional $status")
        }
    }

    /**
     * ACK, whose branch rules are opposite in the two cases and both mandatory: a
     * non-2xx ACK reuses the INVITE's branch, a 2xx ACK is a new transaction with its
     * own. Getting this backwards produces a retransmitted 200 and, eventually, a BYE.
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
        val cseq = SipMessage.cseqOf(invite) ?: return
        val inviteBranch = SipMessage.branchOf(invite.allHeaders("Via").firstOrNull().orEmpty()) ?: return
        val headers = listOf(
            "Via" to via(inviteBranch),
            "Max-Forwards" to "70",
            "From" to (invite.header("From") ?: return),
            "To" to (invite.header("To") ?: return),
            "Call-ID" to (invite.header("Call-ID") ?: return),
            "CSeq" to "${cseq.first} CANCEL",
            "User-Agent" to SipMessage.USER_AGENT,
        )
        send(SipMessage.request("CANCEL", invite.start.uri ?: return, headers))
        note("CANCEL sent")
    }

    /** The URI inside a Contact header, which may carry a name and parameters. */
    private fun bracket(value: String): String {
        val open = value.indexOf('<')
        val close = value.indexOf('>', open + 1)
        return if (open >= 0 && close > open) value.substring(open + 1, close) else value.trim()
    }

    // --------------------------------------------------------------------------- media

    /**
     * What the answer means, and whether this build can carry it.
     *
     * Today only one outcome is reachable, and it is not a call:
     *
     * 1. Encrypted media, which is every answer while `sip_traffic` is on. The negotiation
     *    is the answer to open question 1, the codec named in the `m=` line is the answer
     *    to open question 2, and the call is silent because decrypting needs the SRTP
     *    layer this phase has not built.
     * 2. PCMU in the clear, or another codec, both unreachable for now. They stay because
     *    they are what a call looks like once SRTP lands and the audio path matters again.
     */
    private fun startMedia(number: String, response: SipMessage, mode: OfferMode, rtp: DatagramSocket) {
        val answer = Sdp.parse(response.body)
            ?: throw SipFault("the 200 OK has no usable SDP: ${response.body.replace("\r\n", " ").take(80)}")
        val codec = answer.codec
        val name = codec?.let { "${it.encoding}/${it.clockRate}" } ?: "unknown"

        _answers.update {
            it.copy(
                protocol = answer.protocol,
                encrypted = answer.encrypted,
                cryptoLines = answer.crypto.size,
                negotiated = name,
                payloadTypes = answer.payloadTypes.joinToString(" "),
                ptime = answer.ptime,
                remoteMedia = "${answer.address ?: "?"}:${answer.port}",
            )
        }
        note("answer: ${answer.protocol}, $name, payloads ${answer.payloadTypes.joinToString(",")}, ptime ${answer.ptime ?: "-"}")
        note("remote media ${answer.address}:${answer.port}, local RTP ${rtp.localPort}")

        val address = answer.address ?: throw SipFault("the SDP answer has no connection address")
        if (answer.port <= 0) throw SipFault("the SDP answer refuses the audio stream (port ${answer.port})")
        remoteMedia = InetSocketAddress(InetAddress.getByName(address), answer.port)
        dialog = Dialog(
            remoteTarget = response.header("Contact")?.let { bracket(it) }
                ?: currentInvite?.start?.uri.orEmpty(),
            from = response.header("From") ?: currentInvite?.header("From").orEmpty(),
            to = response.header("To").orEmpty(),
            callId = response.header("Call-ID") ?: callId,
            cseq = SipMessage.cseqOf(currentInvite ?: response)?.first ?: nextCSeq(),
        )

        if (answer.encrypted) {
            // The answer carries its own key in an a=crypto line. Ours was made for this
            // call, so the two together are a complete SDES exchange and there is nothing
            // else to fetch.
            val remote = answer.crypto.firstNotNullOfOrNull { Sdp.inlineKey(it) }
            val local = Sdp.unbase64(localCryptoKey)
            if (remote == null || local == null ||
                remote.size != Srtp.INLINE_BYTES || local.size != Srtp.INLINE_BYTES
            ) {
                note(
                    "OUTCOME: encrypted media (${answer.protocol}) but the key material is not " +
                        "usable: remote ${remote?.size ?: "none"} bytes, local ${local?.size ?: "none"}",
                )
                _state.value = PathState.Connected(number, name, true, audio = false, at = System.currentTimeMillis())
                return
            }
            srtpOut = SrtpContext(SrtpKey.fromInline(local))
            srtpIn = SrtpContext(SrtpKey.fromInline(remote))
            note(
                "SRTP ${Srtp.SUITE}: both keys from SDP, ${Srtp.TAG_BYTES} byte tags, " +
                    "server chose $name from ${mode.label}",
            )
        } else {
            srtpOut = null
            srtpIn = null
            note("OUTCOME: clear media (${answer.protocol}), server chose $name from ${mode.label}")
        }
        if (codec?.payloadType != G711.PAYLOAD_TYPE) {
            note("OUTCOME: the server chose $name, which this build does not decode (offered ${mode.label})")
            _state.value = PathState.Connected(number, name, false, audio = false, at = System.currentTimeMillis())
            return
        }

        val callAudio = CallAudio(appContext) { note(it) }
        if (!callAudio.start()) {
            note("the audio stack did not start; RTP will still be counted")
            _state.value = PathState.Connected(number, name, false, audio = false, at = System.currentTimeMillis())
            return
        }
        audio = callAudio
        // Start on the speaker. The earpiece is the correct default for a phone call, but
        // this is a probe held away from the ear while somebody reads a screen, and every
        // call so far began with the speaker being switched on by hand. The toggle is one
        // tap away when that changes.
        setSpeaker(true)

        jitter.reset()
        ssrc = random.nextInt().toLong() and 0xFFFFFFFFL
        sequence = randomInt(0, 65535)
        timestamp = random.nextInt().toLong() and 0xFFFFFFFFL
        framesSent.set(0)
        framesReceived.set(0)
        datagrams.set(0)

        mediaJobs += scope.launch { sendLoop(rtp, callAudio) }
        mediaJobs += scope.launch { receiveMedia(rtp) }
        mediaJobs += scope.launch { playoutLoop(callAudio) }
        statsJob = scope.launch { statsLoop() }
        _state.value = PathState.Connected(number, name, false, audio = true, at = System.currentTimeMillis())
        note("call is up; speak")
    }

    private fun CoroutineScope.sendLoop(rtp: DatagramSocket, callAudio: CallAudio) {
        val pcm = ShortArray(G711.FRAME_SAMPLES)
        var first = true
        while (isActive) {
            val read = callAudio.readFrame(pcm)
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
            val target = remoteMedia
            if (target == null) {
                note("no remote media address; stopping the send path")
                return
            }
            // Encryption happens after the frame is built and before it reaches the socket,
            // so the packet this loop reasons about stays the plain one.
            val wire = srtpOut?.let { context ->
                try {
                    context.protect(packet)
                } catch (fault: Exception) {
                    if (isActive) note("SRTP could not protect a frame: ${describe(fault)}")
                    return
                }
            } ?: packet
            try {
                rtp.send(DatagramPacket(wire, wire.size, target))
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

    private fun CoroutineScope.receiveMedia(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
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
            val context = srtpIn
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
                note("first media packet decrypted (${parsed.payload.size} bytes, seq ${parsed.sequence})")
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
    private suspend fun CoroutineScope.playoutLoop(callAudio: CallAudio) {
        val silence = ShortArray(G711.FRAME_SAMPLES)
        var next = System.nanoTime()
        while (isActive) {
            next += G711.FRAME_MILLIS * 1_000_000L
            val payload = jitter.poll()
            if (payload == null) {
                callAudio.write(silence, silence.size)
            } else {
                val pcm = G711.decode(payload)
                callAudio.write(pcm, pcm.size)
                receivePeak = max(receivePeak, G711.peak(pcm, pcm.size))
            }
            val wait = next - System.nanoTime()
            if (wait > 0) {
                delay(wait / 1_000_000L)
            } else if (wait < -200_000_000L) {
                next = System.nanoTime() // badly behind, do not try to catch up in a burst
            }
        }
    }

    private suspend fun CoroutineScope.statsLoop() {
        while (isActive) {
            delay(STATS_MILLIS)
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
                localRtpPort = rtpSocket?.localPort ?: 0,
                remoteMedia = remoteMedia?.let { "${it.address.hostAddress}:${it.port}" }.orEmpty(),
                echoCancelled = audio?.echoCancelled ?: false,
                speaker = audio?.speaker ?: false,
            )
            sendPeak = 0f
            receivePeak = 0f
        }
    }

    // ------------------------------------------------------------------- registration

    private fun requestRegister(
        credentials: SipCredentials,
        expires: Int,
        authorization: Pair<String, String>?,
        cseqValue: Int,
    ): SipMessage {
        val identity = Provider.userUri(credentials.host, credentials.username)
        val headers = mutableListOf(
            "Via" to via(branch()),
            "Max-Forwards" to "70",
            "From" to "<$identity>;tag=$fromTag",
            "To" to "<$identity>",
            "Call-ID" to callId,
            "CSeq" to "$cseqValue REGISTER",
            "Contact" to Provider.contactUri(
                credentials.host,
                credentials.username,
                localIp,
                localSipPort,
                if (credentials.tls) "tls" else null,
            ),
            "Expires" to expires.toString(),
            "User-Agent" to SipMessage.USER_AGENT,
        )
        authorization?.let { headers += it }
        return SipMessage.request("REGISTER", Provider.serverUri(credentials.host), headers)
    }

    private suspend fun doRegister(credentials: SipCredentials): Int {
        // The registrar grants the lesser of this and its own ceiling, so asking for a short
        // expiry is a way to measure the lapse in minutes without touching the account.
        val requested = expiryRequest ?: credentials.expiresSeconds
        var authorization: Pair<String, String>? = null
        var cseqValue = nextCSeq()

        repeat(3) { attempt ->
            val request = requestRegister(credentials, requested, authorization, cseqValue)
            val response = transact(request, timeoutMillis = REGISTER_TIMEOUT_MILLIS)
                ?: throw SipFault(
                    "no answer to REGISTER; UDP to ${credentials.host}:${credentials.port} is not getting through",
                )
            when (val status = response.start.status ?: -1) {
                200 -> {
                    val granted = grantedExpiry(response, requested)
                    _answers.update { it.copy(grantedSeconds = granted) }
                    note("registered, granted $granted s")
                    return granted
                }
                401, 407 -> {
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
                        method = "REGISTER",
                        uri = Provider.serverUri(credentials.host),
                        cnonce = randomHex(8),
                    )
                    cseqValue = nextCSeq()
                    note("$status challenge, answering with $headerName")
                    if (attempt == 2) {
                        throw SipFault("the server keeps answering $status; check the username and password")
                    }
                }
                else -> throw SipFault("REGISTER answered $status ${response.start.reason}")
            }
        }
        throw SipFault("could not register")
    }

    /** What the server actually granted, which is not always what was asked for. */
    private fun grantedExpiry(response: SipMessage, requested: Int): Int {
        response.header("Expires")?.trim()?.toIntOrNull()?.let { if (it > 0) return it }
        response.header("Contact")?.let { contact ->
            Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
                .find(contact)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { if (it > 0) return it }
        }
        return requested
    }

    /**
     * Stops refreshing on purpose and watches the provider's own view until it lapses.
     *
     * This is the measurement behind open question 3, and the only Phase 0 answer that
     * cannot be had from a single call: the grant is 3600 s, we refresh at 80% of it, and
     * nobody has measured how long the provider keeps believing a client that has gone
     * quiet. It is passive, so it costs one tap and an idle phone rather than attention.
     */
    fun measureLapse() {
        val current = credentials ?: return note("register before measuring the lapse")
        val registered = _state.value as? PathState.Registered
            ?: return note("register before measuring the lapse")
        val granted = registered.grantedSeconds
        scope.launch {
            refresh?.cancel()
            refresh = null
            val stopped = System.currentTimeMillis()
            // The binding is counted from the REGISTER, not from when refreshing stopped, so
            // that is the anchor for every number here. Measuring from the stop made the
            // first run of this report a lapse of 26 s against a 60 s grant, which reads as
            // an early drop and was really 60 s from the registration.
            val expiresAt = registered.at + granted * 1000L
            val already = ((stopped - registered.at) / 1000).toInt()
            note(
                "refreshing stopped at ${clock(stopped)}, $already s into a $granted s binding; " +
                    "the provider should drop us at ${clock(expiresAt)}",
            )
            while (isActive) {
                delay(LAPSE_POLL_MILLIS)
                val status = try {
                    api.registrationStatus(current.username)
                } catch (fault: Exception) {
                    note("lapse check failed: ${describe(fault)}")
                    continue
                }
                val sinceRegister = ((System.currentTimeMillis() - registered.at) / 1000).toInt()
                if (!status.registered) {
                    note(
                        "LAPSE: the provider dropped the binding ${sinceRegister} s after the " +
                            "REGISTER against a grant of $granted s, which is " +
                            "${sinceRegister - granted} s past its expiry. That is the number the " +
                            "product's keepalive margin has to survive.",
                    )
                    return@launch
                }
                note("still registered ${sinceRegister} s after the REGISTER")
            }
        }
    }

    private fun clock(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

    /**
     * Refreshes at 80% of the grant, and never less than half a minute. The interval is
     * the number behind open question 3: it is what the registration costs in battery
     * for as long as the app is expected to be reachable.
     */
    private fun scheduleRefresh(credentials: SipCredentials, granted: Int) {
        refresh?.cancel()
        refresh = scope.launch {
            var wait = maxOf(MIN_REFRESH_SECONDS, (granted * 0.8).toLong())
            _answers.update { it.copy(refreshSeconds = wait) }
            note("refresh every $wait s")
            while (isActive) {
                delay(wait * 1000)
                try {
                    val again = doRegister(credentials)
                    if (again != granted) note("the server now grants $again s")
                    wait = maxOf(MIN_REFRESH_SECONDS, (again * 0.8).toLong())
                    _answers.update { it.copy(refreshSeconds = wait) }
                    registered = true
                    _state.value = PathState.Registered(again, System.currentTimeMillis())
                } catch (fault: Exception) {
                    registered = false
                    note("registration refresh failed: ${describe(fault)}")
                    _state.value = PathState.RegistrationFailed(describe(fault))
                    return@launch
                }
            }
        }
    }


    /**
     * BYE, with one retry if it is challenged. Ending the local call is not conditional
     * on the server answering: the audio and the socket are ours to stop, and a probe
     * that hangs on a lost BYE is worse than one that leaves a stale dialog at the far
     * end, which the provider's own timeout clears.
     */
    private suspend fun bye() {
        val current = dialog
        if (current == null) {
            endCall("no confirmed dialog to end", failed = false)
            return
        }
        var cseqValue = ++current.cseq
        var authorization: Pair<String, String>? = null
        for (attempt in 0 until 2) {
            val headers = mutableListOf(
                "Via" to via(branch()),
                "Max-Forwards" to "70",
                "From" to current.from,
                "To" to current.to,
                "Call-ID" to current.callId,
                "CSeq" to "$cseqValue BYE",
                "User-Agent" to SipMessage.USER_AGENT,
            )
            authorization?.let { headers += it }
            val request = SipMessage.request("BYE", current.remoteTarget, headers)
            val response = transact(request, BYE_TIMEOUT_MILLIS)
            if (response == null) {
                note("no answer to BYE")
                break
            }
            when (val status = response.start.status ?: 0) {
                200 -> {
                    note("call ended by BYE")
                    break
                }
                401, 407 -> {
                    val challengeName = if (status == 401) "WWW-Authenticate" else "Proxy-Authenticate"
                    val headerName = if (status == 401) "Authorization" else "Proxy-Authorization"
                    val challenge = response.header(challengeName)?.let { Digest.parse(it) }
                    if (challenge == null) {
                        note("BYE challenged without a usable $challengeName")
                        break
                    }
                    val known = credentials
                    authorization = headerName to Digest.authorization(
                        challenge = challenge,
                        username = known?.username.orEmpty(),
                        password = known?.password.orEmpty(),
                        method = "BYE",
                        uri = current.remoteTarget,
                        cnonce = randomHex(8),
                    )
                    cseqValue = ++current.cseq
                    if (attempt == 1) note("BYE kept its challenge; ending locally anyway")
                }
                else -> {
                    note("BYE answered $status ${response.start.reason}")
                    break
                }
            }
        }
        endCall("hung up", failed = false)
    }

    private fun cancelInvite() {
        currentInvite?.let { sendCancelFor(it) }
        endCall("canceled", failed = false)
    }

    private fun endCall(reason: String, failed: Boolean) {
        // The counters belong in the log, not only in the report file: a run on a secondary
        // Android user cannot be read over adb from its files, and logcat is the channel
        // that works from every profile.
        note(
            "media totals: ${framesSent.get()} sent, ${framesReceived.get()} received, " +
                "${datagrams.get()} datagrams, ${srtpIn?.rejected ?: 0} srtp rejected",
        )
        mediaJobs.forEach { it.cancel() }
        mediaJobs.clear()
        statsJob?.cancel()
        statsJob = null
        audio?.stop()
        audio = null
        rtpSocket?.close()
        rtpSocket = null
        remoteMedia = null
        currentInvite = null
        dialog = null
        jitter.reset()
        if (reason.isNotEmpty()) note(reason)
        _state.value = if (failed) PathState.Failed(reason) else PathState.Ended(reason)
    }

    // --------------------------------------------------------------------------- report

    /**
     * Writes the report and the log into this app's own files directory and returns the
     * directory, so a machine with adb can pull them:
     *
     *     adb pull /sdcard/Android/data/com.willingtocode.voicepathfinder/files/runs
     *
     * This exists because reading a SIP exchange off a screenshot is both lossy and the
     * wrong way to handle anything that names an account. The files hold the SIP
     * identities of the call, the way any SIP log does, and no password.
     */
    fun export(): String? {
        val directory = File(appContext.getExternalFilesDir(null), "runs")
        val body = report()
        // Straight to logcat as well, so `adb logcat -s VoicePathfinder` carries the whole
        // report out of a profile whose files adb cannot read.
        for (line in body.split("\n")) Log.i(LOGCAT_TAG, "report  $line")
        return runCatching {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(directory, "report-$stamp.txt").writeText(body)
            File(directory, "log-$stamp.txt").writeText(
                synchronized(lines) { lines.joinToString("\n") },
            )
            note("saved the report and log under ${directory.absolutePath}")
            directory.absolutePath
        }.onFailure {
            note("could not save the report: ${describe(it)}")
        }.getOrNull()
    }

    /**
     * The finding, in a form that can be pasted into `notes/STATUS.md`.
     *
     * Every line is something the run actually reported, and a line with no value says so
     * rather than guessing. The SIP password is not here, and neither is the API password:
     * a report is for sharing.
     */
    fun report(): String {
        val answers = _answers.value
        val stats = _stats.value
        val detail = (_provision.value as? ProvisionState.Ready)?.detail
        val prepared = prepared
        val builder = StringBuilder()
        fun line(text: String) {
            builder.append(text).append('\n')
        }
        line("Voice Pathfinder report")
        line(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})",
        )
        line("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        line(
            "sub-account: " + if (prepared != null) {
                "${prepared.label} (${prepared.account}), codecs ${prepared.allowedCodecs}, " +
                    "caller ${prepared.callerId.ifBlank { "none" }}, " +
                    "dialing ${Provisioning.dialingModeLabel(prepared.dialingMode)}"
            } else {
                "-"
            },
        )
        line("sip_traffic: ${if (detail == null) "-" else if (detail.sipTraffic) "on" else "off"}")
        line("server: ${credentials?.let { "${it.host}:${it.port} ${it.transport}" } ?: "-"}")
        line("server source: ${detail?.serverSource ?: "-"}")
        line(
            "credentials: " + when {
                detail == null -> "-"
                detail.generatedPassword -> "password generated and written back by this app"
                else -> "password read from the API, not stored"
            },
        )
        line("state: ${stateText(_state.value)}")
        line("offered: ${answers.offered.ifEmpty { "-" }}")
        line(
            "registration: " + if (answers.grantedSeconds > 0) {
                "granted ${answers.grantedSeconds} s, refreshing every ${answers.refreshSeconds} s"
            } else {
                "not registered"
            },
        )
        line("provider registration status: ${detail?.let { if (it.registered) "registered ${it.registeredOn}" else "not registered elsewhere" } ?: "-"}")
        line("provider transport: ${detail?.transport ?: "-"}")
        line("media protocol: ${answers.protocol.ifEmpty { "-" }}")
        line(
            "media encrypted: " + when {
                answers.protocol.isEmpty() -> "unknown"
                answers.encrypted -> "YES (${answers.cryptoLines} crypto line(s))"
                else -> "no"
            },
        )
        line(
            "srtp: " + if (srtpIn != null) {
                "${Srtp.SUITE}, ${Srtp.TAG_BYTES} byte tags, decrypting both ways"
            } else {
                "not in use"
            },
        )
        line(
            "negotiated: ${answers.negotiated.ifEmpty { "-" }} " +
                "(payloads ${answers.payloadTypes.ifEmpty { "-" }}, ptime ${answers.ptime ?: "-"})",
        )
        line("remote media: ${answers.remoteMedia.ifEmpty { "-" }}")
        line("INVITE to 200 OK: ${if (answers.inviteMillis >= 0) "${answers.inviteMillis} ms" else "-"}")
        line("incoming INVITEs seen: ${answers.incomingInvites}")
        line(
            "media: ${stats.framesSent} sent, ${stats.framesReceived} received, " +
                "${stats.lost} lost, ${stats.late} late, ${stats.duplicates} duplicated, " +
                "${stats.reordered} reordered",
        )
        // Datagrams and rejections are what tell "the far end sent nothing" apart from
        // "packets arrived and we refused them". Without both, silence is unattributable.
        line(
            "rtp datagrams: ${datagrams.get()}, srtp rejected: ${srtpIn?.rejected ?: 0}",
        )
        line("audio: ${if (stats.echoCancelled) "echo cancellation on" else "echo cancellation off"}")

        val records = _calls.value.records
        if (records.isNotEmpty()) {
            line("provider call records:")
            for (record in records.take(5)) {
                line("  ${record.date}  ${record.disposition}  ${record.description}")
                for (detail in record.logs.lines().take(6)) {
                    if (detail.isNotBlank()) line("    ${detail.trim()}")
                }
            }
        }
        return builder.toString()
    }

    private fun stateText(state: PathState): String = when (state) {
        PathState.Unregistered -> "not registered"
        is PathState.Registering -> "registering"
        is PathState.Registered -> "registered"
        is PathState.RegistrationFailed -> "registration failed: ${state.reason}"
        is PathState.Dialing -> "dialing ${state.number}"
        is PathState.Ringing -> "ringing ${state.number}"
        is PathState.Connected -> if (state.audio) "connected" else "connected without audio"
        is PathState.Ended -> "ended: ${state.reason}"
        is PathState.Failed -> "failed: ${state.reason}"
    }

    private companion object {
        const val MAX_LOG_LINES = 600
        const val FIRST_RETRANSMIT_MILLIS = 500L
        const val MAX_RETRANSMIT_MILLIS = 4_000L
        const val REGISTER_TIMEOUT_MILLIS = 16_000L
        const val INVITE_TIMEOUT_MILLIS = 45_000L
        const val BYE_TIMEOUT_MILLIS = 6_000L

        /** The logcat tag, so a run can be read from another Android user over adb. */
        const val LOGCAT_TAG = "VoicePathfinder"

        /** How often the lapse measurement asks the provider what it thinks. */
        const val LAPSE_POLL_MILLIS = 5_000L

        /** Reads after an account write: the record can lag the write by a second or two. */
        const val READ_BACK_ATTEMPTS = 3
        const val READ_BACK_DELAY_MILLIS = 1_500L
        const val MIN_REFRESH_SECONDS = 30L
        const val STATS_MILLIS = 250L
        const val KEEPALIVE_MILLIS = 25_000L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val CALL_RECORDS_SHOWN = 8
    }
}

